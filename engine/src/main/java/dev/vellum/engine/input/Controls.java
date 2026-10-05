package dev.vellum.engine.input;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.FontMetrics;
import dev.vellum.engine.host.FontSpec;
import dev.vellum.engine.layout.Box;
import dev.vellum.engine.paint.Canvas;
import dev.vellum.engine.style.Colors;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Shadow;

/**
 * Painting of built-in form controls (text in inputs and textareas with caret and selection, checkbox ticks, range
 * thumbs, select labels and arrows, progress/meter fills, placeholders). The painter calls {@link #paint} after a
 * control's background and border, inside its transform/opacity. Backgrounds (the vanilla sprites from the UA
 * stylesheet) are the painter's; this draws only what CSS cannot.
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

    /** Paints the control's content into {@code box} (coordinates: the box's border-box origin is at 0,0). */
    public static void paint(Canvas canvas, Box box) {
        Element el = box.element;
        if (el == null || box.style == null) return;
        canvas.save();
        canvas.clipRect(box.borderLeft, box.borderTop, box.paddingBoxWidth(), box.paddingBoxHeight());
        switch (el.tagName()) {
            case "textarea" -> paintText(canvas, box, TextField.of(el));
            case "select" -> paintSelect(canvas, box, el);
            case "progress", "meter" -> paintBar(canvas, box, el);
            case "input" -> {
                if (el.isTextControl()) paintText(canvas, box, TextField.of(el));
                else switch (el.inputType()) {
                    case "checkbox", "radio" -> paintCheck(canvas, box, el);
                    case "range" -> paintRange(canvas, box, el);
                    case "submit", "reset", "button" ->
                            drawLine(canvas, box, buttonLabel(el), Float.NaN, nativeShadow(box.style));
                    default -> { }
                }
            }
            default -> { }
        }
        canvas.restore();
    }

    /** Value (or placeholder), selection highlight and caret of a text field, scrolled by the field's offsets. */
    private static void paintText(Canvas canvas, Box box, TextField field) {
        Element el = field.element;
        ComputedStyle s = box.style;
        TextEditor editor = field.editor;
        TextLayout value = field.layout();
        String placeholder = el.getAttribute("placeholder");
        if (editor.text().isEmpty() && placeholder != null) {
            int color = el.placeholderStyle != null ? el.placeholderStyle.color
                    : Colors.withAlphaFactor(s.color, PLACEHOLDER_ALPHA);
            drawLines(canvas, box, field, field.layout(placeholder, false), color);
        } else {
            if (el.isFocused() && editor.hasSelection()) paintSelection(canvas, field, value, s.accentColor);
            drawLines(canvas, box, field, value, s.color);
        }
        if (el.isFocused() && field.caretOn() && !editor.hasSelection()) {
            int caret = editor.caret();
            canvas.fillRect(field.textX() + value.x(caret), field.glyphY(value.lineOf(caret)), 1, field.glyphHeight(), s.color);
        }
    }

    private static void paintSelection(Canvas canvas, TextField field, TextLayout layout, int accent) {
        TextEditor editor = field.editor;
        int color = Colors.withAlphaFactor(accent, SELECTION_ALPHA);
        float x0 = field.textX(), newline = field.fonts().width(" ", field.font());
        for (int i = 0; i < layout.lineCount(); i++) {
            TextLayout.Line line = layout.line(i);
            int from = Math.max(editor.selectionStart(), line.start());
            int to = Math.min(editor.selectionEnd(), line.end());
            if (from > to) continue;
            // A selection running past a hard line end includes the newline: show it as a space's width.
            float extra = editor.selectionEnd() > line.end() && !line.softWrapped() ? newline : 0;
            float x = line.x(from), w = line.x(to) - x + extra;
            if (w > 0) canvas.fillRect(x0 + x, field.glyphY(i), w, field.glyphHeight(), color);
        }
    }

    private static void drawLines(Canvas canvas, Box box, TextField field, TextLayout layout, int color) {
        ComputedStyle s = box.style;
        float x = field.textX(), lineHeight = field.lineHeight();
        for (int i = 0; i < layout.lineCount(); i++) {
            float y = field.glyphY(i);
            if (y + lineHeight < 0 || y - lineHeight > box.height) continue; // scrolled out (the clip trims the rest)
            canvas.drawText(layout.line(i).display(), x, y, field.font(), color, decorations(s), nativeShadow(s));
        }
    }

    /** The UA stylesheet draws checkboxes and radios with sprites; without a background, draw a plain mark. */
    private static void paintCheck(Canvas canvas, Box box, Element el) {
        ComputedStyle s = box.style;
        if (!s.backgroundLayers.isEmpty() || !el.checked()) return;
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
    private static void paintRange(Canvas canvas, Box box, Element el) {
        RangeControl range = new RangeControl(el);
        String sprite = el.isHovered() || el.isActive() || el.isFocused() ? THUMB_HIGHLIGHTED : THUMB;
        canvas.drawSprite(sprite, RangeControl.thumbX(box, range.fraction()), box.contentY(),
                RangeControl.THUMB_WIDTH, box.contentHeight(), box.style.tint);
        String label = el.getAttribute("label");
        if (label != null) drawLine(canvas, box, label + ": " + range.text(), Float.NaN, true);
    }

    /** The selected option's label, and a down arrow at the right. */
    private static void paintSelect(Canvas canvas, Box box, Element el) {
        FontSpec font = FontSpec.of(box.style);
        float arrowWidth = fonts(box).width(ARROW, font), arrowX = box.contentX() + box.contentWidth() - arrowWidth;
        boolean shadow = nativeShadow(box.style);
        drawLine(canvas, box, ARROW, arrowX, shadow);
        Element option = el.selectedOption();
        if (option == null) return;
        canvas.save();
        canvas.clipRect(box.contentX(), box.borderTop, Math.max(0, arrowX - 2 - box.contentX()), box.paddingBoxHeight());
        drawLine(canvas, box, option.label(), box.contentX(), shadow);
        canvas.restore();
    }

    /** Progress and meter: the filled fraction of the padding box in the accent colour (nothing when indeterminate). */
    private static void paintBar(Canvas canvas, Box box, Element el) {
        String value = el.getAttribute("value");
        if (value == null && el.tagName().equals("progress")) return;
        double min = el.tagName().equals("meter") ? Forms.number(el.getAttribute("min"), 0) : 0;
        double max = Forms.number(el.getAttribute("max"), 1);
        double v = Forms.number(value, min);
        float fraction = max > min ? (float) Math.max(0, Math.min(1, (v - min) / (max - min))) : 0;
        canvas.fillRect(box.borderLeft, box.borderTop, box.paddingBoxWidth() * fraction, box.paddingBoxHeight(),
                box.style.accentColor);
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

    /**
     * One line of text in the style's colour, vertically centred in the content box, at {@code x} (border-box
     * coordinates) or centred horizontally when {@code x} is NaN.
     */
    private static void drawLine(Canvas canvas, Box box, String text, float x, boolean shadow) {
        ComputedStyle s = box.style;
        FontSpec font = FontSpec.of(s);
        FontMetrics fonts = fonts(box);
        float y = box.contentY() + (box.contentHeight() - fonts.glyphHeight(font)) / 2;
        if (Float.isNaN(x)) x = box.contentX() + (box.contentWidth() - fonts.width(text, font)) / 2;
        canvas.drawText(text, x, y, font, s.color, decorations(s), shadow);
    }

    private static FontMetrics fonts(Box box) {
        return box.element.ownerDocument().host().fonts();
    }

    private static int decorations(ComputedStyle s) {
        return (s.underline ? Canvas.UNDERLINE : 0) | (s.lineThrough ? Canvas.STRIKETHROUGH : 0);
    }

    /** True for {@code text-shadow: minecraft}; other text shadows are not drawn inside controls. */
    private static boolean nativeShadow(ComputedStyle s) {
        for (Shadow sh : s.textShadow) if (sh.isNative()) return true;
        return false;
    }
}
