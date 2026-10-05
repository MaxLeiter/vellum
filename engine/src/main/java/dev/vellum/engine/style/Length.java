package dev.vellum.engine.style;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A computed length: {@code px + percent% of a reference}, or one of the keyword sizes.
 *
 * <p>Relative units (em, rem, vw, vh, vmin, vmax) are converted to px when the style is computed. Percentages stay
 * symbolic until layout, because their reference (containing block width, height, font size...) is only known there.
 *
 * <p>Math functions fold into the {@code px + percent} pair where they can: sums, differences and products with
 * numbers always ({@code calc(100% - 12px)}), and {@code min()}, {@code max()} and {@code clamp()} over arguments that
 * are all px or all percentages. A comparison that mixes them ({@code clamp(72px, 25%, 100px)}) can only be decided
 * once the reference is known, so it stays a {@link Term} that {@link #resolve} evaluates: such a length is
 * {@code px + percent% + factor × term + ...}. Plain lengths have no terms and resolve on the linear path.
 */
public final class Length {
    public enum Kind { FIXED, AUTO, NONE, MIN_CONTENT, MAX_CONTENT, FIT_CONTENT }

    /** The comparisons a {@link Term} makes. {@code CLAMP} has three arguments: min, value, max. */
    private enum Op { MIN, MAX, CLAMP }

    /**
     * {@code factor × op(args)}: a {@code min()}, {@code max()} or {@code clamp()} whose arguments mix px and
     * percentages, so it cannot be decided before the reference is known. Arguments are fixed lengths.
     */
    private record Term(Op op, List<Length> args, float factor) {
        float resolve(float reference) {
            float v = args.get(0).resolve(reference);
            if (op == Op.CLAMP) {
                v = Math.max(v, Math.min(args.get(1).resolve(reference), args.get(2).resolve(reference)));
            } else {
                for (int i = 1; i < args.size(); i++) {
                    float a = args.get(i).resolve(reference);
                    v = op == Op.MIN ? Math.min(v, a) : Math.max(v, a);
                }
            }
            return factor * v;
        }

        /** The function itself, without its factor. */
        String function() {
            StringBuilder sb = new StringBuilder(op.name().toLowerCase(Locale.ROOT)).append('(');
            for (int i = 0; i < args.size(); i++) sb.append(i == 0 ? "" : ", ").append(args.get(i));
            return sb.append(')').toString();
        }
    }

    public static final Length ZERO = new Length(Kind.FIXED, 0, 0, List.of());
    public static final Length AUTO = new Length(Kind.AUTO, 0, 0, List.of());
    public static final Length NONE = new Length(Kind.NONE, 0, 0, List.of());
    public static final Length MIN_CONTENT = new Length(Kind.MIN_CONTENT, 0, 0, List.of());
    public static final Length MAX_CONTENT = new Length(Kind.MAX_CONTENT, 0, 0, List.of());
    public static final Length FIT_CONTENT = new Length(Kind.FIT_CONTENT, 0, 0, List.of());
    public static final Length PERCENT_100 = new Length(Kind.FIXED, 0, 100, List.of());
    public static final Length PERCENT_50 = new Length(Kind.FIXED, 0, 50, List.of());

    public final Kind kind;
    /** Absolute part, in GUI pixels. The whole length when it has no percentage ({@link #hasPercent}). */
    public final float px;
    /** Percentage part, 0..100 scale (50 means 50%). With {@link #px}, the whole length when it is {@link #isLinear}. */
    public final float percent;
    /** Comparisons added to {@code px + percent}; empty for plain lengths. Each holds a percentage. */
    private final List<Term> terms;

    private Length(Kind kind, float px, float percent, List<Term> terms) {
        this.kind = kind;
        this.px = px;
        this.percent = percent;
        this.terms = terms;
    }

    public static Length px(float px) {
        return of(px, 0);
    }

    public static Length percent(float percent) {
        return of(0, percent);
    }

    public static Length of(float px, float percent) {
        return px == 0 && percent == 0 ? ZERO : new Length(Kind.FIXED, px, percent, List.of());
    }

    private static Length of(float px, float percent, List<Term> terms) {
        return terms.isEmpty() ? of(px, percent) : new Length(Kind.FIXED, px, percent, List.copyOf(terms));
    }

    public boolean isFixed() { return kind == Kind.FIXED; }
    /** Whether a fixed length is exactly {@code px + percent%}, without comparisons waiting for the reference. */
    public boolean isLinear() { return terms.isEmpty(); }
    public boolean isAuto() { return kind == Kind.AUTO; }
    /** Whether the length depends on its percentage reference. */
    public boolean hasPercent() { return kind == Kind.FIXED && (percent != 0 || !terms.isEmpty()); }

    /** A fixed length below zero whatever its (non-negative) reference: {@code -2px}, {@code calc(-1px - 5%)}. */
    public boolean isNegative() {
        return kind == Kind.FIXED && terms.isEmpty() && px <= 0 && percent <= 0 && (px < 0 || percent < 0);
    }

    /**
     * Resolves against a percentage reference. Keywords resolve to {@code fallback}. When the reference is unknown
     * (NaN, e.g. a percentage height inside an auto-height parent) a length with a percentage part also resolves to
     * {@code fallback}, as CSS treats it as {@code auto}.
     */
    public float resolve(float reference, float fallback) {
        if (kind != Kind.FIXED) return fallback;
        if (!hasPercent()) return px;
        if (Float.isNaN(reference)) return fallback;
        float v = px + percent * reference / 100f;
        for (int i = 0; i < terms.size(); i++) v += terms.get(i).resolve(reference);
        return v;
    }

    /** Resolves against a reference; keywords and unresolvable percentages become 0. */
    public float resolve(float reference) {
        return resolve(reference, 0f);
    }

    // ---- Math on fixed lengths (calc(), min(), max(), clamp(), interpolation) ----

    /** {@code a + b}. Equal terms merge (their factors add), so sums of interpolated values stay small. */
    public static Length sum(Length a, Length b) {
        if (a.terms.isEmpty() && b.terms.isEmpty()) return of(a.px + b.px, a.percent + b.percent);
        List<Term> terms = new ArrayList<>(a.terms);
        for (Term t : b.terms) {
            int i = indexOf(terms, t);
            if (i < 0) {
                terms.add(t);
                continue;
            }
            float factor = terms.get(i).factor + t.factor;
            if (factor == 0) terms.remove(i);
            else terms.set(i, new Term(t.op, t.args, factor));
        }
        return of(a.px + b.px, a.percent + b.percent, terms);
    }

    private static int indexOf(List<Term> terms, Term t) {
        for (int i = 0; i < terms.size(); i++) {
            if (terms.get(i).op == t.op && terms.get(i).args.equals(t.args)) return i;
        }
        return -1;
    }

    /** {@code this × factor}. */
    public Length times(float factor) {
        if (factor == 0) return ZERO;
        if (terms.isEmpty()) return of(px * factor, percent * factor);
        List<Term> scaled = new ArrayList<>(terms.size());
        for (Term t : terms) scaled.add(new Term(t.op, t.args, t.factor * factor));
        return of(px * factor, percent * factor, scaled);
    }

    /** {@code min(args...)}: arguments of one kind (all px, or all percentages) fold; the rest wait for the reference. */
    public static Length min(List<Length> args) {
        return compare(Op.MIN, args);
    }

    /** {@code max(args...)}, folding like {@link #min}. */
    public static Length max(List<Length> args) {
        return compare(Op.MAX, args);
    }

    /** {@code clamp(min, value, max)}: {@code max(min, min(value, max))}, folded when all three are of one kind. */
    public static Length clamp(Length min, Length value, Length max) {
        if (sameKind(min, value) && sameKind(value, max) && sameKind(min, max)) {
            return compare(Op.MAX, List.of(min, compare(Op.MIN, List.of(value, max))));
        }
        return of(0, 0, List.of(new Term(Op.CLAMP, List.of(min, value, max), 1)));
    }

    private static Length compare(Op op, List<Length> args) {
        List<Length> left = new ArrayList<>(args.size());
        for (Length a : args) {
            int i = 0;
            while (i < left.size() && !sameKind(left.get(i), a)) i++;
            if (i == left.size()) left.add(a);
            else left.set(i, pick(op, left.get(i), a));
        }
        return left.size() == 1 ? left.getFirst() : of(0, 0, List.of(new Term(op, left, 1)));
    }

    /** Whether two lengths compare without a reference: both plain px, or both plain percentages. */
    private static boolean sameKind(Length a, Length b) {
        if (!a.terms.isEmpty() || !b.terms.isEmpty()) return false;
        return a.percent == 0 && b.percent == 0 || a.px == 0 && b.px == 0;
    }

    private static Length pick(Op op, Length a, Length b) {
        float av = a.px + a.percent, bv = b.px + b.percent; // one of the parts is zero in both
        return (op == Op.MIN) == (av <= bv) ? a : b;
    }

    /**
     * Interpolation for transitions and animations. Keywords do not interpolate: the result flips at t = 0.5. Lengths
     * with terms interpolate as CSS defines for math functions: {@code calc(a × (1 - t) + b × t)}.
     */
    public static Length lerp(Length a, Length b, float t) {
        if (a.kind != Kind.FIXED || b.kind != Kind.FIXED) return t < 0.5f ? a : b;
        if (a.terms.isEmpty() && b.terms.isEmpty()) {
            return of(a.px + (b.px - a.px) * t, a.percent + (b.percent - a.percent) * t);
        }
        return sum(a.times(1 - t), b.times(t));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Length l && l.kind == kind && l.px == px && l.percent == percent && l.terms.equals(terms);
    }

    @Override
    public int hashCode() {
        return ((kind.ordinal() * 31 + Float.hashCode(px)) * 31 + Float.hashCode(percent)) * 31 + terms.hashCode();
    }

    /** CSS text: {@code 4px}, {@code 50%}, {@code calc(50% + 4px)}, {@code min(10px, 50%)}, {@code calc(...)}. */
    @Override
    public String toString() {
        if (kind != Kind.FIXED) return kind.name().toLowerCase(Locale.ROOT).replace('_', '-');
        if (terms.isEmpty() && percent == 0) return fmt(px) + "px";
        if (terms.isEmpty() && px == 0) return fmt(percent) + "%";
        if (px == 0 && percent == 0 && terms.size() == 1 && terms.getFirst().factor == 1) return terms.getFirst().function();
        StringBuilder sb = new StringBuilder("calc(");
        boolean first = true;
        if (percent != 0) first = part(sb, first, percent, "%");
        if (px != 0) first = part(sb, first, px, "px");
        for (Term t : terms) {
            float factor = first ? t.factor : Math.abs(t.factor);
            if (!first) sb.append(t.factor < 0 ? " - " : " + ");
            if (factor != 1) sb.append(fmt(factor)).append(" * "); // a leading "-min(" would be a function named -min
            sb.append(t.function());
            first = false;
        }
        return sb.append(')').toString();
    }

    /** Appends one part of a sum: its sign becomes the operator, except for the first part. */
    private static boolean part(StringBuilder sb, boolean first, float value, String unit) {
        if (!first) sb.append(value < 0 ? " - " : " + ");
        sb.append(fmt(first ? value : Math.abs(value))).append(unit);
        return false;
    }

    private static String fmt(float f) {
        return f == (int) f ? Integer.toString((int) f) : Float.toString(f);
    }
}
