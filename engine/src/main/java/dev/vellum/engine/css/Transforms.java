package dev.vellum.engine.css;

import dev.vellum.engine.css.ComponentValue.Func;
import dev.vellum.engine.style.Length;
import dev.vellum.engine.style.TransformFunction;
import dev.vellum.engine.style.TransformFunction.Matrix;
import dev.vellum.engine.style.TransformFunction.Rotate;
import dev.vellum.engine.style.TransformFunction.Scale;
import dev.vellum.engine.style.TransformFunction.Skew;
import dev.vellum.engine.style.TransformFunction.Translate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** {@code transform} lists: translate, scale, rotate, skew (with their X/Y forms) and matrix. 2D only. */
final class Transforms {
    private Transforms() {}

    static List<TransformFunction> read(ValueReader r, ValueContext ctx) {
        if (r.ident("none")) return List.of();
        List<TransformFunction> out = new ArrayList<>();
        while (!r.atEnd()) {
            TransformFunction t = r.next() instanceof Func f ? function(f, ctx) : null;
            if (t == null) return null;
            out.add(t);
        }
        return out.isEmpty() ? null : List.copyOf(out);
    }

    /** A transform function's argument type and arity, and how to build it from the parsed arguments. */
    private record Signature(Longhand.Parser argument, int min, int max, Function<List<Object>, TransformFunction> build) {}

    private static final Longhand.Parser LENGTH = (r, ctx) -> Numeric.length(r, ctx, true);
    private static final Map<String, Signature> FUNCTIONS = Map.ofEntries(
            Map.entry("translate", new Signature(LENGTH, 1, 2, a -> new Translate(length(a, 0), length(a, 1)))),
            Map.entry("translatex", new Signature(LENGTH, 1, 1, a -> new Translate(length(a, 0), Length.ZERO))),
            Map.entry("translatey", new Signature(LENGTH, 1, 1, a -> new Translate(Length.ZERO, length(a, 0)))),
            Map.entry("scale", new Signature(Numeric::fraction, 1, 2,
                    a -> new Scale(number(a, 0, 1), number(a, 1, number(a, 0, 1))))),
            Map.entry("scalex", new Signature(Numeric::fraction, 1, 1, a -> new Scale(number(a, 0, 1), 1))),
            Map.entry("scaley", new Signature(Numeric::fraction, 1, 1, a -> new Scale(1, number(a, 0, 1)))),
            Map.entry("rotate", new Signature(Numeric::angle, 1, 1, a -> new Rotate(number(a, 0, 0)))),
            Map.entry("rotatez", new Signature(Numeric::angle, 1, 1, a -> new Rotate(number(a, 0, 0)))),
            Map.entry("skew", new Signature(Numeric::angle, 1, 2, a -> new Skew(number(a, 0, 0), number(a, 1, 0)))),
            Map.entry("skewx", new Signature(Numeric::angle, 1, 1, a -> new Skew(number(a, 0, 0), 0))),
            Map.entry("skewy", new Signature(Numeric::angle, 1, 1, a -> new Skew(0, number(a, 0, 0)))),
            Map.entry("matrix", new Signature(Numeric::number, 6, 6, a -> new Matrix(number(a, 0, 1), number(a, 1, 0),
                    number(a, 2, 0), number(a, 3, 1), number(a, 4, 0), number(a, 5, 0)))));

    private static TransformFunction function(Func f, ValueContext ctx) {
        Signature sig = FUNCTIONS.get(f.name());
        List<Object> args = sig == null ? null : args(f, ctx, sig.argument(), sig.min(), sig.max());
        return args == null ? null : sig.build().apply(args);
    }

    private static Length length(List<Object> args, int i) {
        return i < args.size() ? (Length) args.get(i) : Length.ZERO;
    }

    private static float number(List<Object> args, int i, float fallback) {
        return i < args.size() ? (Float) args.get(i) : fallback;
    }

    /** The comma-separated arguments of {@code f}, each parsed by {@code parser}; null if malformed. */
    private static List<Object> args(Func f, ValueContext ctx, Longhand.Parser parser, int min, int max) {
        List<List<ComponentValue>> parts = ValueReader.splitCommas(f.args());
        if (parts.size() < min || parts.size() > max) return null;
        List<Object> out = new ArrayList<>(parts.size());
        for (List<ComponentValue> part : parts) {
            ValueReader r = new ValueReader(part);
            Object v = parser.parse(r, ctx);
            if (v == null || !r.atEnd()) return null;
            out.add(v);
        }
        return out;
    }

    static String serialize(List<TransformFunction> list) {
        if (list.isEmpty()) return "none";
        return CssText.join(list, " ", t -> switch (t) {
            case Translate tr -> "translate(" + tr.x() + ", " + tr.y() + ")";
            case Scale s -> "scale(" + CssText.number(s.x()) + ", " + CssText.number(s.y()) + ")";
            case Rotate ro -> "rotate(" + CssText.deg(ro.degrees()) + ")";
            case Skew sk -> "skew(" + CssText.deg(sk.xDegrees()) + ", " + CssText.deg(sk.yDegrees()) + ")";
            case Matrix m -> "matrix(" + CssText.join(List.of(m.a(), m.b(), m.c(), m.d(), m.e(), m.f()), ", ",
                    CssText::number) + ")";
            case TransformFunction.Interpolated in -> serialize(in.t() < 0.5f ? in.from() : in.to());
        });
    }
}
