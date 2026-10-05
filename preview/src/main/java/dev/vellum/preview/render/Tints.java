package dev.vellum.preview.render;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tinted copies of images: texel RGB × colour RGB, as Minecraft's GUI shaders multiply by the vertex colour (alpha
 * is applied separately through the composite). Java2D cannot multiply while drawing, so copies are made once per
 * image and colour and kept in a small LRU cache; glyph and sprite images are small, and UIs use few colours.
 */
final class Tints {
    private static final int CAPACITY = 4096;

    private record Key(BufferedImage image, int rgb) {}

    private static final Map<Key, BufferedImage> CACHE = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, BufferedImage> eldest) {
            return size() > CAPACITY;
        }
    };

    private Tints() {}

    /** {@code image} multiplied by the RGB of {@code argb}; the image itself for white. */
    static synchronized BufferedImage tint(BufferedImage image, int argb) {
        int rgb = argb & 0xFFFFFF;
        if (rgb == 0xFFFFFF) return image;
        return CACHE.computeIfAbsent(new Key(image, rgb), key -> multiply(image, rgb));
    }

    private static BufferedImage multiply(BufferedImage image, int rgb) {
        int w = image.getWidth(), h = image.getHeight();
        int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            pixels[i] = p & 0xFF000000 | ((p >> 16 & 0xFF) * r / 255) << 16 | ((p >> 8 & 0xFF) * g / 255) << 8 | (p & 0xFF) * b / 255;
        }
        BufferedImage tinted = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        tinted.setRGB(0, 0, w, h, pixels, 0, w);
        return tinted;
    }
}
