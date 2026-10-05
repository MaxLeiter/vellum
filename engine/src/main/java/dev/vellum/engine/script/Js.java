package dev.vellum.engine.script;

import dev.vellum.engine.dom.Node;
import dev.vellum.engine.event.Event;
import dev.vellum.shadow.rhino.Callable;
import dev.vellum.shadow.rhino.Context;
import dev.vellum.shadow.rhino.EcmaError;
import dev.vellum.shadow.rhino.RhinoException;
import dev.vellum.shadow.rhino.ScriptRuntime;
import dev.vellum.shadow.rhino.Scriptable;
import dev.vellum.shadow.rhino.ScriptableObject;
import dev.vellum.shadow.rhino.Undefined;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The one conversion layer between Java and script values.
 *
 * <p>Java to JS ({@link #toJs}): strings, booleans and numbers pass through (floats via their shortest decimal form,
 * so {@code 12.3f} reads as 12.3, not 12.300000190734863); nodes and events become their wrappers; collections and
 * arrays become new JS arrays (snapshots, which is what the DOM API returns); maps become plain objects. Script
 * values pass through unchanged. Anything else is a bug, because scripts never see raw Java objects.
 *
 * <p>JS to Java: the static helpers follow the language's own conversions ({@code ToString}, {@code ToNumber}...).
 */
final class Js {
    private final RhinoScriptRuntime rt;

    Js(RhinoScriptRuntime rt) {
        this.rt = rt;
    }

    // ---- Java to JS ----

    Object toJs(Object value) {
        return switch (value) {
            case null -> null;
            case Float f -> Double.parseDouble(f.toString());
            case Number n -> n;
            case Boolean b -> b;
            case CharSequence s -> s;
            case Scriptable s -> s;
            case Undefined u -> u;
            case Node n -> rt.dom.wrap(n);
            case Event e -> rt.events.wrap(e);
            case Collection<?> c -> array(c);
            case Object[] a -> array(Arrays.asList(a));
            case Map<?, ?> m -> object(m);
            default -> throw new IllegalArgumentException("No script value for " + value.getClass().getName());
        };
    }

    Object[] toJsArgs(Object... values) {
        return Arrays.stream(values).map(this::toJs).toArray();
    }

    Scriptable array(Collection<?> items) {
        return Context.getCurrentContext().newArray(rt.global, items.stream().map(this::toJs).toArray());
    }

    Scriptable object(Map<?, ?> properties) {
        Scriptable object = newObject();
        properties.forEach((k, v) -> object.put(String.valueOf(k), object, toJs(v)));
        return object;
    }

    Scriptable newObject() {
        return Context.getCurrentContext().newObject(rt.global);
    }

    /** {@code JSON.parse(json)}; a SyntaxError for malformed input. */
    Object parseJson(String json) {
        return ScriptableObject.callMethod(json(), "parse", new Object[] {json});
    }

    /** {@code JSON.stringify(value)}, or null when the value has no JSON form (undefined, a function). */
    String stringify(Object value) {
        Object text = ScriptableObject.callMethod(json(), "stringify", new Object[] {value});
        return text instanceof CharSequence s ? s.toString() : null;
    }

    /** How a value reads as text (console output, template text): strings raw, objects as JSON, nodes as tags. */
    String display(Object value) {
        if (value instanceof CharSequence s) return s.toString();
        if (value instanceof HostObject h) return h.target instanceof Node n ? n.toString() : "[object " + h.getClassName() + "]";
        if (value instanceof Scriptable s && !(value instanceof Callable) && !"Error".equals(s.getClassName())) {
            try {
                String json = stringify(s);
                if (json != null) return json;
            } catch (RhinoException e) {
                // A cyclic structure: fall back to String(value).
            }
        }
        return str(value);
    }

    private Scriptable json() {
        return (Scriptable) ScriptableObject.getProperty(rt.global, "JSON");
    }

    /**
     * Calls a script function from host code that is already running on behalf of a script (forEach callbacks): no
     * new entry, errors propagate to the calling script.
     */
    Object invoke(Callable fn, Scriptable thisObj, Object... args) {
        return fn.call(Context.getCurrentContext(), rt.global, thisObj, toJsArgs(args));
    }

    /** The elements of a JS array (or array-like object). */
    static List<Object> elements(Scriptable array) {
        return Arrays.asList(Context.getCurrentContext().getElements(array));
    }

    // ---- JS to Java ----

    static boolean isNullish(Object value) {
        return value == null || Undefined.isUndefined(value);
    }

    static String str(Object value) {
        return Context.toString(value);
    }

    static double num(Object value) {
        return Context.toNumber(value);
    }

    static boolean bool(Object value) {
        return Context.toBoolean(value);
    }

    /** The Java object behind a host wrapper, or a TypeError when {@code value} is not one of type {@code type}. */
    static <T> T unwrap(Object value, Class<T> type) {
        if (value instanceof HostObject h && type.isInstance(h.target)) return type.cast(h.target);
        throw typeError(describe(value) + " is not of type '" + type.getSimpleName() + "'");
    }

    static Callable function(Object value) {
        if (value instanceof Callable c) return c;
        throw typeError(describe(value) + " is not a function");
    }

    /** {@code object[name]}, with absent properties read as undefined. */
    static Object property(Scriptable object, String name) {
        Object v = ScriptableObject.getProperty(object, name);
        return v == Scriptable.NOT_FOUND ? Undefined.instance : v;
    }

    private static String describe(Object value) {
        return isNullish(value) ? str(value) : value instanceof Scriptable s ? "[object " + s.getClassName() + "]"
                : str(value);
    }

    // ---- Errors thrown to scripts ----

    /** A catchable script error of a built-in type ("Error", "TypeError", "SyntaxError", "RangeError"). */
    static EcmaError error(String type, String message) {
        return ScriptRuntime.constructError(type, message);
    }

    static EcmaError typeError(String message) {
        return error("TypeError", message);
    }
}
