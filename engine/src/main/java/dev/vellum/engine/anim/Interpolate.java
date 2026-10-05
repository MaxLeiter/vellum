package dev.vellum.engine.anim;

import dev.vellum.engine.style.Colors;
import dev.vellum.engine.style.Length;
import dev.vellum.engine.style.Prop;
import dev.vellum.engine.style.Shadow;
import dev.vellum.engine.style.TransformFunction;
import dev.vellum.engine.style.TransformFunction.Interpolated;
import dev.vellum.engine.style.TransformFunction.Matrix;
import dev.vellum.engine.style.TransformFunction.Rotate;
import dev.vellum.engine.style.TransformFunction.Scale;
import dev.vellum.engine.style.TransformFunction.Skew;
import dev.vellum.engine.style.TransformFunction.Translate;
import dev.vellum.engine.style.Visibility;

import java.util.List;

/**
 * Interpolation of computed values by {@link Prop.Interp}. Values are what {@link Prop#get} returns.
 *
 * <p>Pairs that cannot interpolate smoothly ({@link #canInterpolate} is false: keywords such as {@code auto},
 * {@code normal} line heights, {@code auto} z-index, mismatched shadows, discrete properties) flip from one value to
 * the other at {@code t = 0.5}. Transitions only start for pairs that can interpolate; keyframe animations accept
 * both.
 */
public final class Interpolate {
    private Interpolate() {}

    /** The value of {@code prop} at progress {@code t} from {@code from} to {@code to}. {@code t} may overshoot. */
    @SuppressWarnings("unchecked")
    public static Object value(Prop prop, Object from, Object to, float t) {
        if (t == 0) return from;
        if (t == 1) return to;
        if (!canInterpolate(prop, from, to)) return t < 0.5f ? from : to;
        return switch (prop.interpolation) {
            case LENGTH -> Length.lerp((Length) from, (Length) to, t);
            case FLOAT -> lerp((Float) from, (Float) to, t);
            case INT -> Math.round(lerp((Integer) from, (Integer) to, t));
            case COLOR -> Colors.lerp((Integer) from, (Integer) to, t);
            case SHADOWS -> shadows((List<Shadow>) from, (List<Shadow>) to, t);
            case TRANSFORM -> transforms((List<TransformFunction>) from, (List<TransformFunction>) to, t);
            // Only visibility interpolates among discrete properties: visible throughout when either end is.
            case DISCRETE -> t < 0 ? from : t > 1 ? to : Visibility.VISIBLE;
            case NONE -> throw new AssertionError(prop);
        };
    }

    /**
     * True when {@link #value} interpolates smoothly between the two values (or, for visibility, keeps the element
     * visible in between) rather than flipping at the midpoint. {@link Prop.Interp#NONE} properties never do.
     */
    @SuppressWarnings("unchecked")
    public static boolean canInterpolate(Prop prop, Object from, Object to) {
        return switch (prop.interpolation) {
            case NONE -> false;
            case DISCRETE -> prop == Prop.VISIBILITY && (from == Visibility.VISIBLE || to == Visibility.VISIBLE);
            case LENGTH -> ((Length) from).isFixed() && ((Length) to).isFixed();
            case FLOAT -> !((Float) from).isNaN() && !((Float) to).isNaN(); // NaN: normal line height, auto ratio
            case INT -> from != null && to != null; // null: auto z-index
            case COLOR, TRANSFORM -> true; // mismatched transform lists blend as matrices at paint time
            case SHADOWS -> shadowsMatch((List<Shadow>) from, (List<Shadow>) to);
        };
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    // ---- Shadows ----

    /** Pairwise, after padding the shorter list: neither shadow is Minecraft's native one and both agree on inset. */
    private static boolean shadowsMatch(List<Shadow> a, List<Shadow> b) {
        for (int i = 0, n = Math.min(a.size(), b.size()); i < n; i++) {
            if (a.get(i).inset() != b.get(i).inset()) return false;
        }
        for (Shadow s : a) if (s.isNative()) return false;
        for (Shadow s : b) if (s.isNative()) return false;
        return true;
    }

    private static List<Shadow> shadows(List<Shadow> a, List<Shadow> b, float t) {
        Shadow[] out = new Shadow[Math.max(a.size(), b.size())];
        for (int i = 0; i < out.length; i++) out[i] = Shadow.lerp(shadowAt(a, i, b), shadowAt(b, i, a), t);
        return List.of(out);
    }

    /** The shadow at {@code i}, or a transparent zero shadow matching {@code other}'s when the list is shorter. */
    private static Shadow shadowAt(List<Shadow> list, int i, List<Shadow> other) {
        return i < list.size() ? list.get(i) : new Shadow(0, 0, 0, 0, Colors.TRANSPARENT, other.get(i).inset());
    }

    // ---- Transforms ----

    /**
     * Function by function when both lists have the same function types (an empty list stands for identity
     * functions of the other list's types); otherwise an {@link Interpolated} blend the painter resolves by
     * decomposing both sides. Matrices always blend that way: interpolating their components would collapse a
     * rotation (a half turn passes through the zero matrix).
     */
    private static List<TransformFunction> transforms(List<TransformFunction> from, List<TransformFunction> to,
                                                      float t) {
        List<TransformFunction> a = from.isEmpty() ? identities(to) : from;
        List<TransformFunction> b = to.isEmpty() ? identities(from) : to;
        if (a != null && b != null && a.size() == b.size()) {
            TransformFunction[] out = new TransformFunction[a.size()];
            int i = 0;
            while (i < out.length && (out[i] = lerp(a.get(i), b.get(i), t)) != null) i++;
            if (i == out.length) return List.of(out);
        }
        return List.of(new Interpolated(from, to, t));
    }

    /** Null when the functions do not have the same type. */
    private static TransformFunction lerp(TransformFunction a, TransformFunction b, float t) {
        return switch (a) {
            case Translate x when b instanceof Translate y ->
                    new Translate(Length.lerp(x.x(), y.x(), t), Length.lerp(x.y(), y.y(), t));
            case Scale x when b instanceof Scale y -> new Scale(lerp(x.x(), y.x(), t), lerp(x.y(), y.y(), t));
            case Rotate x when b instanceof Rotate y -> new Rotate(lerp(x.degrees(), y.degrees(), t));
            case Skew x when b instanceof Skew y ->
                    new Skew(lerp(x.xDegrees(), y.xDegrees(), t), lerp(x.yDegrees(), y.yDegrees(), t));
            default -> null;
        };
    }

    /** Identity functions of the same types as {@code list}, or null if it holds a function that blends as a whole. */
    private static List<TransformFunction> identities(List<TransformFunction> list) {
        TransformFunction[] out = new TransformFunction[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = switch (list.get(i)) {
                case Translate ignored -> new Translate(Length.ZERO, Length.ZERO);
                case Scale ignored -> new Scale(1, 1);
                case Rotate ignored -> new Rotate(0);
                case Skew ignored -> new Skew(0, 0);
                case Matrix ignored -> null;
                case Interpolated ignored -> null;
            };
            if (out[i] == null) return null;
        }
        return List.of(out);
    }
}
