package dev.vellum.engine.css;

import dev.vellum.engine.css.ComponentValue.Block;
import dev.vellum.engine.css.ComponentValue.Func;
import dev.vellum.engine.css.Token.Type;
import dev.vellum.engine.style.Length;

import java.util.List;

/**
 * Numbers, lengths, angles and times, including {@code calc()}, {@code min()}, {@code max()} and {@code clamp()}.
 * Relative length units become px here (em, rem, vw, vh, vmin, vmax, dp); percentages stay symbolic, and math on
 * lengths folds into a {@link Length}'s {@code px + percent} pair. {@code min/max/clamp} over a mix of px and
 * percentages cannot be folded and are rejected.
 */
final class Numeric {
    private Numeric() {}

    enum Kind { NUMBER, LENGTH, ANGLE, TIME }

    /**
     * A computed numeric value: {@code value} is the number, px, degrees or milliseconds; lengths may also have a
     * {@code percent} part (0..100 scale). {@code integer} is the number token's integer type flag.
     */
    record Quantity(Kind kind, float value, float percent, boolean integer) {
        /** A plain percentage (or zero length): the only lengths colour channels and opacity accept. */
        boolean isPercentage() {
            return kind == Kind.LENGTH && value == 0;
        }

        Quantity scale(float f) {
            return new Quantity(kind, value * f, percent * f, integer && f == Math.round(f));
        }
    }

    // ---- Typed readers: each consumes one component value, or nothing when it does not fit ----

    /**
     * A length or percentage (unitless 0 allowed). Where negative values are not allowed, a negative literal is
     * invalid but a math function is clamped, as CSS does ({@code calc(100% - 12px)} stays as it is).
     */
    static Length length(ValueReader r, ValueContext ctx, boolean allowNegative) {
        int m = r.mark();
        ComponentValue v = r.next();
        Quantity q = of(v, ctx);
        Length result = null;
        if (q != null && q.kind == Kind.NUMBER && q.value == 0) {
            result = Length.ZERO;
        } else if (q != null && q.kind == Kind.LENGTH) {
            boolean negative = q.value < 0 || q.percent < 0;
            if (allowNegative || !negative) result = Length.of(q.value, q.percent);
            else if (v instanceof ComponentValue.Func) {
                boolean mixed = q.value != 0 && q.percent != 0;
                result = mixed ? Length.of(q.value, q.percent) : Length.ZERO;
            }
        }
        if (result == null) r.reset(m);
        return result;
    }

    /** A length without percentages, in px. */
    static Float px(ValueReader r, ValueContext ctx, boolean allowNegative) {
        int m = r.mark();
        Length l = length(r, ctx, allowNegative);
        if (l != null && !l.hasPercent()) return l.px;
        r.reset(m);
        return null;
    }

    /** An angle in degrees (unitless 0 allowed). */
    static Float angle(ValueReader r, ValueContext ctx) {
        Quantity q = read(r, ctx, Kind.ANGLE);
        return q == null ? null : q.value;
    }

    /** A time in milliseconds. */
    static Float time(ValueReader r, ValueContext ctx) {
        Quantity q = read(r, ctx, Kind.TIME);
        return q == null ? null : q.value;
    }

    static Float number(ValueReader r, ValueContext ctx) {
        Quantity q = read(r, ctx, Kind.NUMBER);
        return q == null ? null : q.value;
    }

    /** An integer; math functions are rounded, as CSS does for integer properties. */
    static Integer integer(ValueReader r, ValueContext ctx) {
        int m = r.mark();
        ComponentValue v = r.next();
        Quantity q = of(v, ctx);
        if (q != null && q.kind == Kind.NUMBER && (q.integer || v instanceof Func)) return Math.round(q.value);
        r.reset(m);
        return null;
    }

    /** A number, or a percentage as a fraction ({@code 50%} → 0.5): opacity, alpha. */
    static Float fraction(ValueReader r, ValueContext ctx) {
        int m = r.mark();
        Quantity q = of(r.next(), ctx);
        if (q != null && q.kind == Kind.NUMBER) return q.value;
        if (q != null && q.isPercentage()) return q.percent / 100f;
        r.reset(m);
        return null;
    }

    /** Reads a value of the given kind; unitless 0 also counts as an angle. */
    private static Quantity read(ValueReader r, ValueContext ctx, Kind kind) {
        int m = r.mark();
        Quantity q = of(r.next(), ctx);
        if (q != null && kind == Kind.ANGLE && q.kind == Kind.NUMBER && q.value == 0) return new Quantity(kind, 0, 0, false);
        if (q != null && q.kind == kind) return q;
        r.reset(m);
        return null;
    }

    /** One numeric token or math function, or null. */
    static Quantity of(ComponentValue v, ValueContext ctx) {
        if (v instanceof Token t) return token(t, ctx);
        if (v instanceof Func f) return math(f, ctx);
        return null;
    }

    private static Quantity token(Token t, ValueContext ctx) {
        float n = (float) t.number;
        return switch (t.type) {
            case NUMBER -> new Quantity(Kind.NUMBER, n, 0, t.flag);
            case PERCENTAGE -> new Quantity(Kind.LENGTH, 0, n, false);
            case DIMENSION -> dimension(n, t.lower, ctx);
            default -> null;
        };
    }

