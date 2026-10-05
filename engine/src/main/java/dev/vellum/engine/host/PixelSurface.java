package dev.vellum.engine.host;

/**
 * The pixels behind a {@code <canvas>}, provided by the host ({@link Host#createSurface}): in game a dynamic texture,
 * in the previewer an image. Pixels are ARGB, not premultiplied, row-major from the top-left.
 *
 * <p>The engine does the drawing (the 2D context's blending, clipping and image data) and calls only these
 * primitives, always with rectangles inside the surface. Writes replace pixels. Changes become visible to painting
 * at the next {@link #upload}, which the document calls once per frame before painting.
 */
public interface PixelSurface {
    int width();

    int height();

    /**
     * The image URL the host's {@link dev.vellum.engine.paint.Canvas#drawImage} draws this surface with, or null
     * when the host cannot draw it (an in-memory surface).
     */
    String url();

    /** Sets every pixel of the rectangle to {@code argb}. */
    void fillRect(int x, int y, int width, int height, int argb);

    /** Reads the rectangle into {@code argb}, row by row ({@code width} pixels per row). */
    void getPixels(int x, int y, int width, int height, int[] argb);

    /** Writes the rectangle from {@code argb}, row by row ({@code width} pixels per row). */
    void setPixels(int x, int y, int width, int height, int[] argb);

    /** Makes the changes since the last upload visible to painting (a texture upload of what changed). */
    default void upload() {}

    /** Releases the surface; it is not used afterwards. */
    default void dispose() {}
}
