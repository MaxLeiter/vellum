package dev.vellum.preview.render;

import dev.vellum.engine.host.FontSpec;
import dev.vellum.engine.paint.Canvas;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Font metrics and glyph layout. Tests that read the real glyphs are skipped when no Minecraft jar is found. */
class MinecraftFontTest {
    private static final FontSpec PLAIN = spec(8, false, false);

    private static MinecraftAssets minecraft;
    private static final MinecraftFont FALLBACK = new MinecraftFont(MinecraftAssets.open(List.of(), Optional.empty()));

    @BeforeAll
    static void open() {
        minecraft = MinecraftAssets.open(List.of(), MinecraftAssets.findClientJar());
    }

    @AfterAll
    static void close() throws IOException {
        minecraft.close();
    }

    private static MinecraftFont minecraftFont() {
        assumeTrue(minecraft.hasMinecraft(), "no Minecraft " + MinecraftAssets.MINECRAFT_VERSION + " jar found");
        return new MinecraftFont(minecraft);
    }

    private static FontSpec spec(float size, boolean bold, boolean italic) {
        return new FontSpec(List.of("minecraft:default"), size, bold, italic);
    }

    @Test
    void asciiAdvancesMatchTheFallbackTable() {
        MinecraftFont font = minecraftFont();
        for (int c = 32; c < 127; c++) {
            assertEquals(FALLBACK.charWidth(c, PLAIN), font.charWidth(c, PLAIN), "advance of '" + (char) c + "'");
        }
    }

    @Test
    void advancesAreTheOpaqueWidthPlusOne() {
        MinecraftFont font = minecraftFont();
        assertEquals(2, font.width("i", PLAIN));
        assertEquals(4, font.width(" ", PLAIN));
        assertEquals(6 + 2 + 6, font.width("AiB", PLAIN));
    }

    @Test
    void boldAddsAPixelPerGlyphAndSizeScales() {
        MinecraftFont font = minecraftFont();
        assertEquals(font.width("Hi!", PLAIN) + 3, font.width("Hi!", spec(8, true, false)));
        assertEquals(font.width("Hi!", PLAIN) * 2, font.width("Hi!", spec(16, false, false)));
        assertEquals(font.width("Hi", PLAIN) * 1.5f, font.width("Hi", spec(12, false, false)));
    }

    @Test
    void providersAreSearchedInOrder() {
        MinecraftFont font = minecraftFont();
        // accented.png has 12px cells with ascent 10, so its glyphs start 3px above the glyph box.
        assertEquals(-3, font.glyph('É', PLAIN).top());
        assertEquals(0, font.glyph('E', PLAIN).top());
        assertEquals(8, font.glyph('E', PLAIN).image().getHeight());
    }

    @Test
    void codePointsWithoutAGlyphFallBackToJava2D() {
        MinecraftFont.Glyph glyph = minecraftFont().glyph('日', PLAIN);
        assertNotNull(glyph.image());
        assertTrue(glyph.advance() > 4);
    }

    @Test
    void withoutAJarAsciiUsesMinecraftsAdvances() {
        assertEquals(2 + 3 + 2 + 4 + 2 + 6, FALLBACK.width("il! .A", PLAIN));
    }

    @Test
    void drawsShadowsFirstThenGlyphsThenBars() {
        List<String> calls = new ArrayList<>();
        FALLBACK.draw("ab", spec(16, false, false), 0xFFFFFFFF, Canvas.UNDERLINE, true, new MinecraftFont.GlyphSink() {
            @Override
            public void glyph(BufferedImage image, AffineTransform transform, int argb) {
                calls.add("glyph " + Integer.toHexString(argb) + " at " + transform.getTranslateX());
            }

            @Override
            public void rect(float x0, float y0, float x1, float y1, int argb) {
                calls.add("rect " + Integer.toHexString(argb) + " " + x0 + "," + y0 + "-" + x1 + "," + y1);
            }
        });
        // At 16px everything doubles: shadow offset 2, advance 12 per glyph, underline rows 16-18.
        assertEquals(List.of(
                "glyph ff3f3f3f at 2.0", "glyph ff3f3f3f at 14.0", "rect ff3f3f3f 0.0,18.0-26.0,20.0",
                "glyph ffffffff at 0.0", "glyph ffffffff at 12.0", "rect ffffffff -2.0,16.0-24.0,18.0"), calls);
    }
}
