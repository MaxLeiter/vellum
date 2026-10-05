package dev.vellum.engine.host;

import java.util.Arrays;

/**
 * A {@link PixelSurface} in a Java array. The default for hosts that cannot draw canvases (scripts still draw and
 * read pixels), and the base of hosts that paint from Java memory, which wrap {@link #pixels()} in their own image.
 */
public class ArraySurface implements PixelSurface {
    private final int width, height;
    private final int[] pixels;

    public ArraySurface(int width, int height) {
        this.width = width;
        this.height = height;
        this.pixels = new int[width * height];
    }

    @Override
    public int width() { return width; }

    @Override
    public int height() { return height; }

    /** Not drawable. */
    @Override
    public String url() { return null; }

    /** The pixels, ARGB, {@link #width} per row. Writes show at once. */
    public int[] pixels() { return pixels; }

    @Override
    public void fillRect(int x, int y, int w, int h, int argb) {
        for (int row = y; row < y + h; row++) Arrays.fill(pixels, row * width + x, row * width + x + w, argb);
    }

    @Override
    public void getPixels(int x, int y, int w, int h, int[] argb) {
        for (int row = 0; row < h; row++) System.arraycopy(pixels, (y + row) * width + x, argb, row * w, w);
    }

    @Override
    public void setPixels(int x, int y, int w, int h, int[] argb) {
        for (int row = 0; row < h; row++) System.arraycopy(argb, row * w, pixels, (y + row) * width + x, w);
    }
}
