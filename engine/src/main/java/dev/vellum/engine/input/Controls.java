package dev.vellum.engine.input;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.FontSpec;
import dev.vellum.engine.layout.Box;
import dev.vellum.engine.layout.TextMeasure;
import dev.vellum.engine.paint.Canvas;
import dev.vellum.engine.paint.TextPainter;
import dev.vellum.engine.style.Colors;
import dev.vellum.engine.style.ComputedStyle;

/**
 * Painting of built-in form controls (text in inputs and textareas with caret and selection, checkbox ticks, range
 * thumbs, select labels and arrows, progress/meter fills, placeholders). The painter calls {@link #paint} after a
 * control's background and border, inside its transform/opacity, with the style it paints the box with.
 * Backgrounds (the vanilla sprites from the UA stylesheet) are the painter's; this draws only what CSS cannot. Text
 * goes through the painter's {@link TextPainter}, so it has the same shadows, decorations, spacing and snapping as
 * any text; what a control draws is kept in its {@code controlState} between frames.
 */
public final class Controls {
    private static final String THUMB = "minecraft:widget/slider_handle";
    private static final String THUMB_HIGHLIGHTED = "minecraft:widget/slider_handle_highlighted";
    private static final String ARROW = "▼";
    private static final float PLACEHOLDER_ALPHA = 0.5f, SELECTION_ALPHA = 0.45f;

    private Controls() {}

    /** True for elements whose content is drawn by {@link #paint} rather than laid out from children. */
    public static boolean isControl(String tag) {
        return switch (tag) {
            case "input", "textarea", "select", "progress", "meter" -> true;
            default -> false;
        };
    }

    // ---- Text geometry, shared with layout (baselines) and text fields ----

    /**
     * How far apart a control's text lines are, each centred in that height: a textarea's line height; the content
     * height of a single-line control, whose one line is centred in its content box.
     */
    public static float lineHeight(Element control, ComputedStyle s, float contentHeight) {
        return control.tagName().equals("textarea") ? s.usedLineHeight() : contentHeight;
    }

    /** Top of the glyph box of text line {@code line}, the lines starting at {@code top} {@code lineHeight} apart. */
    public static float glyphTop(float top, float lineHeight, float glyphHeight, int line) {
        return top + line * lineHeight + (lineHeight - glyphHeight) / 2;
    }

    /**
     * Makes a text control's scrollable overflow its text, once layout sized the box (layout gives a control no
     * content of its own), so its scroll offsets bound the text like any scroll container's.
     */
    public static void overflow(Box box) {
        Element e = box.element;
        if (e != null && box.kind == Box.Kind.BLOCK && e.isTextControl()) TextField.of(e).extend();
    }

    // ---- Painting ----

    /** Paints the control's content into {@code box} (its border-box origin at 0,0) with style {@code s}. */
    public static void paint(Canvas canvas, Box box, ComputedStyle s) {
        Element el = box.element;
        if (el == null) return;
        switch (el.tagName()) {
            case "textarea" -> paintText(canvas, box, s, TextField.of(el));
            case "select" -> paintSelect(canvas, box, s, el);
            case "progress", "meter" -> paintBar(canvas, box, s, el);
            case "input" -> {
                if (el.isTextControl()) paintText(canvas, box, s, TextField.of(el));
                else switch (el.inputType()) {
                    case "checkbox", "radio" -> paintCheck(canvas, box, s, el);
                    case "range" -> paintRange(canvas, box, s, el);
                    case "submit", "reset", "button" -> paintButton(canvas, box, s, el);
                    default -> { }
                }
            }
            default -> { }
        }
    }

