package dev.vellum.engine.layout;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.host.FontMetrics;
import dev.vellum.engine.host.FontSpec;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.testing.TestFonts;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextMeasureTest {
    /** Test fonts that count the host measurements of whole strings. */
    private static final class CountingFonts implements FontMetrics {
        final TestFonts fonts = new TestFonts();
        int widths;

        @Override
        public float width(String text, FontSpec font) {
            widths++;
            return fonts.width(text, font);
        }

        @Override
        public float charWidth(int codePoint, FontSpec font) {
            return fonts.charWidth(codePoint, font);
        }
    }

    @Test
    void relayoutsDoNotMeasureTheSameWordsAgain() {
        CountingFonts fonts = new CountingFonts();
        TestHost host = new TestHost() {
            @Override
            public FontMetrics fonts() { return fonts; }
        };
        StringBuilder html = new StringBuilder("<body>");
        for (int i = 0; i < 50; i++) html.append("<p>The quick brown fox jumps over the lazy dog ").append(i).append("</p>");
        Document doc = host.load(html.append("</body>").toString());
        int first = fonts.widths;
        assertTrue(first > 0);
        doc.layoutEngine().layout();
        doc.layoutEngine().layout();
        assertEquals(first, fonts.widths, "every word was measured once");
    }

    @Test
    void spacingAddsPerGlyphAndPerWordSeparator() {
        TextMeasure measure = new TextMeasure(new TestFonts());
        FontSpec font = FontSpec.of(ComputedStyle.INITIAL);
        ComputedStyle s = new ComputedStyle();
        assertNull(measure.spaced("a b", font, s));
        s.letterSpacing = 1;
        s.wordSpacing = 2;
        // a 6, space 4, b 6; plus 1 per glyph and 2 per space
        assertEquals(6 + 4 + 6 + 3 + 2, measure.width("a b", font, s), 1e-3);
        assertEquals(4 + 1 + 2, measure.advance(' ', font, s), 1e-3);
        SpacedText glyphs = measure.spaced("a b", font, s);
        assertEquals(List.of("a", " ", "b"), List.of(glyphs.parts()));
        assertArrayEquals(new float[] {0, 7, 14}, glyphs.x(), 1e-3f);
        s.letterSpacing = 0;
        SpacedText words = measure.spaced("ab  cd e", font, s);
        assertEquals(List.of("ab  ", "cd ", "e"), List.of(words.parts()));
        assertArrayEquals(new float[] {0, 24, 24 + 18}, words.x(), 1e-3f);
        assertEquals(2, measure.offsetAt("ab  cd", font, s, 14), "left half of the first space");
        assertEquals(3, measure.offsetAt("ab  cd", font, s, 16));
    }
}