    private static Quantity dimension(float n, String unit, ValueContext ctx) {
        return switch (unit) {
            case "px" -> lengthPx(n);
            case "em" -> lengthPx(n * ctx.em());
            case "rem" -> lengthPx(n * ctx.rem());
            case "vw" -> lengthPx(n * ctx.viewportWidth() / 100f);
            case "vh" -> lengthPx(n * ctx.viewportHeight() / 100f);
            case "vmin" -> lengthPx(n * Math.min(ctx.viewportWidth(), ctx.viewportHeight()) / 100f);
            case "vmax" -> lengthPx(n * Math.max(ctx.viewportWidth(), ctx.viewportHeight()) / 100f);
            case "dp" -> lengthPx(n / ctx.devicePixelRatio());
            case "deg" -> new Quantity(Kind.ANGLE, n, 0, false);
            case "rad" -> new Quantity(Kind.ANGLE, (float) Math.toDegrees(n), 0, false);
            case "grad" -> new Quantity(Kind.ANGLE, n * 0.9f, 0, false);
            case "turn" -> new Quantity(Kind.ANGLE, n * 360f, 0, false);
            case "s" -> new Quantity(Kind.TIME, n * 1000f, 0, false);
            case "ms" -> new Quantity(Kind.TIME, n, 0, false);
            default -> null;
        };
    }

    private static Quantity lengthPx(float px) {
        return new Quantity(Kind.LENGTH, px, 0, false);
    }

    // ---- Math functions ----

    private static Quantity math(Func f, ValueContext ctx) {
        List<List<ComponentValue>> args = ValueReader.splitCommas(f.args());
        return switch (f.name()) {
            case "calc" -> args.size() == 1 ? new Calc(args.get(0), ctx).expression() : null;
            case "min", "max" -> {
                Quantity best = null;
                for (List<ComponentValue> arg : args) {
                    Quantity q = new Calc(arg, ctx).expression();
                    best = best == null ? q : pick(best, q, f.name().equals("max"));
                    if (best == null) yield null;
                }
                yield best;
            }
            case "clamp" -> {
                if (args.size() != 3) yield null;
                Quantity lo = new Calc(args.get(0), ctx).expression();
                Quantity val = new Calc(args.get(1), ctx).expression();
                Quantity hi = new Calc(args.get(2), ctx).expression();
                if (lo == null || val == null || hi == null) yield null;
                Quantity upper = pick(val, hi, false);
                yield upper == null ? null : pick(lo, upper, true);
            }
            default -> null;
        };
    }

    /** The larger (or smaller) of two comparable quantities; null when they cannot be compared. */
    private static Quantity pick(Quantity a, Quantity b, boolean max) {
        if (a == null || b == null || a.kind != b.kind) return null;
        float av, bv;
        if (a.percent == 0 && b.percent == 0) { av = a.value; bv = b.value; }
        else if (a.value == 0 && b.value == 0) { av = a.percent; bv = b.percent; }
        else return null;
        return (max ? av >= bv : av <= bv) ? a : b;
    }

    /** Recursive descent over a calc() expression: sums of products of values, parentheses and nested math. */
    private static final class Calc {
        private final ValueReader in;
        private final ValueContext ctx;

        Calc(List<ComponentValue> values, ValueContext ctx) {
            this.in = new ValueReader(values);
            this.ctx = ctx;
        }

        /** The whole input as one expression, or null. */
        Quantity expression() {
            Quantity q = sum();
            return q != null && in.atEnd() ? q : null;
        }

        private Quantity sum() {
            Quantity a = product();
            while (a != null) {
                if (in.delim('+')) a = add(a, product(), 1);
                else if (in.delim('-')) a = add(a, product(), -1);
                else break;
            }
            return a;
        }

        private Quantity product() {
            Quantity a = factor();
            while (a != null) {
                if (in.delim('*')) {
                    Quantity b = factor();
                    if (b == null) return null;
                    a = a.kind == Kind.NUMBER ? b.scale(a.value) : b.kind == Kind.NUMBER ? a.scale(b.value) : null;
                } else if (in.delim('/')) {
                    Quantity b = factor();
                    a = b == null || b.kind != Kind.NUMBER || b.value == 0 ? null : a.scale(1 / b.value);
                } else {
                    break;
                }
            }
            return a;
        }

        private Quantity factor() {
            ComponentValue v = in.next();
            if (v instanceof Block b && b.open() == '(') return new Calc(b.body(), ctx).expression();
            if (v instanceof Token t && t.is(Type.IDENT)) {
                return switch (t.lower) {
                    case "pi" -> new Quantity(Kind.NUMBER, (float) Math.PI, 0, false);
                    case "e" -> new Quantity(Kind.NUMBER, (float) Math.E, 0, false);
                    default -> null;
                };
            }
            return of(v, ctx);
        }

        private static Quantity add(Quantity a, Quantity b, int sign) {
            if (b == null || a.kind != b.kind) return null;
            return new Quantity(a.kind, a.value + sign * b.value, a.percent + sign * b.percent, a.integer && b.integer);
        }
    }
}
