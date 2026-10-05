package dev.vellum.engine.script;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.Host;
import dev.vellum.shadow.rhino.Context;
import dev.vellum.shadow.rhino.Scriptable;

import java.util.ArrayList;
import java.util.List;

/**
 * Templates (D-009): Vue-style syntax bound by dirty checking. When the document has loaded it is compiled once into
 * {@link Binding}s ({@link TemplateCompiler}); after every entry the runtime calls {@link #digest}, which re-evaluates
 * them, touches the DOM only where a rendered value changed, and repeats until nothing changes.
 */
final class Templates {
    static final int MAX_PASSES = 10;

    private final RhinoScriptRuntime rt;
    private final TemplateScope scope;
    private final List<Element> cloaked = new ArrayList<>();
    private final TemplateCompiler compiler;
    private final Binding.Block root = new Binding.Block();
    private boolean installed;

    Templates(RhinoScriptRuntime rt) {
        this.rt = rt;
        scope = new TemplateScope(rt);
        compiler = new TemplateCompiler(rt, cloaked);
    }

    /** Compiles the document (once scripts have run); the entry that calls this then runs the first digest. */
    void install() {
        if (installed) return;
        installed = true;
        Element html = rt.document.documentElement();
        if (html != null) compiler.compileElement(html, scope, root);
    }

    /** {@code vellum.state(object)}: adds the object's properties to the template scope and returns it. */
    Object state(Object value) {
        if (!(value instanceof Scriptable state)) throw Js.typeError("vellum.state() takes an object");
        scope.addState(state);
        return state;
    }

    void digest(Context cx) {
        if (!installed) return;
        int passes = 1;
        while (root.update(cx)) {
            if (++passes > MAX_PASSES) {
                rt.document.host().log(Host.LogLevel.WARN, "Templates still changing after " + MAX_PASSES
                        + " passes: an expression changes state each time it is evaluated");
                break;
            }
        }
        for (Element e : cloaked) e.removeAttribute("v-cloak");
        cloaked.clear();
    }
}
