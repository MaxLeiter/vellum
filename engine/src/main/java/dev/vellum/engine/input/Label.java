package dev.vellum.engine.input;

import dev.vellum.engine.host.FontSpec;
import dev.vellum.engine.layout.SpacedText;
import dev.vellum.engine.layout.TextMeasure;
import dev.vellum.engine.paint.Canvas;
import dev.vellum.engine.paint.TextPainter;
import dev.vellum.engine.style.ComputedStyle;

/**
 * A line of text a control draws (a button's label, the selected option, a slider's caption, the select arrow):
 * measured, and cut for letter- and word-spacing, only when its text or font changes, so drawing it every frame
 * allocates nothing. Kept in the control's state.
 */
final class Label {
    private String text;
    private FontSpec font;
    private float letterSpacing, wordSpacing;
    private float width;
    private SpacedText spaced;

    /** Sets the text in {@code s}'s font and spacing, measuring it again only when something changed. */
    Label set(String text, FontSpec font, ComputedStyle s, TextMeasure measure) {
        if (!text.equals(this.text) || !font.equals(this.font) || s.letterSpacing != letterSpacing
                || s.wordSpacing != wordSpacing) {
            this.text = text;
            this.font = font;
            letterSpacing = s.letterSpacing;
            wordSpacing = s.wordSpacing;
            width = measure.width(text, font, s);
            spaced = measure.spaced(text, font, s);
        }
        return this;
    }

    float width() {
        return width;
    }

    /** Draws the text with its glyph box's top-left at (x, y), styled by {@code s} in {@code color}. */
    void draw(Canvas canvas, float x, float y, ComputedStyle s, int color) {
        TextPainter.draw(canvas, text, spaced, x, y, font, s, color);
    }
}
