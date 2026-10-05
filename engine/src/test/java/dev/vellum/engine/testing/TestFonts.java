package dev.vellum.engine.testing;

import dev.vellum.engine.host.FontMetrics;
import dev.vellum.engine.host.FontSpec;

/**
 * Deterministic font metrics with the advance widths of Minecraft's default ASCII font (glyph width + 1px spacing,
 * bold adds 1px per glyph), scaled by font size / 8. Non-ASCII characters advance 6px. Good enough for layout tests
 * to look like the game; the previewer reads the real glyphs instead.
 */
public final class TestFonts implements FontMetrics {
    private static final int[] ASCII = new int[128];

    static {
        java.util.Arrays.fill(ASCII, 6);
        set(" ", 4);
        set("!',.:;i|", 2);
        set("`l", 3);
        set("\"()*I[]t{}", 4);
        set("<>fk", 5);
        set("@~", 7);
    }

    private static void set(String chars, int w) {
        for (char c : chars.toCharArray()) ASCII[c] = w;
    }

    public static int advance(int codePoint) {
        return codePoint < 128 ? ASCII[codePoint] : 6;
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
