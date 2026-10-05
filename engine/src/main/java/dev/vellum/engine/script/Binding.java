package dev.vellum.engine.script;

import dev.vellum.shadow.rhino.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/** A live piece of a compiled template, re-evaluated on every digest. */
interface Binding {
    /** Re-evaluates and patches the DOM where the result changed; returns whether anything changed. */
    boolean update(Context cx);

    /** The bindings of one compiled subtree (the document, a v-for item, a v-if branch), updated in order. */
    final class Block implements Binding {
        private final List<Binding> bindings = new ArrayList<>();

        void add(Binding binding) {
            bindings.add(binding);
        }

        @Override
        public boolean update(Context cx) {
            boolean changed = false;
            for (Binding b : bindings) changed |= b.update(cx);
            return changed;
        }
    }

    /** Renders one computed value into the DOM, writing only when it differs from what it last wrote. */
    final class Value<V> implements Binding {
        private static final Object UNSET = new Object();
        private final Function<Context, V> compute;
        private final Consumer<V> apply;
        private Object last = UNSET;

        Value(Function<Context, V> compute, Consumer<V> apply) {
            this.compute = compute;
            this.apply = apply;
        }

        @Override
        public boolean update(Context cx) {
            V value = compute.apply(cx);
            if (Objects.equals(value, last)) return false;
            last = value;
            apply.accept(value);
            return true;
        }
    }
}
