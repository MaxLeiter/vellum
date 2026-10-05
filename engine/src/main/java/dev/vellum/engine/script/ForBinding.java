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
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code v-for}: one instance per item, each a clone of the template element compiled in its own scope (the loop
 * variables, inside the enclosing scope). Instances are matched by {@code :key} (else by position), so a reordered
 * list moves existing elements, and only new items are cloned and compiled.
 */
final class ForBinding implements Binding {
    /** Items rendered at most; the cloning is Java work outside the script budget. */
    static final int MAX_ITEMS = 10_000;

    /** One item: its value, its key (the property name for objects, else the index) and its index. */
    private record Item(Object value, Object key, int index) {}

    private static final class Instance {
        final Element element;
        final NativeObject scope;
        final Block block = new Block();

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
    private List<Instance> instances = List.of();
    private Map<Object, Instance> byKey = Map.of();

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
        boolean changed = false;
        List<Instance> next = new ArrayList<>();
        Map<Object, Instance> nextByKey = new HashMap<>();
        for (Item item : items(cx)) {
            assign(probe, item);
            if (filter != null && !Js.bool(filter.eval(cx, probe))) continue;
            Object k = key == null ? item.index() : normalize(key.eval(cx, probe));
            Instance instance = byKey.get(k);
            if (instance == null || nextByKey.containsKey(k)) { // new, or a duplicate key
                instance = create();
                changed = true;
            }
            nextByKey.putIfAbsent(k, instance);
            assign(instance.scope, item);
            next.add(instance);
        }
        Set<Instance> kept = Collections.newSetFromMap(new IdentityHashMap<>());
        kept.addAll(next);
        for (Instance old : instances) {
            if (!kept.contains(old)) {
                old.element.remove();
                changed = true;
            }
        }
        // Walk backwards from the anchor, moving only the elements that are out of place.
        Node ref = anchor;
        for (int i = next.size() - 1; i >= 0; i--) {
            Element el = next.get(i).element;
            if (el.parentNode() != parent || el.nextSibling() != ref) {
                parent.insertBefore(el, ref);
                changed = true;
            }
            ref = el;
        }
        instances = next;
        byKey = nextByKey;
        for (Instance instance : next) changed |= instance.block.update(cx);
        return changed;
    }

    private Instance create() {
        Instance instance = new Instance((Element) DomBindings.clone(template, true), TemplateCompiler.childScope(scope, null, null));
        compiler.compileElement(instance.element, instance.scope, instance.block);
        return instance;
    }

    /** Sets the loop variables: {@code (value, key, index)} for objects, {@code (value, index)} otherwise. */
    private void assign(NativeObject target, Item item) {
        target.put(aliases.get(0), target, item.value());
        if (aliases.size() > 1) target.put(aliases.get(1), target, item.key());
        if (aliases.size() > 2) target.put(aliases.get(2), target, item.index());
    }

    /** Arrays, objects (own enumerable properties) and {@code n in 5} (1 to 5). */
    private List<Item> items(Context cx) {
        Object value = source.eval(cx, scope);
        List<Item> items = new ArrayList<>();
        if (value instanceof Number n) {
            for (int i = 0; i < n.doubleValue() && i < MAX_ITEMS; i++) items.add(new Item(i + 1, i, i));
        } else if (value instanceof NativeArray array) {
            List<Object> values = Js.elements(array);
            for (int i = 0; i < values.size() && i < MAX_ITEMS; i++) items.add(new Item(values.get(i), i, i));
        } else if (value instanceof Scriptable object) {
            Object[] ids = object.getIds();
            for (int i = 0; i < ids.length && i < MAX_ITEMS; i++) {
                String name = String.valueOf(ids[i]);
                items.add(new Item(Js.property(object, name), name, i));
            }
        }
        if (items.size() == MAX_ITEMS) {
            rt.document.host().log(Host.LogLevel.WARN, "v-for renders at most " + MAX_ITEMS + " items");
        }
        return items;
    }

    /** Keys compare as JS would: 1 and 1.0 are the same number, a concatenated string equals a literal one. */
    private static Object normalize(Object key) {
        if (key instanceof Number n) return n.doubleValue();
        if (key instanceof CharSequence s) return s.toString();
        return key;
    }
}
