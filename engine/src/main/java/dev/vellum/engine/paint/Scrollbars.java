package dev.vellum.engine.paint;

import dev.vellum.engine.layout.Box;

/**
 * Geometry of the overlay scrollbars of a scroll container, shared by the painter (drawing) and the input handler
 * (hover, drag, track clicks). Rectangles are {x, y, width, height} in the box's border-box coordinates, or null when
 * that axis does not scroll. STUB: implemented by the paint workstream.
 */
public final class Scrollbars {
    private Scrollbars() {}

    /** The track along the right (vertical) or bottom (horizontal) edge of the padding box. */
    public static float[] track(Box box, boolean vertical, boolean hovered) { return null; }

    /** The thumb within the track for the current scroll offset. */
    public static float[] thumb(Box box, boolean vertical, boolean hovered) { return null; }

    /** Scroll offset change per px of thumb movement along the axis. */
    public static float scrollPerThumbPixel(Box box, boolean vertical) { return 0; }
}
