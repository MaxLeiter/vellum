package dev.vellum.preview.render;

import dev.vellum.preview.Snapshots;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ImageCanvas against hand-computed pixels, using a synthetic resource pack (no Minecraft jar needed). */
class ImageCanvasTest {
    private static final int R = 0xFFFF0000, G = 0xFF00FF00, B = 0xFF0000FF, W = 0xFFFFFFFF, CLEAR = 0;
    /** A 4×4 nine-slice sprite with a 1px border: corners, edges and centre all different. */
    private static final int C1 = 0xFFFF8080, T = 0xFFFFFF80, C2 = 0xFF80FF80, L = 0xFF80FFFF, M = 0xFF404040,
            RT = 0xFF8080FF, C3 = 0xFFFF80FF, BO = 0xFFC0C0C0, C4 = 0xFFFFC080;

    @TempDir
    Path dir;
    private MinecraftAssets assets;

    @BeforeEach
    void pack() throws IOException {
        assets = new TestPack(dir)
                .png("test:textures/quad.png", 2, 2, R, G, B, W)
                .png("test:textures/gui/sprites/frame.png", 4, 4,
                        C1, T, T, C2,
                        L, M, M, RT,
                        L, M, M, RT,
                        C3, BO, BO, C4)
                .text("test:textures/gui/sprites/frame.png.mcmeta",
                        "{\"gui\": {\"scaling\": {\"type\": \"nine_slice\", \"width\": 4, \"height\": 4, \"border\": 1}}}")
                .assets();
    }

