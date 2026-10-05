package dev.vellum.engine.css;

import dev.vellum.engine.css.ComponentValue.Func;
import dev.vellum.engine.style.TimingFunction;
import dev.vellum.engine.style.TimingFunction.CubicBezier;
import dev.vellum.engine.style.TimingFunction.Steps;

import java.util.List;
import java.util.Map;

/** Easing functions: the keywords, {@code cubic-bezier()} and {@code steps()}. */
public final class Timings {
    private static final Map<String, TimingFunction> KEYWORDS = Map.of(
            "linear", TimingFunction.LINEAR, "ease", TimingFunction.EASE, "ease-in", TimingFunction.EASE_IN,
            "ease-out", TimingFunction.EASE_OUT, "ease-in-out", TimingFunction.EASE_IN_OUT,
            "step-start", new Steps(1, Steps.Jump.START), "step-end", new Steps(1, Steps.Jump.END));
    private static final Map<String, Steps.Jump> JUMPS = Map.of("start", Steps.Jump.START, "end", Steps.Jump.END,
            "jump-start", Steps.Jump.START, "jump-end", Steps.Jump.END, "jump-none", Steps.Jump.NONE,
            "jump-both", Steps.Jump.BOTH);

    private Timings() {}

    /** Parses an easing written as CSS ({@code element.animate()} options and keyframes), or returns null. */
    public static TimingFunction parse(String css) {
        ValueReader r = new ValueReader(CssParser.trim(CssParser.parseComponentValues(css)));
        TimingFunction f = read(r, new ValueContext());
        return f != null && r.atEnd() ? f : null;
    }

    /** Reads one timing function, or returns null without consuming anything. */
    static TimingFunction read(ValueReader r, ValueContext ctx) {
        TimingFunction keyword = Keywords.read(r, KEYWORDS);
        if (keyword != null) return keyword;
        int m = r.mark();
        TimingFunction f = r.peek() instanceof Func func ? function(func, ctx) : null;
        if (f != null) r.next();
        else r.reset(m);
        return f;
    }

    private static TimingFunction function(Func f, ValueContext ctx) {
        List<List<ComponentValue>> args = ValueReader.splitCommas(f.args());
        if (f.name().equals("cubic-bezier") && args.size() == 4) {
            float[] p = new float[4];
            for (int i = 0; i < 4; i++) {
                ValueReader r = new ValueReader(args.get(i));
                Float n = Numeric.number(r, ctx);
                if (n == null || !r.atEnd() || (i % 2 == 0 && (n < 0 || n > 1))) return null;
                p[i] = n;
            }
            return new CubicBezier(p[0], p[1], p[2], p[3]);
        }
        if (f.name().equals("steps") && (args.size() == 1 || args.size() == 2)) {
            ValueReader r = new ValueReader(args.get(0));
            Integer n = Numeric.integer(r, ctx);
            Steps.Jump jump = Steps.Jump.END;
            if (args.size() == 2) {
                ValueReader jr = new ValueReader(args.get(1));
                jump = Keywords.read(jr, JUMPS);
                if (jump == null || !jr.atEnd()) return null;
            }
            int min = jump == Steps.Jump.NONE ? 2 : 1;
            return n == null || !r.atEnd() || n < min ? null : new Steps(n, jump);
        }
        return null;
    }

    static String serialize(TimingFunction t) {
        for (Map.Entry<String, TimingFunction> e : KEYWORDS.entrySet()) if (e.getValue().equals(t)) return e.getKey();
        if (t instanceof CubicBezier b) {
            return "cubic-bezier(" + CssText.join(List.of(b.x1(), b.y1(), b.x2(), b.y2()), ", ", CssText::number) + ")";
        }
        Steps s = (Steps) t;
        return "steps(" + s.count() + ", jump-" + Keywords.css(s.jump()) + ")";
    }
}