    /** Value (or placeholder), selection highlight and caret of a text field, scrolled by the element's offsets. */
    private static void paintText(Canvas canvas, Box box, ComputedStyle s, TextField field) {
        Element el = field.element;
        TextEditor editor = field.editor;
        TextLayout value = field.layout();
        String placeholder = el.getAttribute("placeholder");
        boolean showPlaceholder = editor.text().isEmpty() && placeholder != null;
        TextLayout shown = showPlaceholder ? field.placeholderLayout(placeholder) : value;
        boolean clip = clipIf(canvas, box, field.overflows(shown));
        if (showPlaceholder) {
            ComputedStyle ps = el.placeholderStyle;
            drawLines(canvas, box, field, shown, ps != null ? ps : s,
                    ps != null ? ps.color : Colors.withAlphaFactor(s.color, PLACEHOLDER_ALPHA));
        } else {
            if (el.isFocused() && editor.hasSelection()) paintSelection(canvas, field, value, s);
            drawLines(canvas, box, field, value, s, s.color);
        }
        if (el.isFocused() && field.caretOn() && !editor.hasSelection()) {
            int caret = editor.caret();
            canvas.fillRect(TextPainter.snap(canvas, field.textX() + value.x(caret)),
                    TextPainter.snap(canvas, field.glyphY(value.lineOf(caret))), 1, field.glyphHeight(), s.color);
        }
        if (clip) canvas.restore();
    }

    private static void paintSelection(Canvas canvas, TextField field, TextLayout layout, ComputedStyle s) {
        TextEditor editor = field.editor;
        int color = Colors.withAlphaFactor(s.accentColor, SELECTION_ALPHA);
        float x0 = field.textX(), newline = field.measure().width(" ", field.font(), s);
        for (int i = 0; i < layout.lineCount(); i++) {
            TextLayout.Line line = layout.line(i);
            int from = Math.max(editor.selectionStart(), line.start());
            int to = Math.min(editor.selectionEnd(), line.end());
            if (from > to) continue;
            // A selection running past a hard line end includes the newline: show it as a space's width.
            float extra = editor.selectionEnd() > line.end() && !line.softWrapped() ? newline : 0;
            float x = line.x(from), w = line.x(to) - x + extra;
            if (w > 0) {
                float left = TextPainter.snap(canvas, x0 + x);
                canvas.fillRect(left, TextPainter.snap(canvas, field.glyphY(i)),
                        TextPainter.snap(canvas, x0 + x + w) - left, field.glyphHeight(), color);
            }
        }
    }

    private static void drawLines(Canvas canvas, Box box, TextField field, TextLayout layout, ComputedStyle s, int color) {
        float x = field.textX(), lineHeight = field.lineHeight();
        FontSpec font = field.font();
        for (int i = 0; i < layout.lineCount(); i++) {
            float y = field.glyphY(i);
            if (y + lineHeight < 0 || y - lineHeight > box.height) continue; // scrolled out (the clip trims the rest)
            TextLayout.Line line = layout.line(i);
            TextPainter.draw(canvas, line.display(), line.spaced(), x, y, font, s, color);
        }
    }

    /** The UA stylesheet draws checkboxes and radios with sprites; without a background, draw a plain mark. */
    private static void paintCheck(Canvas canvas, Box box, ComputedStyle s, Element el) {
        if (s.hasBackgroundImage() || !el.checked()) return;
        float inset = Math.max(1, Math.min(box.contentWidth(), box.contentHeight()) / 4);
        float x = box.contentX() + inset, y = box.contentY() + inset;
        float w = box.contentWidth() - 2 * inset, h = box.contentHeight() - 2 * inset;
        if (el.inputType().equals("radio")) {
            float r = Math.min(w, h) / 2;
            canvas.fillRoundedRect(x, y, w, h, new float[] {r, r, r, r, r, r, r, r}, s.accentColor);
        } else {
            canvas.fillRect(x, y, w, h, s.accentColor);
        }
    }

    /** The vanilla slider handle at the value, plus an optional "label: value" caption like vanilla sliders. */
    private static void paintRange(Canvas canvas, Box box, ComputedStyle s, Element el) {
        RangeControl range = RangeControl.of(el);
        String sprite = el.isHovered() || el.isActive() || el.isFocused() ? THUMB_HIGHLIGHTED : THUMB;
        canvas.drawSprite(sprite, RangeControl.thumbX(box, range.fraction()), box.contentY(),
                RangeControl.THUMB_WIDTH, box.contentHeight(), s.tint);
        String label = el.getAttribute("label");
        if (label != null) drawCentred(canvas, box, s, range.label.set(range.caption(label), FontSpec.of(s), s, measure(el)));
    }

