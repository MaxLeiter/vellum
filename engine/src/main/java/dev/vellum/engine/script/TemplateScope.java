package dev.vellum.engine.script;

import dev.vellum.shadow.rhino.Scriptable;
import dev.vellum.shadow.rhino.ScriptableObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The root scope of template expressions: a name resolves in {@code vellum.data}, then in each
 * {@code vellum.state()} object in registration order, then in the globals (this scope's parent). Assignments go to
 * the object that has the name; new names become globals, as in sloppy-mode scripts. A source's prototype chain
 * counts, except Object.prototype, so {@code toString} and friends do not shadow anything.
 */
final class TemplateScope extends ScriptableObject {
    private final RhinoScriptRuntime rt;
    private final Scriptable objectPrototype;
    private final List<Scriptable> states = new ArrayList<>();

    TemplateScope(RhinoScriptRuntime rt) {
        this.rt = rt;
        setParentScope(rt.global);
        objectPrototype = ScriptableObject.getObjectPrototype(rt.global);
    }

    void addState(Scriptable state) {
        if (!states.contains(state)) states.add(state);
    }

    @Override
    public String getClassName() {
        return "TemplateScope";
    }

    @Override
    public boolean has(String name, Scriptable start) {
        return owner(name) != null;
    }

    @Override
    public Object get(String name, Scriptable start) {
        Scriptable owner = owner(name);
        return owner == null ? NOT_FOUND : ScriptableObject.getProperty(owner, name);
    }

    @Override
    public void put(String name, Scriptable start, Object value) {
        Scriptable owner = owner(name);
        ScriptableObject.putProperty(owner != null ? owner : rt.global, name, value);
    }

    private Scriptable owner(String name) {
        if (rt.vellum.data() instanceof Scriptable data && defines(data, name)) return data;
        for (Scriptable state : states) if (defines(state, name)) return state;
        return null;
    }

    private boolean defines(Scriptable source, String name) {
        for (Scriptable o = source; o != null && o != objectPrototype; o = o.getPrototype()) {
            if (o.has(name, source)) return true;
        }
        return false;
    }
}
