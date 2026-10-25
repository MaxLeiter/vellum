package dev.vellum.engine.script;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Text;
import dev.vellum.shadow.rhino.Context;
import dev.vellum.shadow.rhino.Scriptable;

import java.util.List;

/**
 * A {@code v-if} / {@code v-else-if} / {@code v-else} chain: the first branch whose condition holds is in the
 * document, just before an anchor. A branch is compiled the first time it shows and kept (with its state) while hidden.
 * A branch with a {@code :key} starts over from a fresh copy whenever the key changes, as a keyed element does
 * ({@link KeyBinding}).
 */
final class IfBinding implements Binding {
    static final class Branch {
        /** Null for v-else. */
        final Expr condition;
        /** With a :key: the element as written and the last key; null without one. */
        final KeyBinding.Keyed keyed;
        /** The element shown; with a key, a new copy for each key. */
        Element element;
        Block block;

        Branch(Expr condition, Element element, Expr key) {
            this.condition = condition;
            this.element = element;
            this.keyed = key == null ? null : new KeyBinding.Keyed((Element) element.cloneNode(true), key);
        }
    }

    private final TemplateCompiler compiler;
    private final Text anchor;
    private final List<Branch> branches;
    private final Scriptable scope;
    private Branch active;

    IfBinding(TemplateCompiler compiler, Text anchor, List<Branch> branches, Scriptable scope) {
        this.compiler = compiler;
        this.anchor = anchor;
        this.branches = branches;
        this.scope = scope;
    }

    @Override
    public boolean update(Context cx) {
        if (anchor.parentNode() == null) return false; // removed by a script
        Branch chosen = null;
        for (Branch b : branches) {
            if (b.condition == null || Js.bool(b.condition.eval(cx, scope))) {
                chosen = b;
                break;
            }
        }
        if (chosen != null && chosen.keyed != null && chosen.keyed.changed(cx, scope) && chosen.block != null) {
            // A new key: drop the compiled copy, and show a fresh one below.
            if (active == chosen) {
                chosen.element.remove();
                active = null;
            }
            chosen.element = chosen.keyed.fresh();
            chosen.block = null;
        }
        boolean changed = chosen != active;
        if (changed) {
            if (active != null) active.element.remove();
            if (chosen != null) {
                if (chosen.block == null) {
                    chosen.block = new Block();
                    compiler.compileElement(chosen.element, scope, chosen.block);
                }
                anchor.parentNode().insertBefore(chosen.element, anchor);
            }
            active = chosen;
        }
        return active != null && active.block.update(cx) || changed;
    }
}
