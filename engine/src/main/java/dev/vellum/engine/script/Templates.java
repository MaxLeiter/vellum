package dev.vellum.engine.script;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.Host;
import dev.vellum.shadow.rhino.Callable;
import dev.vellum.shadow.rhino.Context;
import dev.vellum.shadow.rhino.Scriptable;

import java.util.ArrayList;
import java.util.List;

/**
 * Templates (D-009): Vue-style syntax bound by dirty checking. When the document has loaded it is compiled once into
 * {@link Binding}s ({@link TemplateCompiler}) and rendered. Every entry then marks the templates {@link #invalidate
 * dirty}, and once per frame the runtime runs {@link #digest}, which re-evaluates the bindings, touches the DOM only
 * where a rendered value changed, and repeats until nothing changes.
 */
final class Templates {
    static final int MAX_PASSES = 10;

    private final RhinoScriptRuntime rt;
    private final TemplateScope scope;
    private final List<Element> cloaked = new ArrayList<>();
    private final TemplateCompiler compiler;
    private final Binding.Block root = new Binding.Block();
    /** {@code vellum.nextTick} callbacks waiting for the next digest. */
    private List<Callable> afterDigest = new ArrayList<>();
    private boolean installed, dirty;

    Templates(RhinoScriptRuntime rt) {
        this.rt = rt;
        scope = new TemplateScope(rt);
        compiler = new TemplateCompiler(rt, cloaked);
    }

    boolean installed() {
        return installed;
    }

    /** Compiles and renders the document (once scripts have run, so the load event sees rendered templates). */
    void install(Context cx) {
        if (installed) return;
        installed = true;
        Element html = rt.document.documentElement();
        if (html != null) compiler.compileElement(html, scope, root);
        digest(cx);
    }

    /** Something may have changed what the templates show: the next frame digests. */
    void invalidate() {
        dirty = true;
    }

    boolean needsDigest() {
        return dirty && (installed || !afterDigest.isEmpty());
    }

    /** {@code vellum.nextTick(fn)}: runs {@code fn} after the next digest, when the DOM shows the current state. */
    void nextTick(Callable fn) {
        afterDigest.add(fn);
        dirty = true;
    }

    /** {@code vellum.state(object)}: adds the object's properties to the template scope and returns it. */
    Object state(Object value) {
        if (!(value instanceof Scriptable state)) throw Js.typeError("vellum.state() takes an object");
        scope.addState(state);
        return state;
    }

    void digest(Context cx) {
        dirty = false;
        if (installed) {
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
        if (!afterDigest.isEmpty()) {
            List<Callable> callbacks = afterDigest;
            afterDigest = new ArrayList<>();
            for (Callable fn : callbacks) rt.call("Error in vellum.nextTick callback", fn, rt.global);
            dirty = true; // what the callbacks changed shows at the next frame
        }
    }
}
