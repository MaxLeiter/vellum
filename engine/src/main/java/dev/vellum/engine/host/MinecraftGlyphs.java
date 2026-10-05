package dev.vellum.engine.host;

import java.util.Arrays;

/**
 * Advance widths of Minecraft's default font (glyph width plus 1px spacing, at the native 8px size), for hosts and
 * tests that measure text without the game's glyphs.
 */
public final class MinecraftGlyphs {
    /** Advance of most glyphs, and of every code point this table does not cover. */
    public static final int DEFAULT_ADVANCE = 6;

    private static final int[] ASCII = new int[128];

    static {
        Arrays.fill(ASCII, DEFAULT_ADVANCE);
        String[] groups = {" ", "!',.:;i|", "`l", "\"()*I[]t{}", "<>fk", "@~"};
        int[] advances = {4, 2, 3, 4, 5, 7};
        for (int i = 0; i < groups.length; i++) {
            for (char c : groups[i].toCharArray()) ASCII[c] = advances[i];
        }
    }

    private MinecraftGlyphs() {}

    /** Whether {@link #asciiAdvance} knows the code point's real advance. */
    public static boolean isAscii(int codePoint) {
        return codePoint >= 0 && codePoint < ASCII.length;
    }

    /** The advance of an ASCII code point in px at size 8 (not bold); {@link #DEFAULT_ADVANCE} for others. */
    public static int asciiAdvance(int codePoint) {
        return isAscii(codePoint) ? ASCII[codePoint] : DEFAULT_ADVANCE;
    }
}
