package dev.vellum.engine.layout;

import dev.vellum.engine.host.FontMetrics;
import dev.vellum.engine.host.FontSpec;
import dev.vellum.engine.style.ComputedStyle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How wide text is, for everyone who places glyphs: layout, painting, hit testing and form controls, so they agree.
 * A character advances by the host's glyph advance ({@link FontMetrics}), plus {@code letter-spacing}, plus
 * {@code word-spacing} when it is a word separator (a space or no-break space).
 *
 * <p>One per document. The host's widths of whole strings are cached (bounded), since every relayout measures every
 * word again and the host's measurement is not free (Minecraft lays out the string each time). The cache is keyed by
 * font value and text, so it holds across restyles and relayouts.
 */
public final class TextMeasure {
    /** Cached strings before the cache starts over; a page's words fit many times over. */
    private static final int MAX_CACHED = 8192;

    private final FontMetrics fonts;
    private final Map<FontSpec, Map<String, Float>> widths = new HashMap<>();
    private int cached;

    public TextMeasure(FontMetrics fonts) {
        this.fonts = fonts;
    }

    public FontMetrics fonts() {
        return fonts;
    }

    public float glyphHeight(FontSpec font) {
        return fonts.glyphHeight(font);
    }

    public float ascent(FontSpec font) {
        return fonts.ascent(font);
    }

    /** The host's width of {@code text}, without spacing. Cached. */
    public float width(String text, FontSpec font) {
        Map<String, Float> forFont = widths.computeIfAbsent(font, f -> new HashMap<>());
        Float w = forFont.get(text);
        if (w == null) {
            if (cached >= MAX_CACHED) {
                widths.clear();
                cached = 0;
                forFont = widths.computeIfAbsent(font, f -> new HashMap<>());
            }
            w = fonts.width(text, font);
            forFont.put(text, w);
            cached++;
        }
        return w;
    }

    /** The width of {@code text} as placed: the host's width plus {@code s}'s letter- and word-spacing. */
    public float width(String text, FontSpec font, ComputedStyle s) {
        float w = width(text, font);
        if (s.letterSpacing == 0 && s.wordSpacing == 0) return w;
        int glyphs = 0, separators = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            glyphs++;
            if (isWordSeparator(cp)) separators++;
            i += Character.charCount(cp);
        }
        return w + s.letterSpacing * glyphs + s.wordSpacing * separators;
    }

    /** How far one code point advances the pen in {@code s}: its glyph's advance plus spacing. */
    public float advance(int codePoint, FontSpec font, ComputedStyle s) {
        float w = fonts.charWidth(codePoint, font) + s.letterSpacing;
        return isWordSeparator(codePoint) ? w + s.wordSpacing : w;
    }

    /** The caret position (a char index of {@code text}) nearest to {@code x}, measured from the text's start. */
    public int offsetAt(String text, FontSpec font, ComputedStyle s, float x) {
        float pos = 0;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            float advance = advance(cp, font, s);
            if (x < pos + advance / 2) break;
            pos += advance;
            i += Character.charCount(cp);
        }
        return i;
    }

    /**
     * The parts to draw {@code text} in so its spacing shows, or null when {@code s} has none (the host draws it
     * whole): each glyph with letter-spacing, else each word with the separators after it.
     */
    public SpacedText spaced(String text, FontSpec font, ComputedStyle s) {
        if (s.letterSpacing == 0 && s.wordSpacing == 0) return null;
        boolean perGlyph = s.letterSpacing != 0;
        List<String> parts = new ArrayList<>();
        float[] x = new float[text.length()];
        float pen = 0;
        for (int start = 0, i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (perGlyph || i == text.length() || isWordSeparator(cp) && !isWordSeparator(text.codePointAt(i))) {
                String part = text.substring(start, i);
                x[parts.size()] = pen;
                parts.add(part);
                pen += width(part, font, s);
                start = i;
            }
        }
        return new SpacedText(parts.toArray(new String[0]), Arrays.copyOf(x, parts.size()));
    }

    /** The characters word-spacing applies to. */
    public static boolean isWordSeparator(int codePoint) {
        return codePoint == ' ' || codePoint == 0xA0;
    }
}
