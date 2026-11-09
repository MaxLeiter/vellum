package dev.vellum.engine.paint;

/**
 * The device pixel grid, and the one way the painter rounds to it. Layout positions are fractional; what the painter
 * draws snaps to device pixels so edges stay crisp at any GUI scale. Three rules keep that true (and
 * {@code PixelGridTest} checks them over every kind of box, at ties):
 *
 * <ol>
 *   <li><b>Snap edges, then measure.</b> A rectangle is its four snapped edges and a border or padding band is the
 *       distance between its two snapped edges ({@link #rect}, {@link #band}). Snapping a width separately lets it
 *       disagree with the edges it is meant to meet, by a device pixel.</li>
 *   <li><b>Round every edge the same way.</b> {@link #snap} breaks ties toward +∞; snapping a negated or mirrored
 *       coordinate breaks them the other way, so an edge exactly halfway between device pixels (a box centred in a
 *       window of odd size) lands on different pixels depending on which side measured it (#10).</li>
 *   <li><b>Snap in coordinates whose origin is on the grid.</b> Snapping is relative to the current origin; the
 *       painter puts each transformed context's origin, and each form control's, on a device pixel before anything
 *       inside it snaps (#5, #6).</li>
 * </ol>
 */
final class PixelGrid {
    private PixelGrid() {}

    /** The canvas's device pixel in GUI px, or 1 when the transform makes it meaningless. */
    static float devicePixel(Canvas canvas) {
        float dp = canvas.devicePixel();
        return dp > 0 && Float.isFinite(dp) ? dp : 1;
    }

    /** The grid line nearest {@code v}; ties go toward +∞, for every edge alike. */
    static float snap(float v, float dp) {
        return Math.round(v / dp) * dp;
    }

    /** Writes {x, y, width, height} of the rectangle with its four edges snapped, and returns {@code out}. */
    static float[] rect(float x, float y, float width, float height, float dp, float[] out) {
        return edges(x, y, x + width, y + height, dp, out);
    }

    /**
     * {@link #rect} from the rectangle's edges. Work out each edge once and derive everything that meets it from that
     * value: the same edge computed two ways differs by rounding error, which can tip it the other way at a tie.
     */
    static float[] edges(float left, float top, float right, float bottom, float dp, float[] out) {
        float x0 = snap(left, dp), y0 = snap(top, dp);
        out[0] = x0;
        out[1] = y0;
        out[2] = snap(right, dp) - x0;
        out[3] = snap(bottom, dp) - y0;
        return out;
    }

    /**
     * The width of a band (a border, padding or outline side) running from its outer edge, already snapped, to
     * {@code inner}, its inner edge before snapping; 0 when {@code width} (its width before snapping) is not
     * positive. A band that has any width keeps at least one device pixel, so thin borders do not vanish.
     */
    static float band(float snappedOuter, float inner, float width, float dp) {
        if (!(width > 0)) return 0;
        return Math.max(dp, Math.abs(snap(inner, dp) - snappedOuter));
    }
}
