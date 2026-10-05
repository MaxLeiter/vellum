package dev.vellum.engine.script;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Scheduler;
import dev.vellum.engine.host.Host;
import dev.vellum.shadow.rhino.Callable;
import dev.vellum.shadow.rhino.Context;
import dev.vellum.shadow.rhino.ScriptableObject;
import dev.vellum.shadow.rhino.Undefined;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The global object as {@code window}: viewport, timers and animation frames (through the document's
 * {@link Scheduler}), microtasks, {@code console}, {@code performance}, {@code localStorage}/{@code sessionStorage},
 * {@code location}, {@code structuredClone} and {@code getComputedStyle}.
 */
final class WindowBindings {
    /** Characters (keys plus values) each storage area holds per document. */
    static final int STORAGE_QUOTA = 256 * 1024;
    private static final int HIDDEN = ScriptableObject.DONTENUM | ScriptableObject.READONLY;

    private WindowBindings() {}

    static void install(RhinoScriptRuntime rt) {
        ScriptableObject global = rt.global;
        Document document = rt.document;
        Scheduler scheduler = document.scheduler();
        Host host = document.host();
        global.defineProperty("window", global, HIDDEN);
        global.defineProperty("self", global, HIDDEN);
        global.defineProperty("document", rt.dom.wrap(document), HIDDEN);

        ScriptableObject location = (ScriptableObject) rt.js.newObject();
        Members.Action<Document> go = (d, a) -> navigate(d, a.str(0));
        new Members<>(rt, location, self -> document)
                .prop("href", Document::url, (d, v) -> navigate(d, Js.str(v)))
                .action("assign", go)
                .action("replace", go)
                .action("reload", (d, a) -> host.navigate(d.url()))
                .method("toString", (d, a) -> d.url());

        Members.Action<Document> clearTimer = (d, a) -> scheduler.clearTimer((int) a.num(0, 0));
        new Members<>(rt, global, self -> document)
                .get("innerWidth", Document::viewportWidth)
                .get("innerHeight", Document::viewportHeight)
                .get("devicePixelRatio", Document::devicePixelRatio)
                .prop("location", d -> location, (d, v) -> navigate(d, Js.str(v)))
                .action("addEventListener", (d, a) -> rt.events.addListener(d, a))
                .action("removeEventListener", (d, a) -> rt.events.removeListener(d, a))
                .method("dispatchEvent", (d, a) -> rt.events.dispatch(d, a))
                .method("setTimeout", (d, a) -> timer(rt, a, false))
                .method("setInterval", (d, a) -> timer(rt, a, true))
                .action("clearTimeout", clearTimer)
                .action("clearInterval", clearTimer)
                .method("requestAnimationFrame", (d, a) -> {
                    Callable fn = a.fn(0);
                    return scheduler.requestAnimationFrame(now -> rt.call("Error in requestAnimationFrame callback", fn, global, now));
                })
                .action("cancelAnimationFrame", (d, a) -> scheduler.cancelAnimationFrame((int) a.num(0, 0)))
                .action("queueMicrotask", (d, a) -> {
                    Callable fn = a.fn(0);
                    Context.getCurrentContext().enqueueMicrotask(() -> rt.call("Error in microtask", fn, global));
                })
                .method("structuredClone", (d, a) -> {
                    String json = rt.js.stringify(a.get(0));
                    return json == null ? Undefined.instance : rt.js.parseJson(json);
                })
                .method("getComputedStyle", (d, a) -> rt.styles.computedStyle(Js.unwrap(a.get(0), Element.class), a.str(1, null)))
                .action("close", (d, a) -> host.close());

        ScriptableObject performance = (ScriptableObject) rt.js.newObject();
        global.defineProperty("performance", performance, HIDDEN);
        // The frame clock: the same timestamps requestAnimationFrame callbacks receive.
        new Members<>(rt, performance, self -> scheduler).method("now", (s, a) -> s.now());

        ScriptableObject console = (ScriptableObject) rt.js.newObject();
        global.defineProperty("console", console, ScriptableObject.DONTENUM);
        Members<Host> log = new Members<>(rt, console, self -> host);
        Map<String, Host.LogLevel> levels = Map.of("debug", Host.LogLevel.DEBUG, "log", Host.LogLevel.INFO,
                "info", Host.LogLevel.INFO, "warn", Host.LogLevel.WARN, "error", Host.LogLevel.ERROR);
        levels.forEach((name, level) -> log.action(name, (h, a) -> h.log(level, format(rt.js, a))));

        HostClass<Storage> storage = new HostClass<>(rt, "Storage", Storage.class, null, null).expose("Storage");
        storage.members()
                .get("length", s -> s.items.size())
                .method("key", (s, a) -> s.items.keySet().stream().skip(Math.max(0, (long) a.num(0, 0))).findFirst().orElse(null))
                .method("getItem", (s, a) -> s.items.get(a.str(0)))
                .action("setItem", (s, a) -> s.set(a.str(0), a.str(1)))
                .action("removeItem", (s, a) -> s.remove(a.str(0)))
                .action("clear", (s, a) -> s.clear());
        global.defineProperty("localStorage", storage.wrap(new Storage()), HIDDEN);
        global.defineProperty("sessionStorage", storage.wrap(new Storage()), HIDDEN);
    }

