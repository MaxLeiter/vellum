package dev.vellum.engine.testing;

import dev.vellum.engine.host.FontMetrics;
import dev.vellum.engine.host.FontSpec;
import dev.vellum.engine.host.MinecraftGlyphs;

/**
 * Deterministic font metrics with the advance widths of Minecraft's default ASCII font ({@link MinecraftGlyphs};
 * bold adds 1px per glyph), scaled by font size / 8. Non-ASCII characters advance 6px. Good enough for layout tests
 * to look like the game; the previewer reads the real glyphs instead.
 */
public final class TestFonts implements FontMetrics {
    public static int advance(int codePoint) {
        return MinecraftGlyphs.asciiAdvance(codePoint);
    }

    @Override
    public float width(String text, FontSpec font) {
        float w = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            w += advance(cp) + (font.bold() ? 1 : 0);
        }
        return w * font.scale();
    }

    @Override
    public float charWidth(int codePoint, FontSpec font) {
        return (advance(codePoint) + (font.bold() ? 1 : 0)) * font.scale();
    }
}
