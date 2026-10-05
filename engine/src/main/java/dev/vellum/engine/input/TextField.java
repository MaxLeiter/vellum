package dev.vellum.engine.input;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.event.InputEvent;
import dev.vellum.engine.event.Modifiers;
import dev.vellum.engine.host.FontSpec;
import dev.vellum.engine.host.Host;
import dev.vellum.engine.layout.Box;
import dev.vellum.engine.layout.TextMeasure;
import dev.vellum.engine.style.ComputedStyle;

import java.util.Locale;
import java.util.function.IntPredicate;

/**
 * Behaviour of a text control (a text-like input or a textarea): keys, typed characters, clipboard, caret placement
 * and selection by mouse, the {@code beforeinput}/{@code input}/{@code change} events, caret blink, and scrolling to
 * keep the caret visible. One instance per element, kept in {@code element.controlState}. The text model is a
 * {@link TextEditor}; the visual lines are a {@link TextLayout} (soft-wrapped for textareas).
 *
 * <p>The text scrolls by the element's own scroll offsets: the box's scrollable overflow is the text's extent
 * ({@link #extend}), so the wheel, scrollbars, {@code scrollTop} and {@code scroll} events work as for any scroll
 * container (the UA stylesheet makes inputs {@code overflow: hidden} and textareas {@code overflow: auto}).
 */
final class TextField {
    private static final double BLINK_MS = 530;
    /** Characters a number input accepts while typing (validity is the author's business, as in browsers). */
    private static final IntPredicate NUMBER_CHARS = cp -> cp >= '0' && cp <= '9' || "+-.eE".indexOf(cp) >= 0;

    final Element element;
    final TextEditor editor;
    final boolean multiline;
    private String valueAtFocus;
    private boolean caretOn = true;
    private double blinkStart;
    /** Caret x kept across consecutive Up/Down moves, or NaN. */
    private float goalX = Float.NaN;
    private TextLayout layout, placeholderLayout;
    /** The document and value versions {@link #sync} last read the element at. */
    private int domVersion = -1, valueVersion = -1;

    private TextField(Element element) {
        this.element = element;
        this.multiline = element.tagName().equals("textarea");
        this.editor = new TextEditor(multiline);
    }

    /**
     * The field of a text control, created on first use. It reads the element's value and attributes again only
     * when they may have changed: after a DOM change or a new value.
     */
    static TextField of(Element element) {
        TextField field = element.controlState instanceof TextField f ? f : new TextField(element);
        element.controlState = field;
        if (field.domVersion != element.ownerDocument().domVersion() || field.valueVersion != element.valueVersion()) {
            field.sync();
        }
        return field;
    }

    private void sync() {
        domVersion = element.ownerDocument().domVersion();
        valueVersion = element.valueVersion();
        String value = element.value();
        if (!value.equals(editor.text())) {
            editor.reset(value);
            extend(); // a new value (set by a script, say) is a new scroll extent
        }
        editor.setMaxLength((int) Forms.number(element.getAttribute("maxlength"), -1));
        editor.setFilter(element.inputType().equals("number") ? NUMBER_CHARS : null);
    }

    // ---- Geometry (shared with Controls.paint) ----

    boolean masked() {
        return element.inputType().equals("password");
    }

    FontSpec font() {
        return FontSpec.of(element.computedStyle());
    }

    TextMeasure measure() {
        return Controls.measure(element);
    }

    /**
     * The current value's lines (rebuilt when the text, font, spacing or width changes, which also updates the
     * box's scroll extent).
     */
    TextLayout layout() {
        TextLayout l = lines(layout, editor.text(), masked());
        if (l != layout) {
            layout = l;
            extend();
        }
        return l;
    }

    /** The placeholder's lines, laid out like the value. */
    TextLayout placeholderLayout(String placeholder) {
        return placeholderLayout = lines(placeholderLayout, placeholder, false);
    }

    /** {@code cached} if it lays out {@code text} as this field would now, else a new layout: wrapped in a textarea. */
    private TextLayout lines(TextLayout cached, String text, boolean masked) {
        float wrap = multiline && element.box != null ? element.box.contentWidth() : Float.POSITIVE_INFINITY;
        ComputedStyle s = element.computedStyle();
        FontSpec font = FontSpec.of(s);
        if (cached != null && cached.matches(text, masked, wrap, font, s)) return cached;
        return new TextLayout(text, masked, wrap, measure(), font, s);
    }

