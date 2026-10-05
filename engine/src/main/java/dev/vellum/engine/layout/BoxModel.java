package dev.vellum.engine.layout;

import dev.vellum.engine.style.BoxSizing;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Length;

/** Style-to-geometry helpers of the CSS box model shared by every formatting context. */
final class BoxModel {
    private BoxModel() {}

    /**
     * Resolves the border widths, padding and margins of {@code b} into its fields. Percentages of margins and
     * padding refer to the containing block's width in both axes (0 when it is unknown). Auto margins become 0;
     * the formatting contexts that give them meaning check the style.
     */
    static void resolveEdges(Box b, float cbWidth) {
        ComputedStyle s = b.style;
        b.marginTop = s.marginTop.resolve(cbWidth);
        b.marginRight = s.marginRight.resolve(cbWidth);
        b.marginBottom = s.marginBottom.resolve(cbWidth);
        b.marginLeft = s.marginLeft.resolve(cbWidth);
        resolvePaddingBorder(b, cbWidth);
    }

    /** Resolves only the border widths and padding (the parent owns the margins, e.g. resolved auto margins). */
    static void resolvePaddingBorder(Box b, float cbWidth) {
        ComputedStyle s = b.style;
        b.borderTop = Math.max(0, s.borderTopWidth);
        b.borderRight = Math.max(0, s.borderRightWidth);
        b.borderBottom = Math.max(0, s.borderBottomWidth);
        b.borderLeft = Math.max(0, s.borderLeftWidth);
        b.paddingTop = nonNegative(s.paddingTop, cbWidth);
        b.paddingRight = nonNegative(s.paddingRight, cbWidth);
        b.paddingBottom = nonNegative(s.paddingBottom, cbWidth);
        b.paddingLeft = nonNegative(s.paddingLeft, cbWidth);
    }

    /**
     * Resolves a length that cannot be negative (padding, gaps, track sizes): animations with overshooting easings
     * can briefly extrapolate them below zero. Percentages of an unknown reference resolve to 0.
     */
    static float nonNegative(Length l, float reference) {
        return Math.max(0, l.resolve(reference));
    }

    /**
     * Border plus padding on both sides in an axis straight from the style, with percentages of {@code cbWidth}
     * (NaN for intrinsic sizing, where they count as zero).
     */
    static float paddingBorder(ComputedStyle s, Axis axis, float cbWidth) {
        return axis.isHorizontal()
                ? Math.max(0, s.borderLeftWidth) + Math.max(0, s.borderRightWidth)
                        + nonNegative(s.paddingLeft, cbWidth) + nonNegative(s.paddingRight, cbWidth)
                : Math.max(0, s.borderTopWidth) + Math.max(0, s.borderBottomWidth)
                        + nonNegative(s.paddingTop, cbWidth) + nonNegative(s.paddingBottom, cbWidth);
    }

    /** Both margins in an axis straight from the style (auto as zero), with percentages of {@code cbWidth}. */
    static float margins(ComputedStyle s, Axis axis, float cbWidth) {
        return axis.marginStart(s).resolve(cbWidth) + axis.marginEnd(s).resolve(cbWidth);
    }

    /**
     * Gives a box's auto margins in an axis their share of {@code free}, the space left with auto margins counted as
     * zero (split equally when both are auto; nothing when it is negative). Returns whether there were any.
     */
    static boolean resolveAutoMargins(Box b, Axis axis, float free) {
        boolean start = axis.marginStart(b.style).isAuto(), end = axis.marginEnd(b.style).isAuto();
        if (!start && !end) return false;
        float share = Math.max(0, free) / (start && end ? 2 : 1);
        axis.setMargins(b, axis.marginStart(b) + (start ? share : 0), axis.marginEnd(b) + (end ? share : 0));
        return true;
    }

    static float paddingBorderWidth(Box b) {
        return b.borderLeft + b.paddingLeft + b.paddingRight + b.borderRight;
    }

    static float paddingBorderHeight(Box b) {
        return b.borderTop + b.paddingTop + b.paddingBottom + b.borderBottom;
    }

    /**
     * A specified size ({@code width}, {@code min-height}...) as a border-box size, or NaN when it does not resolve
     * to a length: {@code auto}, {@code none}, sizing keywords, or a percentage of an unknown (NaN) reference.
     */
    static float borderBoxSize(ComputedStyle s, Length l, float reference, float paddingBorder) {
        if (!l.isFixed()) return Float.NaN;
        float v = l.resolve(reference, Float.NaN);
        if (Float.isNaN(v)) return Float.NaN;
        // Negative sizes are invalid in CSS but animations can overshoot into them: clamp.
        return s.boxSizing == BoxSizing.CONTENT_BOX ? Math.max(0, v) + paddingBorder : Math.max(v, paddingBorder);
    }

    /** Clamps {@code v} to {@code [min, max]}, ignoring NaN bounds; the minimum wins when they conflict (CSS). */
    static float clamp(float v, float min, float max) {
        if (!Float.isNaN(max) && v > max) v = max;
        if (!Float.isNaN(min) && v < min) v = min;
        return v;
    }

    /** {@code a} unless it is NaN, else {@code b}. */
    static float or(float a, float b) {
        return Float.isNaN(a) ? b : a;
    }

    /**
     * Converts a border-box size in axis {@code from} into the other axis through {@code aspect-ratio}, which
     * applies to the box named by {@code box-sizing}. NaN when there is no ratio (or no size). Needs the box's
     * padding and border resolved.
     */
    static float transfer(Box b, float size, Axis from) {
        float ratio = b.style.aspectRatio;
        if (Float.isNaN(ratio) || ratio <= 0 || Float.isNaN(size)) return Float.NaN;
        boolean contentBox = b.style.boxSizing == BoxSizing.CONTENT_BOX;
        float inner = contentBox ? Math.max(0, size - from.paddingBorder(b)) : size;
        float out = from.isHorizontal() ? inner / ratio : inner * ratio;
        return contentBox ? out + from.other().paddingBorder(b) : out;
    }
}
