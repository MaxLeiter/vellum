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
                thisObj -> guard(() -> rt.js.toJs(getter.get(self.apply(thisObj)))),
                setter == null ? null : (thisObj, value) -> guard(() -> {
                    setter.set(self.apply(thisObj), value);
                    return null;
                }), ATTRIBUTES);
        return this;
    }

    /** A method returning a value. */
    Members<T> method(String name, Method<T> method) {
        holder.defineProperty(name, new LambdaFunction(rt.global, name, 0, (cx, scope, thisObj, args) ->
                guard(() -> rt.js.toJs(method.call(self.apply(thisObj), new Args(args))))), ATTRIBUTES);
        return this;
    }

    /** A method returning undefined. */
    Members<T> action(String name, Action<T> action) {
        return method(name, (target, args) -> {
            action.run(target, args);
            return Undefined.instance;
        });
    }

    private static Object guard(Supplier<Object> body) {
        try {
            return body.get();
        } catch (RhinoException e) {
            throw e;
        } catch (RuntimeException e) {
            // Java failures (bad arguments, missing features) must reach scripts as catchable errors.
            throw Js.error("Error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }
}