    /**
     * Makes the box's scrollable overflow the value's text (with room for the caret after it), so the element's
     * scroll offsets move the text: down a textarea's lines, along an input's line. Runs after layout
     * ({@link Controls#overflow}) and whenever the text changes, and keeps the offsets in the new range.
     */
    void extend() {
        Box box = element.box;
        TextLayout l = layout();
        if (box == null) return;
        if (multiline) {
            box.scrollHeight = Math.max(box.paddingBoxHeight(),
                    box.paddingTop + l.lineCount() * lineHeight() + box.paddingBottom);
        } else {
            box.scrollWidth = Math.max(box.paddingBoxWidth(), box.paddingLeft + l.width() + 1 + box.paddingRight);
        }
        element.clampScroll();
    }

    /** Whether text laid out as {@code l} can show outside the content box, so painting must clip it. */
    boolean overflows(TextLayout l) {
        Box box = element.box;
        return element.scrollLeft() > 0 || element.scrollTop() > 0 || l.width() + 1 > box.contentWidth()
                || l.lineCount() * lineHeight() > box.contentHeight() || glyphHeight() > lineHeight();
    }

    /** Height of a visual line: the line height in a textarea; the content height in an input (text is centred in it). */
    float lineHeight() {
        return element.box == null ? glyphHeight()
                : Controls.lineHeight(element, element.computedStyle(), element.box.contentHeight());
    }

    float glyphHeight() {
        return measure().glyphHeight(font());
    }

    /** Left edge of the text in border-box coordinates: the content box, scrolled. Requires a box. */
    float textX() {
        return element.box.contentX() - element.scrollLeft();
    }

    /** Top of line {@code i}'s glyph box in border-box coordinates (centred in its line height). Requires a box. */
    float glyphY(int line) {
        return Controls.glyphTop(element.box.contentY() - element.scrollTop(), lineHeight(), glyphHeight(), line);
    }

    boolean caretOn() {
        return caretOn;
    }

    // ---- Focus and time ----

    void focused() {
        valueAtFocus = editor.text();
        resetBlink();
    }

    void blurred() {
        commitChange();
        valueAtFocus = null;
        if (!multiline) element.scrollTo(0, 0);
    }

    void blink(double now) {
        caretOn = (now - blinkStart) % (2 * BLINK_MS) < BLINK_MS;
    }

    /** Fires {@code change} if the value differs from when the field was focused (on blur, or Enter in an input). */
    private void commitChange() {
        String value = editor.text();
        if (valueAtFocus != null && !value.equals(valueAtFocus)) {
            valueAtFocus = value;
            element.dispatchEvent(new InputEvent("change", null, null));
        }
    }

    // ---- Keyboard ----

    /** The default action of a keydown while focused. Consumes every key except Escape. */
    boolean keyDown(String key, Modifiers mods) {
        if (key.equals("Escape")) return false;
        if (mods.shortcut() && shortcut(key.toLowerCase(Locale.ROOT), mods.shift())) return true;
        boolean extend = mods.shift();
        boolean word = mods.alt() || mods.ctrl();
        switch (key) {
            case "ArrowLeft", "ArrowRight" -> {
                boolean forward = key.equals("ArrowRight");
                if (mods.meta()) moveToLineEdge(forward, extend); // Cmd+Left/Right on macOS
                else {
                    editor.moveHorizontally(forward, word, extend);
                    caretMoved();
                }
            }
            case "ArrowUp", "ArrowDown" -> moveVertically(key.equals("ArrowUp") ? -1 : 1, extend);
            case "PageUp", "PageDown" -> moveVertically((key.equals("PageUp") ? -1 : 1) * visibleLines(), extend);
            case "Home", "End" -> {
                if (mods.ctrl() || mods.meta()) moveCaret(key.equals("Home") ? 0 : editor.text().length(), extend);
                else moveToLineEdge(key.equals("End"), extend);
            }
            case "Backspace", "Delete" -> perform(editor.planDelete(key.equals("Delete"), word));
            case "Enter" -> {
                if (multiline) perform(editor.planInsert("\n", "insertLineBreak"));
                else commitChange();
            }
            default -> { }
        }
        return true;
    }

    /** Typed text (already composed). */
    void type(String text) {
        perform(editor.planInsert(text, "insertText"));
    }

    private boolean shortcut(String key, boolean shift) {
        switch (key) {
            case "a" -> selectAll();
            case "c" -> copy();
            case "x" -> { if (copy()) perform(editor.planDeleteSelection("deleteByCut")); }
            case "v" -> perform(editor.planInsert(host().getClipboard(), "insertFromPaste"));
            case "z" -> history(!shift);
            case "y" -> history(false);
            default -> { return false; }
        }
        return true;
    }

    void selectAll() {
        editor.selectAll();
        caretMoved();
    }

    /** Copies the selection; password fields never copy. */
    private boolean copy() {
        if (!editor.hasSelection() || masked()) return false;
        host().setClipboard(editor.selectedText());
        return true;
    }

