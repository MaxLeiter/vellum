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
    private final TemplateCompiler compiler;
    /** The element as written, never in the document; each key gets a clone of it. */
    private final Element template;
    private final Text anchor;
    private final Scriptable scope;
    private final Expr key;
    private Element current;
    private Block block;
    private Object lastKey;

    KeyBinding(TemplateCompiler compiler, Element template, Text anchor, Scriptable scope, Expr key) {
        this.compiler = compiler;
        this.template = template;
        this.anchor = anchor;
        this.scope = scope;
        this.key = key;
    }

    @Override
    public boolean update(Context cx) {
        if (anchor.parentNode() == null) return false; // removed by a script
        Object k = ForBinding.normalize(key.eval(cx, scope));
        boolean changed = current == null || !Objects.equals(k, lastKey);
        if (changed) {
            Element fresh = (Element) template.cloneNode(true);
            Block freshBlock = new Block();
            compiler.compileElement(fresh, scope, freshBlock);
            if (current != null) current.remove();
            anchor.parentNode().insertBefore(fresh, anchor);
            current = fresh;
            block = freshBlock;
            lastKey = k;
        }
        return block.update(cx) || changed;
    }
}
