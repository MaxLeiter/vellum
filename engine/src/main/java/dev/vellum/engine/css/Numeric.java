package dev.vellum.engine.css;

import dev.vellum.engine.css.ComponentValue.Block;
import dev.vellum.engine.css.ComponentValue.Func;
import dev.vellum.engine.css.Token.Type;
import dev.vellum.engine.style.Length;

import java.util.ArrayList;
import java.util.List;

/**
 * Numbers, lengths, angles and times, including {@code calc()}, {@code min()}, {@code max()} and {@code clamp()}.
 * Relative length units become px here (em, rem, vw, vh, vmin, vmax, dp); percentages stay symbolic, and math on
 * lengths is {@link Length}'s: it folds into {@code px + percent} where it can and keeps comparisons that mix px and
 * percentages for layout to resolve.
 */
final class Numeric {
    private Numeric() {}

    enum Kind { NUMBER, LENGTH, ANGLE, TIME }

    /**
     * A computed numeric value: a {@code length} (with px and percentages) for {@link Kind#LENGTH}, otherwise a
     * {@code value} in plain numbers, degrees or milliseconds. {@code integer} is the number token's integer type flag.
     */
    record Quantity(Kind kind, float value, Length length, boolean integer) {
        static Quantity of(Length length) {
            return new Quantity(Kind.LENGTH, 0, length, false);
        }

        /** A plain percentage (or zero length): the only lengths colour channels and opacity accept. */
        boolean isPercentage() {
            return kind == Kind.LENGTH && length.isLinear() && length.px == 0;
        }

        /** The percentage of a {@link #isPercentage} quantity. */
        float percent() {
            return length.percent;
        }

        Quantity scale(float f) {
            if (kind == Kind.LENGTH) return of(length.times(f));
            return new Quantity(kind, value * f, null, integer && f == Math.round(f));
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
            if (allowNegative || !q.length.isNegative()) result = q.length;
            else if (v instanceof Func) result = Length.ZERO;
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
        if (q != null && q.isPercentage()) return q.percent() / 100f;
        r.reset(m);
        return null;
    }

    /** Reads a value of the given kind; unitless 0 also counts as an angle. */
    private static Quantity read(ValueReader r, ValueContext ctx, Kind kind) {
        int m = r.mark();
        Quantity q = of(r.next(), ctx);
        if (q != null && kind == Kind.ANGLE && q.kind == Kind.NUMBER && q.value == 0) return new Quantity(kind, 0, null, false);
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
            case NUMBER -> new Quantity(Kind.NUMBER, n, null, t.flag);
            case PERCENTAGE -> Quantity.of(Length.percent(n));
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
            case "deg" -> new Quantity(Kind.ANGLE, n, null, false);
            case "rad" -> new Quantity(Kind.ANGLE, (float) Math.toDegrees(n), null, false);
            case "grad" -> new Quantity(Kind.ANGLE, n * 0.9f, null, false);
            case "turn" -> new Quantity(Kind.ANGLE, n * 360f, null, false);
            case "s" -> new Quantity(Kind.TIME, n * 1000f, null, false);
            case "ms" -> new Quantity(Kind.TIME, n, null, false);
            default -> null;
        };
    }

    private static Quantity lengthPx(float px) {
        return Quantity.of(Length.px(px));
    }

    // ---- Math functions ----

    private static Quantity math(Func f, ValueContext ctx) {
        List<List<ComponentValue>> parts = ValueReader.splitCommas(f.args());
        List<Quantity> args = new ArrayList<>(parts.size());
        for (List<ComponentValue> part : parts) {
            Quantity q = new Calc(part, ctx).expression();
            if (q == null || !args.isEmpty() && q.kind != args.getFirst().kind) return null; // arguments agree in kind
            args.add(q);
        }
        if (args.isEmpty()) return null;
        return switch (f.name()) {
            case "calc" -> args.size() == 1 ? args.getFirst() : null;
            case "min", "max" -> compare(f.name().equals("min"), args);
            case "clamp" -> args.size() != 3 ? null : args.getFirst().kind == Kind.LENGTH
                    ? Quantity.of(Length.clamp(args.get(0).length, args.get(1).length, args.get(2).length))
                    : compare(false, List.of(args.get(0), compare(true, args.subList(1, 3))));
            default -> null;
        };
    }

    /** {@code min()} or {@code max()} of quantities of one kind; lengths that mix px and percentages stay symbolic. */
    private static Quantity compare(boolean min, List<Quantity> args) {
        if (args.getFirst().kind == Kind.LENGTH) {
            List<Length> lengths = new ArrayList<>(args.size());
            for (Quantity q : args) lengths.add(q.length);
            return Quantity.of(min ? Length.min(lengths) : Length.max(lengths));
        }
        Quantity best = args.getFirst();
        for (Quantity q : args) if (min ? q.value < best.value : q.value > best.value) best = q;
        return best;
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
                    case "pi" -> new Quantity(Kind.NUMBER, (float) Math.PI, null, false);
                    case "e" -> new Quantity(Kind.NUMBER, (float) Math.E, null, false);
                    default -> null;
                };
            }
            return of(v, ctx);
        }

        private static Quantity add(Quantity a, Quantity b, int sign) {
            if (b == null || a.kind != b.kind) return null;
            if (a.kind == Kind.LENGTH) return Quantity.of(Length.sum(a.length, b.length.times(sign)));
            return new Quantity(a.kind, a.value + sign * b.value, null, a.integer && b.integer);
        }
    }
}
