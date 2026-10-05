package dev.vellum.engine.paint;

import dev.vellum.engine.host.FontSpec;
import dev.vellum.engine.layout.SpacedText;
import dev.vellum.engine.style.Colors;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Shadow;

/**
 * Draws one line of styled text: its CSS text-shadows first (the first listed on top), then the text in its colour,
 * with the style's decorations; {@code text-shadow: minecraft} has the host draw its native shadow with the text.
 * Letter- or word-spaced text is drawn in its {@link SpacedText} parts. Positions snap to device pixels. The painter's
 * text runs and form controls both draw text through here.
 */
public final class TextPainter {
    private TextPainter() {}

    /**
     * Draws {@code text} with its glyph box's top-left at (x, y) in {@code s}'s decorations and shadows, in
     * {@code color} (usually {@code s.color}); {@code spaced} is null unless spacing applies.
     */
    public static void draw(Canvas canvas, String text, SpacedText spaced, float x, float y, FontSpec font,
                            ComputedStyle s, int color) {
        draw(canvas, text, spaced, x, y, font, s, color, Geometry.devicePixel(canvas));
    }

    /** Rounds a position to device pixels as {@link #draw} does, so carets and highlights line up with the text. */
    public static float snap(Canvas canvas, float v) {
        return Geometry.snap(v, Geometry.devicePixel(canvas));
    }

    /** {@link #draw} snapping to {@code devicePixel}, which the painter tracks per transform. */
    static void draw(Canvas canvas, String text, SpacedText spaced, float x, float y, FontSpec font,
                     ComputedStyle s, int color, float devicePixel) {
        int decorations = (s.underline ? Canvas.UNDERLINE : 0) | (s.lineThrough ? Canvas.STRIKETHROUGH : 0);
        boolean nativeShadow = false;
        for (int i = s.textShadow.size() - 1; i >= 0; i--) {
            Shadow shadow = s.textShadow.get(i);
            if (shadow.isNative()) {
                nativeShadow = true;
            } else if (!Colors.isTransparent(shadow.color())) {
                line(canvas, text, spaced, x + shadow.offsetX(), y + shadow.offsetY(), font, shadow.color(), decorations,
                        false, devicePixel);
            }
        }
        if (!Colors.isTransparent(color)) line(canvas, text, spaced, x, y, font, color, decorations, nativeShadow, devicePixel);
    }

    private static void line(Canvas canvas, String text, SpacedText spaced, float x, float y, FontSpec font, int argb,
                             int decorations, boolean shadow, float dp) {
        float ty = Geometry.snap(y, dp);
        if (spaced == null) {
            canvas.drawText(text, Geometry.snap(x, dp), ty, font, argb, decorations, shadow);
            return;
        }
        String[] parts = spaced.parts();
        float[] xs = spaced.x();
        for (int i = 0; i < parts.length; i++) {
            canvas.drawText(parts[i], Geometry.snap(x + xs[i], dp), ty, font, argb, decorations, shadow);
        }
    }
}
