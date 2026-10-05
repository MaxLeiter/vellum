package dev.vellum.engine.script;

import dev.vellum.shadow.rhino.Scriptable;
import dev.vellum.shadow.rhino.ScriptableObject;

import java.util.Arrays;
import java.util.stream.Stream;

/**
 * A script object backed by a Java object, its {@link #target}. Its behaviour comes from its prototype (see
 * {@link HostClass}). Objects whose property names are open-ended ({@code dataset}, {@code element.style}) also get
 * {@link Named} properties, consulted after own and inherited properties.
 */
final class HostObject extends ScriptableObject {
    /** Open-ended property names resolved by the host: dataset keys, CSS properties. */
    interface Named {
        boolean has(String name);
        Object get(String name);
        void put(String name, Object value);
        default void delete(String name) {}
        default Object[] ids() { return new Object[0]; }
    }

    final Object target;
    private final String className;
    private final Named named;

    HostObject(Scriptable scope, Scriptable prototype, String className, Object target, Named named) {
        super(scope, prototype);
        this.className = className;
        this.target = target;
        this.named = named;
    }

    @Override
    public String getClassName() {
        return className;
    }

    @Override
    public boolean has(String name, Scriptable start) {
        return super.has(name, start) || isNamed(name) && named.has(name);
    }

    @Override
    public Object get(String name, Scriptable start) {
        Object own = super.get(name, start);
        return own != NOT_FOUND || !isNamed(name) || !named.has(name) ? own : named.get(name);
    }

    @Override
    public void put(String name, Scriptable start, Object value) {
        if (isNamed(name) && !super.has(name, start)) named.put(name, value);
        else super.put(name, start, value);
    }

    @Override
    public void delete(String name) {
        if (isNamed(name) && !super.has(name, this)) named.delete(name);
        else super.delete(name);
    }

    @Override
    public Object[] getIds() {
        Object[] own = super.getIds();
        return named == null ? own : Stream.concat(Arrays.stream(own), Arrays.stream(named.ids())).toArray();
    }

    /** Named properties never shadow the prototype's methods and accessors. */
    private boolean isNamed(String name) {
        return named != null && !ScriptableObject.hasProperty(getPrototype(), name);
    }
}
