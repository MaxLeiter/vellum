package dev.vellum.engine.script;

import dev.vellum.engine.anim.Animation;
import dev.vellum.engine.anim.AnimationOptions;
import dev.vellum.engine.css.ResolvedKeyframe;
import dev.vellum.engine.css.StyleEngine;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.event.Event;
import dev.vellum.engine.style.AnimationSpec;
import dev.vellum.engine.style.TimingFunction;
import dev.vellum.shadow.rhino.Callable;
import dev.vellum.shadow.rhino.Context;
import dev.vellum.shadow.rhino.LambdaFunction;
import dev.vellum.shadow.rhino.NativeArray;
import dev.vellum.shadow.rhino.Scriptable;
import dev.vellum.shadow.rhino.ScriptableObject;
import dev.vellum.shadow.rhino.Undefined;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * {@code element.animate(keyframes, options)}. Keyframes (an array of objects, or an object of arrays) are
 * normalised the Web Animations way, each one's declarations computed by the style engine, and the result started
 * by the animation engine. Returns an Animation wrapper with play controls, {@code onfinish}/{@code oncancel} and a
 * {@code finished} promise.
 */
final class AnimationBindings {
    /** A keyframe as written: its offset (NaN until distributed), its easing (or null) and its CSS properties. */
    record Keyframe(double offset, TimingFunction easing, Map<String, String> properties) {
        Keyframe at(double newOffset) {
            return new Keyframe(newOffset, easing, properties);
        }

        String declarations() {
            return properties.entrySet().stream().map(p -> p.getKey() + ": " + p.getValue()).collect(Collectors.joining("; "));
        }
    }

    private static final Pattern EASING_FUNCTION = Pattern.compile("(cubic-bezier|steps)\\((.*)\\)");

    private final RhinoScriptRuntime rt;
    private final HostClass<Animation> animation;

    AnimationBindings(RhinoScriptRuntime rt) {
        this.rt = rt;
        animation = new HostClass<>(rt, "Animation", Animation.class, null, null).expose("Animation");
        animation.members()
                .action("play", (x, a) -> x.play())
                .action("pause", (x, a) -> x.pause())
                .action("cancel", (x, a) -> x.cancel())
                .action("finish", (x, a) -> x.finish())
                .action("reverse", (x, a) -> x.reverse())
                .get("playState", x -> x.playState().name().toLowerCase(Locale.ROOT))
                .prop("currentTime", x -> Double.isNaN(x.currentTime()) ? null : x.currentTime(),
                        (x, v) -> x.setCurrentTime(Js.num(v)))
                .prop("playbackRate", Animation::playbackRate, (x, v) -> x.setPlaybackRate(Js.num(v)));
    }

    Object animate(Element element, Args a) {
        List<Keyframe> keyframes = keyframes(a.get(0));
        AnimationOptions options = options(a.get(1));
        rt.document.flushStyle(); // keyframes are computed on top of the current base style
        StyleEngine styles = rt.document.styleEngine();
        List<ResolvedKeyframe> resolved = keyframes.stream().map(k -> {
            ResolvedKeyframe r = styles.computeDeclarations(element, k.declarations(), element.baseStyle);
            return new ResolvedKeyframe((float) k.offset(), k.easing(), r.style(), r.props());
        }).toList();
        return wrap(rt.document.animations().animate(element, resolved, options));
    }

    private HostObject wrap(Animation anim) {
        HostObject w = animation.wrap(anim);
        w.put("onfinish", w, null);
        w.put("oncancel", w, null);
        Callable[] settle = new Callable[2];
        Scriptable finished = Context.getCurrentContext().newObject(rt.global, "Promise", new Object[] {
                new LambdaFunction(rt.global, "executor", 2, (cx, scope, thisObj, args) -> {
                    settle[0] = (Callable) args[0];
                    settle[1] = (Callable) args[1];
                    return Undefined.instance;
                })});
        // A cancelled animation rejects `finished`; as in browsers, nobody listening to that is not an error.
        ScriptableObject.callMethod(finished, "catch", new Object[] {
                new LambdaFunction(rt.global, "ignore", 1, (cx, scope, thisObj, args) -> Undefined.instance)});
        w.defineProperty("finished", finished, ScriptableObject.READONLY | ScriptableObject.PERMANENT);
        anim.onFinish(() -> settle(w, "finish", settle[0]));
        anim.onCancel(() -> settle(w, "cancel", settle[1]));
        return w;
    }