    private ImageCanvas canvas(int width, int height, float scale) {
        return new ImageCanvas(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), scale, assets, new MinecraftFont(assets));
    }

    private static int[] pixels(ImageCanvas canvas) {
        BufferedImage image = canvas.image();
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    @Test
    void rectsCoverDevicePixelsByTheirCentres() {
        ImageCanvas c = canvas(4, 1, 1);
        c.fillRect(0.25f, 0, 1, 1, R); // [0.25, 1.25) holds the centre of pixel 0 only
        c.fillRect(1.6f, 0, 1, 1, B);  // [1.6, 2.6) holds the centre of pixel 2 only
        assertArrayEquals(new int[] {R, CLEAR, B, CLEAR}, pixels(c));
    }

    @Test
    void guiPixelsAreScaleDevicePixels() {
        ImageCanvas c = canvas(8, 8, 2);
        c.fillRect(1, 1, 2, 1.5f, R);
        BufferedImage image = c.image();
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                boolean inside = x >= 2 && x < 6 && y >= 2 && y < 5;
                assertEquals(inside ? R : CLEAR, image.getRGB(x, y), "pixel " + x + "," + y);
            }
        }
        assertEquals(0.5f, c.devicePixel());
        c.transform(2, 0, 0, 2, 0, 0);
        assertEquals(0.25f, c.devicePixel());
    }

    @Test
    void clipsToTheDevicePixelBoundingBoxOfTheTransformedRect() {
        ImageCanvas c = canvas(10, 10, 2);
        c.translate(4, 0);
        c.transform(0, 1, -1, 0, 0, 0); // rotate 90°: (2, 1) → (-1, 2)
        c.clipRect(0, 0, 2, 1);
        c.fillRect(-50, -50, 100, 100, R);
        BufferedImage image = c.image();
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 10; x++) {
                assertEquals(x >= 6 && x < 8 && y < 4 ? R : CLEAR, image.getRGB(x, y), "pixel " + x + "," + y);
            }
        }
    }

    @Test
    void alphaMultipliesThroughTheStateStack() {
        ImageCanvas c = canvas(2, 1, 1);
        c.multiplyAlpha(0.5f);
        c.save();
        c.multiplyAlpha(0.5f);
        c.fillRect(0, 0, 1, 1, W);
        c.restore();
        c.fillRect(1, 0, 1, 1, W);
        assertArrayEquals(new int[] {0x40FFFFFF, 0x80FFFFFF}, pixels(c));
        assertThrows(IllegalStateException.class, c::restore);
    }

    @Test
    void gradientQuadsInterpolateVertexColoursAtPixelCentres() {
        ImageCanvas c = canvas(4, 1, 1);
        c.fillQuads(new float[] {0, 0, 4, 0, 4, 1, 0, 1}, new int[] {R, B, B, R}, 1);
        assertArrayEquals(new int[] {0xFFDF0020, 0xFF9F0060, 0xFF60009F, 0xFF2000DF}, pixels(c));
    }

    @Test
    void sharedQuadEdgesAreFilledOnce() {
        ImageCanvas c = canvas(4, 4, 1);
        c.fillRect(0, 0, 4, 4, 0xFF000000);
        // Slightly different vertex colours force the rasterizer; double-blended pixels would come out ~190 red.
        int[] colors = {0x80FF0000, 0x80FE0000, 0x80FF0000, 0x80FE0000, 0x80FF0000, 0x80FE0000, 0x80FF0000, 0x80FE0000};
        c.fillQuads(new float[] {0, 0, 2, 0, 2, 4, 0, 4, 2, 0, 4, 0, 4, 4, 2, 4}, colors, 2);
        for (int pixel : pixels(c)) {
            int red = pixel >> 16 & 0xFF;
            assertTrue(red >= 126 && red <= 129 && pixel >>> 24 == 255, () -> Integer.toHexString(pixel));
        }
    }

    @Test
    void imagesRepeatFlipAndTint() {
        ImageCanvas c = canvas(4, 2, 1);
        c.drawImage("test:textures/quad.png", 0, 0, 4, 2, 0, 0, 2, 1, W, false);
        assertArrayEquals(new int[] {R, G, R, G, B, W, B, W}, pixels(c));

        c = canvas(2, 2, 1);
        c.drawImage("test:textures/quad.png", 0, 0, 2, 2, 1, 0, 0, 1, W, false);
        assertArrayEquals(new int[] {G, R, W, B}, pixels(c));

        c = canvas(2, 2, 1);
        c.drawImage("test:textures/quad.png", 0, 0, 2, 2, 0, 0, 1, 1, 0xFF808080, false);
        assertArrayEquals(new int[] {0xFF800000, 0xFF008000, 0xFF000080, 0xFF808080}, pixels(c));
    }

    @Test
    void missingTexturesDrawMinecraftsChecks() {
        ImageCanvas c = canvas(2, 2, 1);
        c.drawImage("test:textures/nothing.png", 0, 0, 2, 2, 0, 0, 1, 1, W, false);
        assertArrayEquals(new int[] {0xFF000000, 0xFFF800F8, 0xFFF800F8, 0xFF000000}, pixels(c));
    }

    @Test
    void spritesAreNineSlicedLikeVanilla() {
        ImageCanvas c = canvas(6, 3, 1);
        c.drawSprite("test:frame", 0, 0, 6, 3, W);
        assertArrayEquals(new int[] {
                C1, T, T, T, T, C2,
                L, M, M, M, M, RT,
                C3, BO, BO, BO, BO, C4}, pixels(c));
    }

    @Test
    void primitivesMatchGolden() {
        ImageCanvas c = canvas(320, 200, 2);
        c.fillRect(0, 0, 160, 100, 0xFF202830);
        c.fillRect(4, 4, 20, 12, R);
        c.fillRect(26.5f, 4.5f, 20, 12, 0x8000FF00);
        c.fillRect(4, 18, 60, c.devicePixel(), W);
        c.fillQuads(new float[] {4, 22, 64, 22, 64, 38, 4, 38, 68, 22, 100, 22, 100, 38, 68, 38},
                new int[] {R, B, B, R, R, G, B, 0xFFFFFF00}, 2);
        c.fillQuads(new float[] {104, 22, 130, 38, 104, 38, 104, 38}, new int[] {W, 0xFF000000, 0xFFFF8000, 0xFFFF8000}, 1);
        c.drawImage("test:textures/quad.png", 4, 42, 32, 16, 0, 0, 4, 2, W, false);
        c.drawImage("test:textures/quad.png", 40, 42, 16, 16, 0, 0, 1, 1, W, true);
        c.drawSprite("test:frame", 60, 42, 40, 16, W);
        c.drawSprite("test:frame", 104, 42, 40, 16, 0x8080FFFF);
        c.save();
        c.clipRect(4, 62, 30, 20);
        c.fillQuads(new float[] {0, 55, 50, 55, 50, 90, 0, 90}, new int[] {G, B, R, W}, 1);
        c.restore();
        c.save();
        c.translate(70, 75);
        c.transform(0.866f, 0.5f, -0.5f, 0.866f, 0, 0);
        c.drawSprite("test:frame", -20, -8, 40, 16, W);
        c.restore();
        c.save();
        c.multiplyAlpha(0.5f);
        c.fillRect(104, 62, 30, 20, R);
        c.fillRect(118, 72, 30, 20, B);
        c.restore();
        Snapshots.assertMatches("primitives", c.image());
    }
}
