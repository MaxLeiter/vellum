package dev.vellum.engine.script;

import dev.vellum.shadow.rhino.Context;
import dev.vellum.shadow.rhino.LambdaConstructor;
import dev.vellum.shadow.rhino.Scriptable;
import dev.vellum.shadow.rhino.ScriptableObject;

import java.util.function.Function;

/**
 * A host class: a prototype filled from a {@link Members} table written against the Java type {@code T}, and a
 * constructor function so {@code instanceof} works. Classes with a factory can be created with {@code new}; the
 * others throw "Illegal constructor", as in browsers.
 */
final class HostClass<T> {
    final String name;
    final ScriptableObject prototype;
    private final LambdaConstructor constructor;
    private final RhinoScriptRuntime rt;
    private final Class<T> type;

    HostClass(RhinoScriptRuntime rt, String name, Class<T> type, HostClass<?> parent, Function<Args, T> factory) {
        this.rt = rt;
        this.name = name;
        this.type = type;
        prototype = (ScriptableObject) Context.getCurrentContext().newObject(rt.global);
        constructor = new LambdaConstructor(rt.global, name, 0, LambdaConstructor.CONSTRUCTOR_NEW, (cx, scope, args) -> {
            if (factory == null) throw Js.typeError("Illegal constructor");
            return (Scriptable) rt.js.toJs(factory.apply(new Args(args)));
        });
        // Links prototype.constructor, and resets the prototype's own prototype to Object.prototype: inherit after.
        constructor.setPrototypeScriptable(prototype);
        if (parent != null) prototype.setPrototype(parent.prototype);
    }

    /** The member table of this class's prototype; {@code this} must wrap a {@code T}. */
    Members<T> members() {
        return new Members<>(rt, prototype, thisObj -> Js.unwrap(thisObj, type));
    }

    /** Makes the constructor a global under each of {@code names}. */
    HostClass<T> expose(String... names) {
        for (String n : names) rt.global.defineProperty(n, constructor, ScriptableObject.DONTENUM);
        return this;
    }

    HostObject wrap(T target) {
        return wrap(target, null);
    }

    HostObject wrap(T target, HostObject.Named named) {
        return new HostObject(rt.global, prototype, name, target, named);
    }
}