    /** Settles {@code finished} and calls {@code onfinish} / {@code oncancel}. */
    private void settle(HostObject w, String type, Callable resolveOrReject) {
        rt.enter("Error in animation on" + type, cx -> {
            Object value = w;
            if (type.equals("cancel")) {
                Scriptable error = cx.newObject(rt.global, "Error", new Object[] {"The animation was cancelled"});
                error.put("name", error, "AbortError");
                value = error;
            }
            resolveOrReject.call(cx, rt.global, rt.global, new Object[] {value});
            if (Js.property(w, "on" + type) instanceof Callable handler) {
                handler.call(cx, rt.global, w, new Object[] {rt.js.toJs(new Event(type, false, false))});
            }
            return null;
        });
    }

    // ---- Keyframes and options (package-private for tests) ----

    /** Normalises either keyframe form: offsets distributed and checked, properties as CSS names. */
    static List<Keyframe> keyframes(Object spec) {
        List<Keyframe> frames;
        if (spec instanceof NativeArray array) frames = distribute(listed(array));
        else if (spec instanceof Scriptable object) frames = propertyIndexed(object);
        else if (Js.isNullish(spec)) frames = List.of();
        else throw Js.typeError("Keyframes must be an array or an object");
        double previous = 0;
        for (Keyframe k : frames) {
            if (!(k.offset() >= previous && k.offset() <= 1)) throw Js.typeError("Keyframe offsets must be in order, from 0 to 1");
            previous = k.offset();
        }
        return frames;
    }

    /** {@code [{opacity: 0}, {opacity: 1, offset: 0.8, easing: 'ease-in'}]}: offsets may be missing. */
    private static List<Keyframe> listed(NativeArray array) {
        List<Keyframe> frames = new ArrayList<>();
        for (Object item : Js.elements(array)) {
            if (!(item instanceof Scriptable frame)) throw Js.typeError("Keyframes must be objects");
            double offset = Double.NaN;
            TimingFunction easing = null;
            Map<String, String> properties = new LinkedHashMap<>();
            for (Object id : frame.getIds()) {
                String name = String.valueOf(id);
                Object value = Js.property(frame, name);
                switch (name) {
                    case "offset" -> offset = Js.isNullish(value) ? Double.NaN : Js.num(value);
                    case "easing" -> easing = easing(Js.str(value));
                    case "composite" -> { }
                    default -> properties.put(StyleBindings.cssName(name), Js.str(value));
                }
            }
            frames.add(new Keyframe(offset, easing, properties));
        }
        return frames;
    }

    /** {@code {opacity: [0, 1], transform: [...]}}: each property's values are spaced evenly on their own. */
    private static List<Keyframe> propertyIndexed(Scriptable spec) {
        List<Object> offsets = list(Js.property(spec, "offset"));
        List<Object> easings = list(Js.property(spec, "easing"));
        TreeMap<Double, Map<String, String>> byOffset = new TreeMap<>();
        for (Object id : spec.getIds()) {
            String name = String.valueOf(id);
            if (name.equals("offset") || name.equals("easing") || name.equals("composite")) continue;
            List<Object> values = list(Js.property(spec, name));
            for (int i = 0; i < values.size(); i++) {
                double offset = i < offsets.size() ? Js.num(offsets.get(i))
                        : values.size() == 1 ? 1 : (double) i / (values.size() - 1);
                byOffset.computeIfAbsent(offset, k -> new LinkedHashMap<>())
                        .put(StyleBindings.cssName(name), Js.str(values.get(i)));
            }
        }
        List<Keyframe> frames = new ArrayList<>();
        for (Map.Entry<Double, Map<String, String>> e : byOffset.entrySet()) {
            Object easing = easings.isEmpty() ? null : easings.get(frames.size() % easings.size());
            frames.add(new Keyframe(e.getKey(), easing == null ? null : easing(Js.str(easing)), e.getValue()));
        }
        return frames;
    }

    private static List<Object> list(Object value) {
        if (Js.isNullish(value)) return List.of();
        return value instanceof NativeArray array ? Js.elements(array) : List.of(value);
    }

