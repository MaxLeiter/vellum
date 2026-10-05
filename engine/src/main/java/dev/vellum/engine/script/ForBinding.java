package dev.vellum.engine.script;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Node;
import dev.vellum.engine.dom.Text;
import dev.vellum.engine.host.Host;
import dev.vellum.shadow.rhino.Context;
import dev.vellum.shadow.rhino.NativeArray;
import dev.vellum.shadow.rhino.NativeObject;
import dev.vellum.shadow.rhino.Scriptable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code v-for}: one instance per item, each a clone of the template element compiled in its own scope (the loop
 * variables, inside the enclosing scope). Instances are matched by {@code :key} (else by position), so a reordered
 * list moves existing elements, and only new items are cloned and compiled.
 */
final class ForBinding implements Binding {
    /** Items rendered at most; the cloning is Java work outside the script budget. */
    static final int MAX_ITEMS = 10_000;

    private static final class Instance {
        final Element element;
        final NativeObject scope;
        final Block block = new Block();
        /** The last update that rendered this instance. */
        int rendered;

        Instance(Element element, NativeObject scope) {
            this.element = element;
            this.scope = scope;
        }
    }

    private final RhinoScriptRuntime rt;
    private final TemplateCompiler compiler;
    private final Element template;
    private final Text anchor;
    private final Scriptable scope;
    private final List<String> aliases;
    private final Expr source, key, filter;
    /** Evaluates :key and v-if for an item before its instance is known. */
    private final NativeObject probe;
    /** The rendered instances in order and by key; each update fills the spare pair and swaps. */
    private List<Instance> instances = new ArrayList<>(), nextInstances = new ArrayList<>();
    private Map<Object, Instance> byKey = new HashMap<>(), nextByKey = new HashMap<>();
    private int updates;
    /** Whether the update in progress created an instance. */
    private boolean created;

    ForBinding(RhinoScriptRuntime rt, TemplateCompiler compiler, Element template, Text anchor, Scriptable scope,
               List<String> aliases, Expr source, Expr key, Expr filter) {
        this.rt = rt;
        this.compiler = compiler;
        this.template = template;
        this.anchor = anchor;
        this.scope = scope;
        this.aliases = aliases;
        this.source = source;
        this.key = key;
        this.filter = filter;
        probe = TemplateCompiler.childScope(scope, null, null);
    }

    @Override
    public boolean update(Context cx) {
        Node parent = anchor.parentNode();
        if (parent == null) return false; // removed by a script
        updates++;
        created = false;
        renderItems(cx);
        boolean changed = created;
        for (Instance old : instances) {
            if (old.rendered != updates) {
                old.element.remove();
                changed = true;
            }
        }
        changed |= arrange(parent);
        List<Instance> rendered = nextInstances;
        nextInstances = instances;
        instances = rendered;
        Map<Object, Instance> keyed = nextByKey;
        nextByKey = byKey;
        byKey = keyed;
        nextInstances.clear(); // the spares hold nothing between updates, so removed instances can go
        nextByKey.clear();
        for (Instance instance : rendered) changed |= instance.block.update(cx);
        return changed;
    }

    /** Arrays, objects (own enumerable properties) and {@code n in 5} (1 to 5), each through {@link #render}. */
    private void renderItems(Context cx) {
        Object value = source.eval(cx, scope);
        int count = 0;
        if (value instanceof Number n) {
            for (int i = 0; i < n.doubleValue() && i < MAX_ITEMS; i++, count++) render(cx, i + 1, i, i);
        } else if (value instanceof NativeArray array) {
            Object[] values = cx.getElements(array);
            for (int i = 0; i < values.length && i < MAX_ITEMS; i++, count++) render(cx, values[i], i, i);
        } else if (value instanceof Scriptable object) {
            Object[] ids = object.getIds();
            for (int i = 0; i < ids.length && i < MAX_ITEMS; i++, count++) {
                String name = String.valueOf(ids[i]);
                render(cx, Js.property(object, name), name, i);
            }
        }
        if (count == MAX_ITEMS) rt.document.host().log(Host.LogLevel.WARN, "v-for renders at most " + MAX_ITEMS + " items");
    }

    /**
     * Renders one item ({@code key} is the property name for objects, else the index): reuses the instance of its
     * key, unless another item took it already (a duplicate key), else creates one.
     */
    private void render(Context cx, Object value, Object itemKey, int index) {
        assign(probe, value, itemKey, index);
        if (filter != null && !Js.bool(filter.eval(cx, probe))) return;
        Object k = key == null ? index : normalize(key.eval(cx, probe));
        Instance instance = byKey.get(k);
        if (instance == null || nextByKey.containsKey(k)) {
            instance = create();
            created = true;
        }
        nextByKey.putIfAbsent(k, instance);
        instance.rendered = updates;
        assign(instance.scope, value, itemKey, index);
        nextInstances.add(instance);
    }

    /**
     * Puts the rendered instances, in order, right before the anchor. Walks back from the anchor by position,
     * moving only the elements that are out of place; returns whether any moved.
     */
    private boolean arrange(Node parent) {
        boolean moved = false;
        int at = indexOf(parent, anchor);
        Node ref = anchor;
        for (int i = nextInstances.size() - 1; i >= 0; i--) {
            Element el = nextInstances.get(i).element;
            if (at > 0 && parent.childAt(at - 1) == el) {
                at--;
            } else {
                parent.insertBefore(el, ref);
                at = indexOf(parent, el);
                moved = true;
            }
            ref = el;
        }
        return moved;
    }

    private static int indexOf(Node parent, Node child) {
        for (int i = 0, n = parent.childCount(); i < n; i++) if (parent.childAt(i) == child) return i;
        return -1;
    }

    private Instance create() {
        Instance instance = new Instance((Element) DomBindings.clone(template, true), TemplateCompiler.childScope(scope, null, null));
        compiler.compileElement(instance.element, instance.scope, instance.block);
        return instance;
    }

    /** Sets the loop variables: {@code (value, key, index)} for objects, {@code (value, index)} otherwise. */
    private void assign(NativeObject target, Object value, Object itemKey, int index) {
        target.put(aliases.get(0), target, value);
        if (aliases.size() > 1) target.put(aliases.get(1), target, itemKey);
        if (aliases.size() > 2) target.put(aliases.get(2), target, index);
    }

    /** Keys compare as JS would: 1 and 1.0 are the same number, a concatenated string equals a literal one. */
    private static Object normalize(Object key) {
        if (key instanceof Number n) return n.doubleValue();
        if (key instanceof CharSequence s) return s.toString();
        return key;
    }
}
