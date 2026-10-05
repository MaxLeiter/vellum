package dev.vellum.engine.script;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Text;
import dev.vellum.shadow.rhino.Context;
import dev.vellum.shadow.rhino.Scriptable;

import java.util.List;

/**
 * A {@code v-if} / {@code v-else-if} / {@code v-else} chain: the first branch whose condition holds is in the
 * document, just before an anchor. A branch is compiled the first time it shows and kept (with its state) while hidden.
 */
final class IfBinding implements Binding {
    static final class Branch {
        /** Null for v-else. */
        final Expr condition;
        final Element element;
        Block block;

        Branch(Expr condition, Element element) {
            this.condition = condition;
            this.element = element;
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