    private void history(boolean undo) {
        String type = undo ? "historyUndo" : "historyRedo";
        if (!Forms.isEditable(element) || !(undo ? editor.canUndo() : editor.canRedo()) || !beforeInput(null, type)) return;
        if (undo) editor.undo(); else editor.redo();
        changed(type, null);
    }

    private void moveToLineEdge(boolean end, boolean extend) {
        TextLayout l = layout();
        TextLayout.Line line = l.line(l.lineOf(editor.caret()));
        moveCaret(end ? l.caretEnd(line) : line.start(), extend);
    }

    /** Up/Down by visual lines, keeping the caret's x; past the first or last line it goes to the start or end. */
    private void moveVertically(int lines, boolean extend) {
        TextLayout l = layout();
        float x = Float.isNaN(goalX) ? l.x(editor.caret()) : goalX;
        int line = l.lineOf(editor.caret()) + lines;
        moveCaret(line < 0 ? 0 : line >= l.lineCount() ? editor.text().length() : l.offsetAt(line, x), extend);
        goalX = x;
    }

    private int visibleLines() {
        return element.box == null ? 1 : Math.max(1, (int) (element.box.contentHeight() / lineHeight()));
    }

    // ---- Mouse ----

    /**
     * Mousedown at a point in the box's border-box coordinates: places the caret (shift extends the selection),
     * selects a word on double-click and the line (textarea) or everything on triple-click. Returns the drag that
     * extends the selection.
     */
    Drag press(float x, float y, int clicks, boolean extend) {
        int offset = offsetAt(x, y);
        if (clicks == 2) {
            int[] w = editor.wordAt(offset);
            editor.select(w[0], w[1]);
        } else if (clicks >= 3) {
            if (multiline) editor.select(editor.lineStart(offset), editor.lineEnd(offset));
            else editor.selectAll();
        } else editor.moveTo(offset, extend);
        caretMoved();
        boolean[] moved = {false};
        return (px, py) -> {
            if (element.box == null) return;
            float[] local = Dom.local(element.box, px, py);
            int o = offsetAt(local[0], local[1]);
            // Ignore the pointer resting where it was pressed, so a double-click's word selection survives.
            if (!moved[0] && o == offset) return;
            moved[0] = true;
            moveCaret(o, true);
        };
    }

    /** The caret offset nearest a point in border-box coordinates. */
    private int offsetAt(float x, float y) {
        Box box = element.box;
        if (box == null || Float.isNaN(x)) return editor.text().length();
        float ly = y - box.contentY() + element.scrollTop();
        return layout().offsetAt(multiline ? (int) Math.floor(ly / lineHeight()) : 0, x - textX());
    }

    // ---- Editing ----

    /** Fires beforeinput, applies the edit unless cancelled, then fires input. */
    private void perform(TextEditor.Edit edit) {
        if (edit == null || !Forms.isEditable(element) || !beforeInput(edit.data(), edit.inputType())) return;
        editor.apply(edit);
        changed(edit.inputType(), edit.data());
    }

    /** False when beforeinput was cancelled or a listener replaced the value (making the planned edit stale). */
    private boolean beforeInput(String data, String inputType) {
        String before = editor.text();
        boolean proceed = element.dispatchEvent(new InputEvent("beforeinput", data, inputType));
        sync();
        return proceed && editor.text().equals(before);
    }

    private void changed(String inputType, String data) {
        element.setValue(editor.text());
        caretMoved();
        element.dispatchEvent(new InputEvent("input", data, inputType));
    }

    private void moveCaret(int offset, boolean extend) {
        editor.moveTo(offset, extend);
        caretMoved();
    }

    private void caretMoved() {
        goalX = Float.NaN;
        resetBlink();
        scrollToCaret();
    }

    private void resetBlink() {
        blinkStart = element.ownerDocument().scheduler().now();
        caretOn = true;
    }

    /** Scrolls the least amount that shows the caret (with room for its 1px width). */
    void scrollToCaret() {
        Box box = element.box;
        if (box == null) return;
        TextLayout l = layout();
        int caret = editor.caret();
        float left = element.scrollLeft(), top = element.scrollTop();
        if (multiline) {
            float h = lineHeight(), lineTop = l.lineOf(caret) * h;
            top = clamp(top, lineTop + h - box.contentHeight(), lineTop);
        } else {
            float x = l.x(caret);
            left = clamp(left, x + 1 - box.contentWidth(), x);
        }
        element.scrollTo(left, top);
    }

    private Host host() {
        return element.ownerDocument().host();
    }

    /** Clamps into [lo, hi], preferring {@code hi} when the range is empty. */
    private static float clamp(float v, float lo, float hi) {
        return Math.min(hi, Math.max(lo, v));
    }
}
