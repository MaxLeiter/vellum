package dev.vellum.engine.style;

/**
 * A computed length: {@code px + percent% of a reference}, or one of the keyword sizes.
 *
 * <p>Relative units (em, rem, vw, vh, vmin, vmax) are converted to px when the style is computed. Percentages stay
 * symbolic until layout, because their reference (containing block width, height, font size...) is only known there.
 * {@code calc()} expressions that add and subtract lengths and percentages fold into the {@code px + percent} pair,
 * which covers the common cases ({@code calc(100% - 12px)}).
 */
public final class Length {
    public enum Kind { FIXED, AUTO, NONE, MIN_CONTENT, MAX_CONTENT, FIT_CONTENT }

    public static final Length ZERO = new Length(Kind.FIXED, 0, 0);
    public static final Length AUTO = new Length(Kind.AUTO, 0, 0);
    public static final Length NONE = new Length(Kind.NONE, 0, 0);
    public static final Length MIN_CONTENT = new Length(Kind.MIN_CONTENT, 0, 0);
    public static final Length MAX_CONTENT = new Length(Kind.MAX_CONTENT, 0, 0);
    public static final Length FIT_CONTENT = new Length(Kind.FIT_CONTENT, 0, 0);
    public static final Length PERCENT_100 = new Length(Kind.FIXED, 0, 100);
    public static final Length PERCENT_50 = new Length(Kind.FIXED, 0, 50);

    public final Kind kind;
    /** Absolute part, in GUI pixels. */
    public final float px;
    /** Percentage part, 0..100 scale (50 means 50%). */
    public final float percent;

    private Length(Kind kind, float px, float percent) {
        this.kind = kind;
        this.px = px;
        this.percent = percent;
    }

    public static Length px(float px) {
        return px == 0 ? ZERO : new Length(Kind.FIXED, px, 0);
    }

    public static Length percent(float percent) {
        return percent == 0 ? ZERO : new Length(Kind.FIXED, 0, percent);
    }

    public static Length of(float px, float percent) {
        if (px == 0 && percent == 0) return ZERO;
        return new Length(Kind.FIXED, px, percent);
    }

    public boolean isFixed() { return kind == Kind.FIXED; }
    public boolean isAuto() { return kind == Kind.AUTO; }
    public boolean isNone() { return kind == Kind.NONE; }
    /** True when this is a plain length with no percentage part, so it resolves without a reference. */
    public boolean isAbsolute() { return kind == Kind.FIXED && percent == 0; }
    public boolean hasPercent() { return kind == Kind.FIXED && percent != 0; }
    public boolean isIntrinsic() {
        return kind == Kind.MIN_CONTENT || kind == Kind.MAX_CONTENT || kind == Kind.FIT_CONTENT;
    }

    /**
     * Resolves against a percentage reference. Keywords resolve to {@code fallback}. When the reference is unknown
     * (NaN, e.g. a percentage height inside an auto-height parent) a length with a percentage part also resolves to
     * {@code fallback}, as CSS treats it as {@code auto}.
     */
    public float resolve(float reference, float fallback) {
        if (kind != Kind.FIXED) return fallback;
        if (percent == 0) return px;
        if (Float.isNaN(reference)) return fallback;
        return px + percent * reference / 100f;
    }

    /** Resolves against a reference; keywords and unresolvable percentages become 0. */
    public float resolve(float reference) {
        return resolve(reference, 0f);
    }

    /** Linear interpolation for transitions. Keywords do not interpolate: the result flips at t = 0.5. */
    public static Length lerp(Length a, Length b, float t) {
        if (a.kind != Kind.FIXED || b.kind != Kind.FIXED) return t < 0.5f ? a : b;
        return of(a.px + (b.px - a.px) * t, a.percent + (b.percent - a.percent) * t);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Length l && l.kind == kind && l.px == px && l.percent == percent;
    }

    @Override
    public int hashCode() {
        return (kind.ordinal() * 31 + Float.hashCode(px)) * 31 + Float.hashCode(percent);
    }

    @Override
    public String toString() {
        if (kind != Kind.FIXED) return kind.name().toLowerCase().replace('_', '-');
        if (percent == 0) return fmt(px) + "px";
        if (px == 0) return fmt(percent) + "%";
        return "calc(" + fmt(percent) + "% + " + fmt(px) + "px)";
    }

    private static String fmt(float f) {
        return f == (int) f ? Integer.toString((int) f) : Float.toString(f);
    }
}
