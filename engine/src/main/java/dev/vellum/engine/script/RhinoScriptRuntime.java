package dev.vellum.engine.script;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.event.Event;
import dev.vellum.shadow.rhino.Callable;
import dev.vellum.shadow.rhino.Context;
import dev.vellum.shadow.rhino.EvaluatorException;
import dev.vellum.shadow.rhino.JavaScriptException;
import dev.vellum.shadow.rhino.RhinoException;
import dev.vellum.shadow.rhino.Script;
import dev.vellum.shadow.rhino.Scriptable;
import dev.vellum.shadow.rhino.ScriptableObject;
import dev.vellum.shadow.rhino.Undefined;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The Rhino-backed {@link ScriptRuntime}: one sandboxed global scope per document holding the DOM bindings, the
 * {@code vellum} API and the template engine.
 *
 * <p>Every call from Java into scripts is an <em>entry</em> ({@link #enter}): scripts, inline handlers, listeners,
 * timers, animation frames, host messages and template events. An entry runs inside the {@link Sandbox} budget,
 * reports errors to the document instead of throwing them, and when the outermost entry finishes it drains
 * microtasks (promise jobs, {@code queueMicrotask}). Each outermost entry marks the templates for re-rendering,
 * which happens once per frame in {@link #beforeRestyle}: the DOM reflects state changes at the next frame, as with
 * Vue's {@code nextTick} ({@code vellum.nextTick(fn)} runs {@code fn} after that update).
 */
final class RhinoScriptRuntime implements ScriptRuntime {
    private static final int INLINE_HANDLER_CACHE = 256;

    final Document document;
    final ScriptableObject global;
    final Js js;
    final EventBindings events;
    final DomBindings dom;
    final CanvasBindings canvases;
    final StyleBindings styles;
    final AnimationBindings animations;
    final Templates templates;
    final VellumApi vellum;
    /** Compiled inline handlers by source; bounded because scripts can generate handler code. */
    private final Map<String, Callable> inlineHandlers = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Callable> eldest) {
            return size() > INLINE_HANDLER_CACHE;
        }
    };
    private int depth;
    private boolean disposed;

    static RhinoScriptRuntime create(Document document) {
        return Sandbox.run(cx -> new RhinoScriptRuntime(document, cx));
    }

    private RhinoScriptRuntime(Document document, Context cx) {
        this.document = document;
        global = cx.initSafeStandardObjects();
        js = new Js(this);
        events = new EventBindings(this);
        dom = new DomBindings(this);
        canvases = new CanvasBindings(this);
        styles = new StyleBindings(this);
        animations = new AnimationBindings(this);
        templates = new Templates(this);
        vellum = new VellumApi(this);
        WindowBindings.install(this);
    }

    // ---- ScriptRuntime ----

    @Override
    public void evaluate(String source, String sourceName) {
        run(source, sourceName, false);
    }

    @Override
    public String evaluateToJson(String source, String sourceName) {
        return run(source, sourceName, true) instanceof String json ? json : null;
    }

    /** Runs a script as an entry; its completion value, as JSON when {@code json}. */
    private Object run(String source, String sourceName, boolean json) {
        String what = "Error in script " + sourceName;
        return enter(what, cx -> {
            Script script = compile(what, () -> cx.compileString(source, sourceName, 1, null));
            if (script == null) return null;
            Object value = script.exec(cx, global, global);
            return json ? js.stringify(value) : value;
        });
    }

    @Override
    public void runInlineHandler(Element element, String code, Event event) {
        String what = "Error in on" + event.type + " handler";
        enter(what, cx -> {
            Callable handler = inlineHandlers.get(code);
            if (handler == null) {
                handler = compile(what, () -> cx.compileFunction(global, "function (event) {\n" + code + "\n}",
                        document.url() + "#on" + event.type, 0, null));
                if (handler == null) return null;
                inlineHandlers.put(code, handler);
            }
            Object result = handler.call(cx, global, dom.wrap(element), new Object[] {events.wrap(event)});
            if (Boolean.FALSE.equals(result)) event.preventDefault(); // "return false" cancels, as in browsers
            return result;
        });
    }

    @Override
    public void receive(String channel, String json) {
        vellum.receive(channel, json);
    }

    /** Templates bind once scripts have run (so their {@code vellum.state()} calls are in). */
    @Override
    public void documentLoaded() {
        enter("Error in templates", cx -> {
            templates.install(cx);
            return null;
        });
    }

    @Override
    public void beforeRestyle() {
        if (!templates.needsDigest()) return;
        enter("Error in templates", cx -> {
            templates.digest(cx);
            return null;
        });
    }

    @Override
    public boolean needsFrame() {
        return templates.needsDigest();
    }

    @Override
    public void dispose() {
        disposed = true;
    }

    // ---- Entries ----

    /** Calls a script function as an entry, converting {@code args} to script values. */
    Object call(String what, Callable fn, Scriptable thisObj, Object... args) {
        return enter(what, cx -> fn.call(cx, global, thisObj, js.toJsArgs(args)));
    }

    /**
     * Runs script work on behalf of the host. Errors are reported with {@code what} as the prefix and never thrown.
     * A nested entry (a listener run by a script's {@code el.click()}) shares the outer budget, and running out of
     * budget unwinds to the outermost entry, which reports it and skips settling: the page's state is unknown.
     */
    Object enter(String what, Function<Context, Object> action) {
        if (disposed) return Undefined.instance;
        return Sandbox.run(cx -> {
            if (depth > 0) return attempt(what, cx, action);
            depth++;
            templates.invalidate(); // any entry may change what templates show
            try {
                Object result = attempt(what, cx, action);
                attempt(what, cx, this::settle);
                return result;
            } catch (Sandbox.BudgetExceeded e) {
                report(what, e);
                return Undefined.instance;
            } finally {
                depth--;
            }
        });
    }

    private Object attempt(String what, Context cx, Function<Context, Object> action) {
        try {
            return action.apply(cx);
        } catch (RuntimeException e) {
            report(what, e);
            return Undefined.instance;
        }
    }

    /** The end of an outermost entry: microtasks, then unhandled promise rejections. */
    private Object settle(Context cx) {
        cx.processMicrotasks();
        cx.getUnhandledPromiseTracker().process(reason ->
                report("Uncaught (in promise)", new JavaScriptException(reason, null, 0)));
        return null;
    }

    /** Runs a compiler; a syntax error is reported with its location and yields null. */
    <T> T compile(String what, Supplier<T> compiler) {
        try {
            return compiler.get();
        } catch (EvaluatorException e) {
            document.reportError(what + ": SyntaxError: " + describe(e), e);
            return null;
        }
    }

    void report(String what, Throwable error) {
        document.reportError(what + ": " + describe(error), error);
    }

    private static String describe(Throwable error) {
        if (error instanceof RhinoException e) {
            return e.sourceName() == null ? e.details() : e.details() + " (" + e.sourceName() + ":" + e.lineNumber() + ")";
        }
        return error.getMessage() != null ? error.getMessage() : error.toString();
    }
}