    /** Web Animations offset distribution: a missing last offset is 1, a missing first is 0, gaps are even. */
    static List<Keyframe> distribute(List<Keyframe> frames) {
        int n = frames.size();
        List<Keyframe> out = new ArrayList<>(frames);
        if (n == 0) return out;
        if (Double.isNaN(out.get(n - 1).offset())) out.set(n - 1, out.get(n - 1).at(1));
        if (n > 1 && Double.isNaN(out.get(0).offset())) out.set(0, out.get(0).at(0));
        int start = 0;
        while (start < n - 1) {
            int end = start + 1;
            while (Double.isNaN(out.get(end).offset())) end++;
            double from = out.get(start).offset(), to = out.get(end).offset();
            for (int k = start + 1; k < end; k++) out.set(k, out.get(k).at(from + (to - from) * (k - start) / (end - start)));
            start = end;
        }
        return out;
    }

    /** A duration in ms, or {@code {duration, delay, easing, iterations, direction, fill}}. */
    static AnimationOptions options(Object spec) {
        if (Js.isNullish(spec)) return AnimationOptions.of(0);
        if (!(spec instanceof Scriptable o)) return AnimationOptions.of((float) number(spec, 0));
        Object easing = Js.property(o, "easing");
        return new AnimationOptions(
                (float) number(Js.property(o, "duration"), 0),
                (float) number(Js.property(o, "delay"), 0),
                Js.isNullish(easing) ? TimingFunction.LINEAR : easing(Js.str(easing)),
                (float) number(Js.property(o, "iterations"), 1),
                keyword(o, "direction", AnimationSpec.Direction.NORMAL),
                keyword(o, "fill", AnimationSpec.FillMode.NONE));
    }

    private static double number(Object value, double fallback) {
        double d = Js.isNullish(value) ? Double.NaN : Js.num(value);
        return Double.isNaN(d) ? fallback : d;
    }

    /** An enum option written in CSS style ("alternate-reverse"); "auto" means the default. */
    private static <E extends Enum<E>> E keyword(Scriptable options, String name, E fallback) {
        Object value = Js.property(options, name);
        if (Js.isNullish(value) || Js.str(value).equals("auto")) return fallback;
        try {
            return Enum.valueOf(fallback.getDeclaringClass(), Js.str(value).toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw Js.typeError("Invalid " + name + ": " + Js.str(value));
        }
    }

    /** A CSS easing: a keyword, {@code cubic-bezier(a, b, c, d)} or {@code steps(n[, position])}. */
    static TimingFunction easing(String text) {
        String s = text.trim().toLowerCase(Locale.ROOT);
        TimingFunction keyword = switch (s) {
            case "linear" -> TimingFunction.LINEAR;
            case "ease" -> TimingFunction.EASE;
            case "ease-in" -> TimingFunction.EASE_IN;
            case "ease-out" -> TimingFunction.EASE_OUT;
            case "ease-in-out" -> TimingFunction.EASE_IN_OUT;
            case "step-start" -> new TimingFunction.Steps(1, TimingFunction.Steps.Jump.START);
            case "step-end" -> new TimingFunction.Steps(1, TimingFunction.Steps.Jump.END);
            default -> null;
        };
        if (keyword != null) return keyword;
        Matcher m = EASING_FUNCTION.matcher(s);
        if (m.matches()) {
            String[] args = m.group(2).split(",");
            try {
                if (m.group(1).equals("cubic-bezier") && args.length == 4) {
                    return new TimingFunction.CubicBezier(Float.parseFloat(args[0].trim()), Float.parseFloat(args[1].trim()),
                            Float.parseFloat(args[2].trim()), Float.parseFloat(args[3].trim()));
                }
                if (m.group(1).equals("steps") && args.length <= 2) {
                    TimingFunction.Steps.Jump jump = args.length == 1 ? TimingFunction.Steps.Jump.END : switch (args[1].trim()) {
                        case "start", "jump-start" -> TimingFunction.Steps.Jump.START;
                        case "end", "jump-end" -> TimingFunction.Steps.Jump.END;
                        case "jump-none" -> TimingFunction.Steps.Jump.NONE;
                        case "jump-both" -> TimingFunction.Steps.Jump.BOTH;
                        default -> null;
                    };
                    int count = Integer.parseInt(args[0].trim());
                    if (jump != null && count > 0) return new TimingFunction.Steps(count, jump);
                }
            } catch (NumberFormatException ignored) {
                // falls through to the error below
            }
        }
        throw Js.typeError("Invalid easing: " + text);
    }
}