    private static void navigate(Document document, String url) {
        document.host().navigate(document.resolveUrl(url));
    }

    /** setTimeout / setInterval: a function plus extra arguments, or a string of code. */
    private static int timer(RhinoScriptRuntime rt, Args a, boolean repeat) {
        Object handler = a.get(0);
        Object[] extra = a.from(2);
        Runnable task = handler instanceof Callable fn
                ? () -> rt.call("Error in timer", fn, rt.global, extra)
                : () -> rt.evaluate(Js.str(handler), rt.document.url() + "#timer");
        double delay = a.num(1, 0);
        if (!(delay > 0)) delay = 0; // also NaN
        Scheduler scheduler = rt.document.scheduler();
        return repeat ? scheduler.setInterval(task, delay) : scheduler.setTimeout(task, delay);
    }

    /**
     * Console formatting as in browsers: a leading string may hold {@code %s %d %i %f %o %O %c} substitutions, then
     * the remaining arguments follow, separated by spaces. Strings print raw, objects as JSON.
     */
    static String format(Js js, Args a) {
        List<String> parts = new ArrayList<>();
        int next = 0;
        if (a.length() > 0 && a.get(0) instanceof CharSequence first) {
            next = 1;
            String pattern = first.toString();
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                char f = i + 1 < pattern.length() ? pattern.charAt(i + 1) : 0;
                if (c != '%' || "sdifoOc%".indexOf(f) < 0 || f == 0) {
                    out.append(c);
                    continue;
                }
                i++;
                if (f == '%') out.append('%');
                else if (next >= a.length()) out.append('%').append(f);
                else {
                    Object v = a.get(next++);
                    switch (f) {
                        case 'd', 'i' -> {
                            double d = Js.num(v);
                            out.append(Js.str(d < 0 ? Math.ceil(d) : Math.floor(d)));
                        }
                        case 'f' -> out.append(Js.str(Js.num(v)));
                        case 'c' -> { } // CSS styling: not supported, consumed
                        default -> out.append(js.display(v));
                    }
                }
            }
            parts.add(out.toString());
        }
        for (int i = next; i < a.length(); i++) parts.add(js.display(a.get(i)));
        return String.join(" ", parts);
    }

    /** One Web Storage area: in memory for the document's lifetime, capped at {@link #STORAGE_QUOTA}. */
    static final class Storage {
        final Map<String, String> items = new LinkedHashMap<>();
        private int size;

        void set(String key, String value) {
            String old = items.get(key);
            int next = size - (old == null ? 0 : key.length() + old.length()) + key.length() + value.length();
            if (next > STORAGE_QUOTA) {
                throw Js.error("RangeError", "QuotaExceededError: storage holds at most " + STORAGE_QUOTA + " characters");
            }
            items.put(key, value);
            size = next;
        }

        void remove(String key) {
            String old = items.remove(key);
            if (old != null) size -= key.length() + old.length();
        }

        void clear() {
            items.clear();
            size = 0;
        }
    }
}
