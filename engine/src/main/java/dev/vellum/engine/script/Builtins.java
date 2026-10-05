package dev.vellum.engine.script;

import dev.vellum.engine.Limits;
import dev.vellum.shadow.rhino.BaseFunction;
import dev.vellum.shadow.rhino.Callable;
import dev.vellum.shadow.rhino.Context;
import dev.vellum.shadow.rhino.Function;
import dev.vellum.shadow.rhino.LambdaFunction;
import dev.vellum.shadow.rhino.NativeArray;
import dev.vellum.shadow.rhino.ScriptRuntime;
import dev.vellum.shadow.rhino.Scriptable;
import dev.vellum.shadow.rhino.ScriptableObject;
import dev.vellum.shadow.rhino.Symbol;
import dev.vellum.shadow.rhino.SymbolKey;
import dev.vellum.shadow.rhino.Undefined;
import dev.vellum.shadow.rhino.VellumBigInts;
import dev.vellum.shadow.rhino.typedarrays.NativeArrayBuffer;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Caps on the built-ins that do their work in Java. The instruction budget only counts interpreted code (and regular
 * expressions), so a built-in that loops to a length the script chose, or allocates a size it chose, would run
 * unchecked: {@code Array.prototype.indexOf.call({length: 2 ** 53}, 0)} spins for ever, and
 * {@code new Array(2 ** 32 - 1).fill(0)} or {@code 'x'.repeat(2 ** 30)} exhaust the heap. Each such built-in is
 * replaced by a wrapper that checks the size first, against {@link Limits#maxArrayLength} or
 * {@link Limits#maxBufferBytes} or {@link Limits#maxStringLength}, and throws a RangeError when it is over. Everything else about the built-in is
 * unchanged.
 *
 * <ul>
 *   <li>Every {@code Array.prototype} method (and {@code Array.from}) checks the length of {@code this} and of its
 *       array arguments, so no array or array-like longer than the cap is ever iterated, joined, copied or
 *       sorted.</li>
 *   <li>{@code ArrayBuffer}, the typed array constructors and their {@code from} check the bytes they would
 *       allocate.</li>
 *   <li>{@code Function.prototype.apply}, {@code Reflect.apply} and {@code Reflect.construct} check the length of
 *       the argument list.</li>
 *   <li>{@code String.prototype.repeat}, {@code padStart}, {@code padEnd}, {@code replace}, {@code replaceAll} and
 *       {@code Array.prototype.join} check the length of the result, and {@code split}, {@code match},
 *       {@code matchAll}, string iteration, {@code String.raw} and the RegExp symbol methods the number of pieces they
 *       would make.</li>
 *   <li>{@code JSON.stringify} checks every array it meets (through a replacer, so arrays reached by getters and
 *       {@code toJSON} are checked too), and {@code JSON.parse} refuses text nested deeper than
 *       {@link Limits#maxDepth} instead of overflowing the stack.</li>
 * </ul>
 *
 * BigInt arithmetic is capped at {@link Limits#maxBigIntBits} by {@link VellumBigInts}, which the build patches into
 * Rhino (rhino/build.gradle). Rhino extras that pages have no use for are removed ({@link #EXTRAS}).
 */
final class Builtins {
    @FunctionalInterface
    private interface Check {
        void check(Scriptable thisObj, Object[] args);
    }

    /** Non-standard globals of Rhino's: continuations, scripts as objects, internal scope types, E4X leftovers. */
    private static final String[] EXTRAS = {"Continuation", "Script", "With", "Call", "JavaException", "isXMLName"};
    private static final String[] TYPED_ARRAYS = {"Int8Array", "Uint8Array", "Uint8ClampedArray", "Int16Array",
            "Uint16Array", "Int32Array", "Uint32Array", "Float32Array", "Float64Array", "BigInt64Array",
            "BigUint64Array"};

    private final Scriptable scope;
    private final Limits limits;
    /** Wrappers by original, so a built-in reachable under two names ({@code values}, {@code @@iterator}) maps to one. */
    private final Map<Object, Object> wrapped = new IdentityHashMap<>();

    private Builtins(Scriptable scope, Limits limits) {
        this.scope = scope;
        this.limits = limits;
    }

    /** Installs the caps in a fresh global scope. */
    static void install(ScriptableObject global, Limits limits) {
        new Builtins(global, limits).install(global);
    }

    private void install(ScriptableObject global) {
        for (String name : EXTRAS) global.delete(name);
        VellumBigInts.setMaxBits(limits.maxBigIntBits());

        Scriptable array = (Scriptable) ScriptableObject.getProperty(global, "Array");
        Scriptable arrayPrototype = (Scriptable) ScriptableObject.getProperty(array, "prototype");
        Check arrays = (thisObj, args) -> {
            checkLength(thisObj, true);
            for (Object a : args) if (a instanceof NativeArray) checkLength((Scriptable) a, false);
        };
        wrapAll(arrayPrototype, arrays);
        wrap(array, "from", (thisObj, args) -> {
            if (args.length > 0 && args[0] instanceof Scriptable source) checkLength(source, true);
        });

        Scriptable function = (Scriptable) ScriptableObject.getProperty(global, "Function");
        Scriptable functionPrototype = (Scriptable) ScriptableObject.getProperty(function, "prototype");
        wrap(functionPrototype, "apply", (thisObj, args) -> argumentList(args, 1));
        if (ScriptableObject.getProperty(global, "Reflect") instanceof Scriptable reflect) {
            wrap(reflect, "apply", (thisObj, args) -> argumentList(args, 2));
            wrap(reflect, "construct", (thisObj, args) -> argumentList(args, 1));
        }

        Scriptable string = (Scriptable) ScriptableObject.getProperty(global, "String");
        Scriptable stringPrototype = (Scriptable) ScriptableObject.getProperty(string, "prototype");
        wrap(string, "raw", (thisObj, args) -> {
            if (arg(args, 0) instanceof Scriptable strings && ScriptableObject.getProperty(strings, "raw") instanceof Scriptable raw) {
                checkLength(raw, true);
            }
        });
        wrap(stringPrototype, "repeat", (thisObj, args) -> {
            double count = args.length > 0 ? ScriptRuntime.toInteger(args[0]) : 0;
            checkString(ScriptRuntime.toString(thisObj).length() * count);
        });
        Check pad = (thisObj, args) -> checkString(args.length > 0 ? ScriptRuntime.toInteger(args[0]) : 0);
        wrap(stringPrototype, "padStart", pad);
        wrap(stringPrototype, "padEnd", pad);
        // Built-ins that make an object per character or per match, or a result that multiplies the input.
        wrap(stringPrototype, "split", (thisObj, args) -> split(ScriptRuntime.toString(thisObj), arg(args, 0), arg(args, 1)));
        Check perCharacter = (thisObj, args) -> checkCount(ScriptRuntime.toString(thisObj).length());
        wrap(stringPrototype, "match", perCharacter);
        wrap(stringPrototype, "matchAll", perCharacter);
        wrap(stringPrototype, SymbolKey.ITERATOR, perCharacter);
        wrap(stringPrototype, "replace", (thisObj, args) ->
                replace(ScriptRuntime.toString(thisObj), arg(args, 0), arg(args, 1), false));
        wrap(stringPrototype, "replaceAll", (thisObj, args) ->
                replace(ScriptRuntime.toString(thisObj), arg(args, 0), arg(args, 1), true));
        Scriptable regExp = (Scriptable) ScriptableObject.getProperty(global, "RegExp");
        Scriptable regExpPrototype = (Scriptable) ScriptableObject.getProperty(regExp, "prototype");
        Check argument = (thisObj, args) -> checkCount(args.length > 0 ? ScriptRuntime.toString(args[0]).length() : 0);
        wrap(regExpPrototype, SymbolKey.MATCH, argument);
        wrap(regExpPrototype, SymbolKey.MATCH_ALL, argument);
        wrap(regExpPrototype, SymbolKey.SPLIT, (thisObj, args) ->
                split(ScriptRuntime.toString(arg(args, 0)), thisObj, arg(args, 1)));
        wrap(regExpPrototype, SymbolKey.REPLACE, (thisObj, args) ->
                replace(ScriptRuntime.toString(arg(args, 0)), thisObj, arg(args, 1), false));
        Check joined = (thisObj, args) -> join(thisObj, args.length > 0 && !Undefined.isUndefined(args[0])
                ? ScriptRuntime.toString(args[0]).length() : 1);
        wrap(arrayPrototype, "join", joined);
        wrap(arrayPrototype, "toString", (thisObj, args) -> join(thisObj, 1));
        wrap(arrayPrototype, "toLocaleString", (thisObj, args) -> join(thisObj, 1));

        wrapConstructor(global, "ArrayBuffer", (thisObj, args) -> checkBytes(args.length > 0 ? ScriptRuntime.toInteger(args[0]) : 0));
        for (String name : TYPED_ARRAYS) {
            if (!(ScriptableObject.getProperty(global, name) instanceof Scriptable ctor)) continue;
            Object bytes = ScriptableObject.getProperty(ctor, "BYTES_PER_ELEMENT");
            int size = bytes instanceof Number n ? n.intValue() : 8;
            Scriptable guarded = wrapConstructor(global, name, (thisObj, args) -> typedArray(args, size));
            if (guarded != null) wrap(guarded, "from", (thisObj, args) -> {
                if (args.length > 0 && args[0] instanceof Scriptable source) checkBytes(length(source, true) * (double) size);
            });
        }

        Scriptable json = (Scriptable) ScriptableObject.getProperty(global, "JSON");
        hardenJson(json);
    }

    // ---- Checks ----

    /** The length of an array, or of an array-like when {@code arrayLike} (read as the built-in would). */
    private static long length(Scriptable object, boolean arrayLike) {
        if (object instanceof NativeArray a) return a.getLength();
        if (!arrayLike) return 0;
        Object length = ScriptableObject.getProperty(object, "length");
        return length == Scriptable.NOT_FOUND ? 0 : ScriptRuntime.toLength(length);
    }

    private void checkLength(Scriptable object, boolean arrayLike) {
        if (object == null) return;
        long length = length(object, arrayLike);
        if (length > limits.maxArrayLength()) {
            throw ScriptRuntime.rangeError("Array of length " + length + " is longer than the " + limits.maxArrayLength()
                    + " built-ins work on");
        }
    }

    private void argumentList(Object[] args, int index) {
        if (index < args.length && args[index] instanceof Scriptable list) checkLength(list, true);
    }

    private void checkString(double length) {
        if (length > limits.maxStringLength()) {
            throw ScriptRuntime.rangeError("String longer than " + limits.maxStringLength() + " characters");
        }
    }

    /** A built-in about to make {@code count} objects (array elements, matches, characters). */
    private void checkCount(double count) {
        if (count > limits.maxArrayLength()) {
            throw ScriptRuntime.rangeError("Result of " + (long) count + " items is more than the " + limits.maxArrayLength()
                    + " built-ins make");
        }
    }

    /**
     * {@code split(separator, limit)}: its pieces are at most the limit, the separator's occurrences plus one for a
     * string separator, or the characters plus one for a pattern.
     */
    private void split(String text, Object separator, Object limit) {
        if (!Undefined.isUndefined(limit) && ScriptRuntime.toUint32(limit) <= limits.maxArrayLength()) return;
        if (Undefined.isUndefined(separator)) return;
        if (separator instanceof CharSequence || separator instanceof Scriptable s && s.getClassName().equals("String")) {
            String sep = ScriptRuntime.toString(separator);
            if (sep.isEmpty()) {
                checkCount(text.length());
                return;
            }
            long pieces = 1;
            for (int i = text.indexOf(sep); i >= 0 && pieces <= limits.maxArrayLength(); i = text.indexOf(sep, i + sep.length())) pieces++;
            checkCount(pieces);
        } else {
            checkCount(text.length() + 1);
        }
    }

    private static Object arg(Object[] args, int i) {
        return i < args.length ? args[i] : Undefined.instance;
    }

    /**
     * {@code replace(pattern, replacement)} with a replacement string: one match, or for {@code replaceAll} and global
     * patterns at most length + 1, each replaced by at most the replacement's length, where {@code $`} and {@code $'}
     * insert up to the whole text. (A function replacement is script code, already on the budget.)
     */
    private void replace(String text, Object pattern, Object replacement, boolean all) {
        if (replacement instanceof Callable) return;
        if (pattern instanceof Scriptable p && p.getClassName().equals("RegExp")) {
            all = ScriptRuntime.toBoolean(ScriptableObject.getProperty(p, "global"));
        }
        String r = ScriptRuntime.toString(replacement);
        long matches = all ? text.length() + 1L : 1;
        long each = r.length() + (r.contains("$`") || r.contains("$'") ? text.length() : 0);
        checkString((double) matches * each + text.length());
    }

    /** {@code join} and {@code toString} of an array of strings: the result's length, separators included. */
    private void join(Scriptable array, int separator) {
        if (!(array instanceof NativeArray a)) return;
        checkLength(a, false);
        double length = (double) separator * Math.max(0, a.getLength() - 1);
        for (Object item : Context.getCurrentContext().getElements(a)) {
            if (item instanceof CharSequence s) length += s.length();
        }
        checkString(length);
    }

    private void checkBytes(double bytes) {
        if (bytes > limits.maxBufferBytes()) {
            throw ScriptRuntime.rangeError("Buffer of more than " + limits.maxBufferBytes() + " bytes");
        }
    }

    /** {@code new XArray(length | array-like | buffer[, offset, length])}: only the first two allocate. */
    private void typedArray(Object[] args, int size) {
        if (args.length == 0) return;
        Object first = args[0];
        if (first instanceof NativeArrayBuffer) return; // a view on an existing buffer
        if (first instanceof Scriptable source) checkBytes(length(source, true) * (double) size);
        else if (!Undefined.isUndefined(first)) checkBytes(ScriptRuntime.toInteger(first) * size);
    }

    // ---- Wrapping ----

    /** Wraps every function-valued own property of {@code holder}, symbol-keyed ones included. */
    private void wrapAll(Scriptable holder, Check check) {
        if (!(holder instanceof ScriptableObject object)) return;
        for (Object id : object.getAllIds()) {
            if (id instanceof String name && !name.equals("constructor")) wrap(holder, name, check);
        }
        wrap(holder, SymbolKey.ITERATOR, check);
    }

    private void wrap(Scriptable holder, String name, Check check) {
        if (!(holder instanceof ScriptableObject object)) return;
        if (!(ScriptableObject.getProperty(holder, name) instanceof Function original)) return;
        int attributes = object.getAttributes(name);
        object.defineProperty(name, wrapper(original, name, check), attributes);
    }

    private void wrap(Scriptable holder, Symbol symbol, Check check) {
        if (!(holder instanceof ScriptableObject object)) return;
        if (!(ScriptableObject.getProperty(holder, symbol) instanceof Function original)) return;
        Object w = wrapper(original, original instanceof BaseFunction b ? b.getFunctionName() : "", check);
        // Defined as Object.defineProperty does: a plain put of a symbol-keyed built-in of RegExp.prototype (an
        // IdScriptableObject) stores null in Rhino 1.9.
        Context cx = Context.getCurrentContext();
        ScriptableObject descriptor = (ScriptableObject) cx.newObject(scope);
        descriptor.put("value", descriptor, w);
        descriptor.put("writable", descriptor, true);
        descriptor.put("enumerable", descriptor, false);
        descriptor.put("configurable", descriptor, true);
        object.defineOwnProperty(cx, symbol, descriptor);
    }

    private Object wrapper(Function original, String name, Check check) {
        return wrapped.computeIfAbsent(original, o -> new LambdaFunction(scope, name, arity(original),
                (cx, s, thisObj, args) -> {
                    check.check(thisObj, args);
                    return original.call(cx, s, thisObj, args);
                }));
    }

    private static int arity(Function f) {
        return f instanceof BaseFunction b ? b.getLength() : 0;
    }

    /**
     * Replaces the global constructor {@code name} by a {@link Guarded} one that checks its arguments, both when
     * called and with {@code new}. Its {@code prototype} is the original's, which points back at it, so
     * {@code instanceof} and {@code constructor} work as before.
     */
    private Scriptable wrapConstructor(ScriptableObject global, String name, Check check) {
        if (!(ScriptableObject.getProperty(global, name) instanceof BaseFunction original)) return null;
        Guarded guarded = new Guarded(scope, original, check);
        for (Object id : original.getAllIds()) {
            if (!(id instanceof String key) || key.equals("prototype") || key.equals("name") || key.equals("length")) continue;
            guarded.defineProperty(key, ScriptableObject.getProperty(original, key), original.getAttributes(key));
        }
        Object prototype = ScriptableObject.getProperty(original, "prototype");
        guarded.setImmunePrototypeProperty(prototype);
        if (prototype instanceof ScriptableObject p) p.defineProperty("constructor", guarded, ScriptableObject.DONTENUM);
        global.defineProperty(name, guarded, global.getAttributes(name));
        return guarded;
    }

    /** A built-in constructor behind a size check. */
    private static final class Guarded extends BaseFunction {
        private final BaseFunction original;
        private final Check check;

        Guarded(Scriptable scope, BaseFunction original, Check check) {
            super(scope, ScriptableObject.getFunctionPrototype(scope));
            this.original = original;
            this.check = check;
        }

        @Override
        public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
            check.check(thisObj, args);
            return original.call(cx, scope, thisObj, args);
        }

        @Override
        public Scriptable construct(Context cx, Scriptable scope, Object[] args) {
            check.check(null, args);
            return original.construct(cx, scope, args);
        }

        @Override
        public String getFunctionName() {
            return original.getFunctionName();
        }

        @Override
        public int getLength() {
            return original.getLength();
        }

        @Override
        public int getArity() {
            return original.getArity();
        }
    }

    // ---- JSON ----

    private void hardenJson(Scriptable json) {
        if (!(json instanceof ScriptableObject object)) return;
        if (ScriptableObject.getProperty(json, "stringify") instanceof Function stringify) {
            object.defineProperty("stringify", new LambdaFunction(scope, "stringify", 3, (cx, s, thisObj, args) -> {
                Object[] call = {args.length > 0 ? args[0] : Undefined.instance, replacer(args.length > 1 ? args[1] : null),
                        args.length > 2 ? args[2] : Undefined.instance};
                return stringify.call(cx, s, thisObj, call);
            }), object.getAttributes("stringify"));
        }
        if (ScriptableObject.getProperty(json, "parse") instanceof Function parse) {
            object.defineProperty("parse", new LambdaFunction(scope, "parse", 2, (cx, s, thisObj, args) -> {
                checkNesting(ScriptRuntime.toString(args.length > 0 ? args[0] : Undefined.instance));
                return parse.call(cx, s, thisObj, args);
            }), object.getAttributes("parse"));
        }
    }

    /**
     * A replacer that checks each array before {@code JSON.stringify} walks it, after calling the page's own replacer.
     * A list of property names (the other form of replacer) is applied here too, by giving the walk a copy of each
     * object with just those properties, in the list's order.
     */
    private Object replacer(Object own) {
        Callable fn = own instanceof Callable c ? c : null;
        List<String> names = own instanceof NativeArray list && fn == null ? propertyNames(list) : null;
        return new LambdaFunction(scope, "", 2, (cx, s, thisObj, args) -> {
            Object value = args.length > 1 ? args[1] : Undefined.instance;
            if (fn != null) value = fn.call(cx, s, thisObj, args);
            if (value instanceof NativeArray a) checkLength(a, false);
            else if (names != null && value instanceof Scriptable object && !(value instanceof Callable)
                    && isPlainObject(object)) {
                Scriptable copy = cx.newObject(scope);
                for (String name : names) {
                    if (ScriptableObject.hasProperty(object, name)) copy.put(name, copy, ScriptableObject.getProperty(object, name));
                }
                value = copy;
            }
            return value;
        });
    }

    private static boolean isPlainObject(Scriptable object) {
        String type = object.getClassName();
        return !type.equals("String") && !type.equals("Number") && !type.equals("Boolean") && !type.equals("BigInt");
    }

    /** The property list of an array replacer: its strings and numbers as strings, without repeats. */
    private List<String> propertyNames(NativeArray list) {
        checkLength(list, false);
        Set<String> names = new LinkedHashSet<>();
        for (Object item : Context.getCurrentContext().getElements(list)) {
            if (item instanceof CharSequence || item instanceof Number) names.add(ScriptRuntime.toString(item));
            else if (item instanceof Scriptable o && (o.getClassName().equals("String") || o.getClassName().equals("Number"))) {
                names.add(ScriptRuntime.toString(item));
            }
        }
        return new ArrayList<>(names);
    }

    /** Refuses JSON text whose arrays and objects nest deeper than {@link Limits#maxDepth}. */
    private void checkNesting(String text) {
        int depth = 0;
        boolean string = false;
        for (int i = 0, n = text.length(); i < n; i++) {
            char c = text.charAt(i);
            if (string) {
                if (c == '\\') i++;
                else if (c == '"') string = false;
            } else if (c == '"') {
                string = true;
            } else if (c == '[' || c == '{') {
                if (++depth > limits.maxDepth()) {
                    throw ScriptRuntime.constructError("SyntaxError", "JSON nested deeper than " + limits.maxDepth());
                }
            } else if (c == ']' || c == '}') {
                depth--;
            }
        }
    }
}
