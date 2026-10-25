package dev.vellum.engine.script;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Text;
import dev.vellum.shadow.rhino.Context;
import dev.vellum.shadow.rhino.Scriptable;

import java.util.Objects;

/**
 * An element with {@code :key} outside a {@code v-for}: when the key's value changes, the element is replaced by a
 * fresh copy, compiled anew, as in Vue. What it held goes with the old copy, and its CSS animations start over, so
 * {@code <div class="name" :key="selected">} replays its entrance each time the selection changes.
 */
final class KeyBinding implements Binding {
    /** A keyed element as written and its last key: what tells when to start over, and the copy to start from. */
    static final class Keyed {
        /** Never in the document; each key gets a clone of it. */
        private final Element template;
        private final Expr key;
        private Object lastKey;

        Keyed(Element template, Expr key) {
            this.template = template;
            this.key = key;
        }

        /** Evaluates the key and keeps it: whether it differs from the last one. */
        boolean changed(Context cx, Scriptable scope) {
            Object k = ForBinding.normalize(key.eval(cx, scope));
            boolean changed = !Objects.equals(k, lastKey);
            lastKey = k;
            return changed;
        }

        /** A new copy of the element as written, not yet compiled. */
        Element fresh() {
            return (Element) template.cloneNode(true);
        }
    }

    private final TemplateCompiler compiler;
    private final Keyed keyed;
    private final Text anchor;
    private final Scriptable scope;
    private Element current;
    private Block block;

    KeyBinding(TemplateCompiler compiler, Element template, Text anchor, Scriptable scope, Expr key) {
        this.compiler = compiler;
        this.keyed = new Keyed(template, key);
        this.anchor = anchor;
        this.scope = scope;
    }

    @Override
    public boolean update(Context cx) {
        if (anchor.parentNode() == null) return false; // removed by a script
        boolean changed = keyed.changed(cx, scope) || current == null;
        if (changed) {
            Element fresh = keyed.fresh();
            Block freshBlock = new Block();
            compiler.compileElement(fresh, scope, freshBlock);
            if (current != null) current.remove();
            anchor.parentNode().insertBefore(fresh, anchor);
            current = fresh;
            block = freshBlock;
        }
        return block.update(cx) || changed;
    }
}
