package dev.vellum.engine.script;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.event.Event;

/**
 * A script engine bound to one document. Created by {@link dev.vellum.engine.host.Host#createScriptRuntime}.
 * Timers and animation frames go through the document's {@link dev.vellum.engine.dom.Scheduler}, so the runtime
 * only evaluates code.
 *
 * <p>Implementations must sandbox scripts: no access to Java classes, a CPU budget per call, and nothing that
 * reaches the file system or network. Documents may come from servers.
 */
public interface ScriptRuntime {
    /** Runs a classic script (inline {@code <script>} or {@code src}). Errors are reported, not thrown. */
    void evaluate(String source, String sourceName);

    /** Runs an inline handler attribute ({@code onclick="..."}) with {@code this} = element and {@code event} bound. */
    void runInlineHandler(Element element, String code, Event event);

    /**
     * Delivers a message from the host (server data, mod events) to listeners registered with
     * {@code vellum.on(channel, fn)}. {@code json} is a JSON value.
     */
    default void receive(String channel, String json) {}

    /** Called once the document's scripts have run, before {@code DOMContentLoaded} is dispatched. */
    default void documentLoaded() {}

    /**
     * Called by {@link dev.vellum.engine.dom.Document#frame} once per frame, after timers, animation frames and
     * input and before restyle: applies the DOM updates the runtime deferred (template bindings re-render here, at
     * most once per frame however many entries ran).
     */
    default void beforeRestyle() {}

    /** Releases resources. The runtime is unusable afterwards. */
    void dispose();
}
