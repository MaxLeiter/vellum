package dev.vellum.preview;

import dev.vellum.engine.paint.Canvas;

/** What the previewer renders each frame: a page, or the canvas test. */
interface Scene {
    /** Advances to {@code nowMs} with a viewport of {@code width}×{@code height} GUI px at GUI scale {@code scale}. */
    void frame(double nowMs, float width, float height, float scale);

    void paint(Canvas canvas);

    /** Whether a frame at {@code nowMs} would look different from the last one painted, input aside. */
    default boolean needsFrame(double nowMs) { return false; }

    /** The failure that stopped the scene, shown in its place; null while it works. */
    default Throwable error() { return null; }
}
