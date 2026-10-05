package dev.vellum.engine.script;

import dev.vellum.shadow.rhino.Context;
import dev.vellum.shadow.rhino.RhinoException;
import dev.vellum.shadow.rhino.Script;
import dev.vellum.shadow.rhino.Scriptable;
import dev.vellum.shadow.rhino.Undefined;

/**
 * A template expression or handler, compiled once to a script and run against whichever scope it is evaluated in
 * (the root template scope, a v-for item's scope, a handler's {@code $event} scope). Each distinct error is reported
 * once, so a broken binding does not flood the log on every digest.
 */
final class Expr {
    private final RhinoScriptRuntime rt;
    private final String what;
    private final Script script;
    /** The last error reported, so a failing binding does not report the same error on every digest. */
    private String reported;

    /** Compiles {@code source}; a syntax error is reported now and the expression then evaluates to undefined. */
    Expr(RhinoScriptRuntime rt, String source, String where) {
        this.rt = rt;
        this.what = "Error in template " + where;
        this.script = rt.compile(what, () ->
                Context.getCurrentContext().compileString(source, rt.document.url() + "#template", 1, null));
    }

    /** Evaluates with {@code this} = window. */
    Object eval(Context cx, Scriptable scope) {
        return eval(cx, scope, rt.global);
    }

    Object eval(Context cx, Scriptable scope, Scriptable thisObj) {
        if (script == null) return Undefined.instance;
        try {
            return script.exec(cx, scope, thisObj);
        } catch (RhinoException e) {
            if (!e.details().equals(reported)) rt.report(what, e);
            reported = e.details();
            return Undefined.instance;
        }
    }
}
