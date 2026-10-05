package dev.vellum.engine.script;

import dev.vellum.shadow.rhino.Context;
import dev.vellum.shadow.rhino.LambdaFunction;
import dev.vellum.shadow.rhino.RhinoException;
import dev.vellum.shadow.rhino.Scriptable;
import dev.vellum.shadow.rhino.ScriptableObject;
import dev.vellum.shadow.rhino.Undefined;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Defines getters, setters and methods on a script object from lambdas written against a Java target, so bindings
 * read as a table: {@code .prop("id", Element::id, (e, v) -> e.setAttribute("id", Js.str(v)))}. The target is
 * resolved from {@code this} (a wrapper's Java object for prototypes, a fixed object for singletons such as
 * {@code console}). This is the single place where results are converted to script values and Java failures become
 * script errors that {@code try/catch} can handle.
 */
final class Members<T> {
    @FunctionalInterface interface Getter<T> { Object get(T self); }
    @FunctionalInterface interface Setter<T> { void set(T self, Object value); }
    @FunctionalInterface interface Method<T> { Object call(T self, Args args); }
    @FunctionalInterface interface Action<T> { void run(T self, Args args); }

    private static final int ATTRIBUTES = ScriptableObject.DONTENUM;

    private final RhinoScriptRuntime rt;
    private final ScriptableObject holder;
    private final Function<Scriptable, T> self;

    Members(RhinoScriptRuntime rt, ScriptableObject holder, Function<Scriptable, T> self) {
        this.rt = rt;
        this.holder = holder;
        this.self = self;
    }

    /** A read-only accessor. */
    Members<T> get(String name, Getter<T> getter) {
        return accessor(name, getter, null);
    }

    /** A read-write accessor; the setter receives the raw script value. */
    Members<T> prop(String name, Getter<T> getter, Setter<T> setter) {
        return accessor(name, getter, setter);
    }

    private Members<T> accessor(String name, Getter<T> getter, Setter<T> setter) {
        holder.defineProperty(Context.getCurrentContext(), name,
                thisObj -> guard(name, () -> rt.js.toJs(getter.get(self.apply(thisObj)))),
                setter == null ? null : (thisObj, value) -> guard(name, () -> {
                    setter.set(self.apply(thisObj), value);
                    return null;
                }), ATTRIBUTES);
        return this;
    }

    /** A method returning a value. */
    Members<T> method(String name, Method<T> method) {
        holder.defineProperty(name, new LambdaFunction(rt.global, name, 0, (cx, scope, thisObj, args) ->
                guard(name, () -> rt.js.toJs(method.call(self.apply(thisObj), new Args(args))))), ATTRIBUTES);
        return this;
    }

    /** A method returning undefined. */
    Members<T> action(String name, Action<T> action) {
        return method(name, (target, args) -> {
            action.run(target, args);
            return Undefined.instance;
        });
    }

    /**
     * Runs a binding so that Java failures reach scripts as errors {@code try/catch} can handle. The exceptions
     * bindings throw on purpose (bad arguments, limits, missing features) carry messages written for scripts. Any
     * other is a bug in Java code, and its message may name Java classes, so the script only learns that the call
     * failed and the host log gets the details.
     */
    private Object guard(String name, Supplier<Object> body) {
        try {
            return body.get();
        } catch (RhinoException e) {
            throw e;
        } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException e) {
            throw Js.error("Error", e.getMessage() != null ? e.getMessage() : name + " failed");
        } catch (RuntimeException e) {
            rt.document.host().reportError("Internal error in " + name, e);
            throw Js.error("Error", "Internal error in " + name);
        }
    }
}
