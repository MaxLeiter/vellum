package dev.vellum.engine.paint;

import dev.vellum.engine.layout.Box;
import dev.vellum.engine.style.ComputedStyle;

/**
 * Where boxes are on screen: the one mapping between a box's border-box coordinates and viewport coordinates, as
 * painted. Painting, hit testing, input, scripts and hosts all use it, so they agree.
 *
 * <p>A box sits at its {@code x}/{@code y} in its parent, shifted by the scroll offset of the box whose content it is
 * in ({@link Box#contentParent()}: absolutely and fixed positioned boxes skip the scrollers below their containing
 * block), and drawn through its CSS transform and those of its ancestors ({@link #transform}).
 */
public final class Coordinates {
    private Coordinates() {}

    /**
     * Sets {@code out} to a box's CSS transform, {@code translate(origin) · transform · translate(-origin)} with
     * percentages against its border box, mapping its border-box space into the space it is placed in (origin at
     * its border-box origin). Returns false, leaving {@code out} alone, when it has none: no transform, or an
     * inline box, which CSS transforms do not apply to. {@code s} is the style it is painted with.
     */
    static boolean transform(Box box, ComputedStyle s, Affine out) {
        if (box.kind == Box.Kind.INLINE || !s.hasTransform()) return false;
        float ox = s.transformOriginX.resolve(box.width), oy = s.transformOriginY.resolve(box.height);
        out.identity().translate(ox, oy).concat(s.transform, box.width, box.height).translate(-ox, -oy);
        return true;
    }

    /** Sets {@code out} to the matrix from {@code box}'s border-box coordinates to viewport coordinates. */
    public static Affine toViewport(Box box, Affine out) {
        Affine own = new Affine();
        out.identity();
        for (Box b = box; b != null; b = hop(b, out)) {
            if (transform(b, StackingOrder.styleOf(b), own)) out.preMultiply(own);
        }
        return out;
    }

    /**
     * Sets {@code out} to the matrix from viewport coordinates to {@code box}'s border-box coordinates. Returns false
     * when a transform collapses the box (such as {@code scale(0)}), so no point maps into it.
     */
    public static boolean fromViewport(Box box, Affine out) {
        return toViewport(box, out).invert();
    }

    /** The bounding rectangle {x, y, width, height} of {@code box}'s border box in viewport coordinates. */
    public static float[] boundingRect(Box box) {
        return toViewport(box, new Affine()).mapBounds(0, 0, box.width, box.height, new float[4]);
    }

    /**
     * Sets {@code out} to the translation from {@code box}'s placement (its border-box origin, before its own
     * transform) to the coordinate space of {@code space}: a transformed ancestor's border box, or the viewport for
     * null. No box in between may be transformed, which holds for the boxes a stacking context paints: a transformed
     * box is a stacking context, and the containing block of the positioned boxes inside it.
     */
    static Affine offset(Box box, Box space, Affine out) {
        out.identity();
        for (Box b = hop(box, out); b != space && b != null; ) b = hop(b, out);
        return out;
    }

    /**
     * Moves {@code out} from {@code b}'s placement into the content of the box that holds it
     * ({@link Box#contentParent()}): by its offset up the tree, less the holder's scroll offset. Returns the holder.
     */
    private static Box hop(Box b, Affine out) {
        Box holder = b.contentParent();
        for (Box p = b; p != holder && p != null; p = p.parent) out.preTranslate(p.x, p.y);
        if (holder != null) out.preTranslate(-holder.scrollLeft(), -holder.scrollTop());
        return holder;
    }
}