    /** The selected option's label, and a down arrow at the right. */
    private static void paintSelect(Canvas canvas, Box box, ComputedStyle s, Element el) {
        SelectState state = el.controlState instanceof SelectState st ? st : new SelectState();
        el.controlState = state;
        FontSpec font = FontSpec.of(s);
        TextMeasure measure = measure(el);
        float y = textTop(box, measure.glyphHeight(font));
        Label arrow = state.arrow.set(ARROW, font, s, measure);
        float arrowX = box.contentX() + box.contentWidth() - arrow.width();
        arrow.draw(canvas, arrowX, y, s, s.color);
        String option = state.option(el);
        if (option == null) return;
        Label label = state.label.set(option, font, s, measure);
        float space = Math.max(0, arrowX - 2 - box.contentX());
        boolean clip = label.width() > space || measure.glyphHeight(font) > box.paddingBoxHeight();
        if (clip) {
            canvas.save();
            canvas.clipRect(box.contentX(), box.borderTop, space, box.paddingBoxHeight());
        }
        label.draw(canvas, box.contentX(), y, s, s.color);
        if (clip) canvas.restore();
    }

    /** Progress and meter: the filled fraction of the padding box in the accent colour (nothing when indeterminate). */
    private static void paintBar(Canvas canvas, Box box, ComputedStyle s, Element el) {
        String value = el.getAttribute("value");
        if (value == null && el.tagName().equals("progress")) return;
        double min = el.tagName().equals("meter") ? Forms.number(el.getAttribute("min"), 0) : 0;
        double max = Forms.number(el.getAttribute("max"), 1);
        double v = Forms.number(value, min);
        float fraction = max > min ? (float) Math.max(0, Math.min(1, (v - min) / (max - min))) : 0;
        canvas.fillRect(box.borderLeft, box.borderTop, box.paddingBoxWidth() * fraction, box.paddingBoxHeight(),
                s.accentColor);
    }

    /** Submit, reset and button inputs: their value (or default label), centred. */
    private static void paintButton(Canvas canvas, Box box, ComputedStyle s, Element el) {
        Label label = el.controlState instanceof Label l ? l : new Label();
        el.controlState = label;
        drawCentred(canvas, box, s, label.set(buttonLabel(el), FontSpec.of(s), s, measure(el)));
    }

    private static String buttonLabel(Element input) {
        String value = input.getAttribute("value");
        if (value != null) return value;
        return switch (input.inputType()) {
            case "submit" -> "Submit";
            case "reset" -> "Reset";
            default -> "";
        };
    }

    /** A label centred in the content box, clipped to the padding box only when it does not fit. */
    private static void drawCentred(Canvas canvas, Box box, ComputedStyle s, Label label) {
        float glyph = measure(box.element).glyphHeight(FontSpec.of(s));
        boolean clip = clipIf(canvas, box, label.width() > box.contentWidth() || glyph > box.contentHeight());
        label.draw(canvas, box.contentX() + (box.contentWidth() - label.width()) / 2, textTop(box, glyph), s, s.color);
        if (clip) canvas.restore();
    }

    /** Top of a single-line control's text: its glyph box centred in the content box. */
    private static float textTop(Box box, float glyphHeight) {
        return glyphTop(box.contentY(), box.contentHeight(), glyphHeight, 0);
    }

    /** Clips to the padding box when content overflows it (a clip costs the backend a draw call). */
    private static boolean clipIf(Canvas canvas, Box box, boolean overflows) {
        if (!overflows) return false;
        canvas.save();
        canvas.clipRect(box.borderLeft, box.borderTop, box.paddingBoxWidth(), box.paddingBoxHeight());
        return true;
    }

    static TextMeasure measure(Element el) {
        return el.ownerDocument().layoutEngine().textMeasure();
    }

    /** A select's paint state: the selected option's label, looked up again only when the DOM changed. */
    private static final class SelectState {
        final Label label = new Label(), arrow = new Label();
        private int domVersion = -1;
        private String option;

        /** The selected option's label, or null when nothing is selected. */
        String option(Element select) {
            int version = select.ownerDocument().domVersion();
            if (version != domVersion) {
                domVersion = version;
                Element selected = select.selectedOption();
                option = selected == null ? null : selected.label();
            }
            return option;
        }
    }
}
