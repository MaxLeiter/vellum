package dev.vellum.engine.script;

import dev.vellum.engine.host.Host;
import dev.vellum.shadow.rhino.Callable;
import dev.vellum.shadow.rhino.LambdaFunction;
import dev.vellum.shadow.rhino.ScriptableObject;
import dev.vellum.shadow.rhino.Undefined;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code vellum} object: data and messages from the host or server ({@code data}, {@code on}, {@code off}),
 * messages back ({@code send}, rate limited), {@code close}, {@code playSound}, {@code t} (translations),
 * {@code open} (navigation), {@code state} (reactive state for templates) and {@code nextTick} (a callback after
 * templates next render).
 */
final class VellumApi {
    /** Messages per second a document may send; bursts up to the same count are allowed. */
    static final int SEND_RATE = 20;

    private final RhinoScriptRuntime rt;
    private final Host host;
    private final Map<String, List<Callable>> listeners = new HashMap<>();
    private Object data;
    private double sendTokens = SEND_RATE;
    private double sendClock;
    private boolean throttled;

    VellumApi(RhinoScriptRuntime rt) {
        this.rt = rt;
        this.host = rt.document.host();
        data = rt.js.newObject();
        ScriptableObject vellum = (ScriptableObject) rt.js.newObject();
        rt.global.defineProperty("vellum", vellum, ScriptableObject.DONTENUM | ScriptableObject.READONLY);
        new Members<>(rt, vellum, self -> this)
                .prop("data", v -> v.data, (v, value) -> v.data = value)
                .method("send", (v, a) -> v.send(a.str(0), a.get(1)))
                .method("on", (v, a) -> v.on(a.str(0), a.fn(1)))
                .action("off", (v, a) -> v.off(a.str(0), a.get(1)))
                .action("close", (v, a) -> host.close())
                .action("playSound", (v, a) -> host.playSound(a.str(0), (float) a.num(1, 1), (float) a.num(2, 1)))
                .method("t", (v, a) -> host.translate(a.str(0), Arrays.stream(a.from(1)).map(Js::str).toArray(String[]::new)))
                .action("open", (v, a) -> host.navigate(rt.document.resolveUrl(a.str(0))))
                .method("state", (v, a) -> rt.templates.state(a.has(0) ? a.get(0) : rt.js.newObject()))
                .action("nextTick", (v, a) -> rt.templates.nextTick(a.fn(0)));
    }

    /** The current {@code vellum.data}. */
    Object data() {
        return data;
    }

    /**
     * A message from the host: {@code json} is parsed; channel "data" replaces {@code vellum.data}; listeners of the
     * channel get the value. Templates re-render afterwards, like after any entry.
     */
    void receive(String channel, String json) {
        rt.enter("Error in message '" + channel + "'", cx -> {
            Object value = rt.js.parseJson(json);
            if (channel.equals("data")) data = value;
            for (Callable fn : List.copyOf(listeners.getOrDefault(channel, List.of()))) {
                rt.call("Error in vellum.on('" + channel + "') listener", fn, rt.global, value);
            }
            return null;
        });
    }

    /** Sends {@code JSON.stringify(value)}; returns false when dropped by the rate limit. */
    private boolean send(String channel, Object value) {
        double now = rt.document.scheduler().now();
        sendTokens = Math.min(SEND_RATE, sendTokens + (now - sendClock) * SEND_RATE / 1000);
        sendClock = now;
        if (sendTokens < 1) {
            if (!throttled) host.log(Host.LogLevel.WARN, "vellum.send: over " + SEND_RATE + " messages per second, dropping");
            throttled = true;
            return false;
        }
        sendTokens--;
        throttled = false;
        String json = rt.js.stringify(value);
        host.send(channel, json == null ? "null" : json);
        return true;
    }

    /** Adds a listener; returns a function that removes it. */
    private Object on(String channel, Callable fn) {
        listeners.computeIfAbsent(channel, k -> new ArrayList<>()).add(fn);
        return new LambdaFunction(rt.global, "off", 0, (cx, scope, thisObj, args) -> {
            off(channel, fn);
            return Undefined.instance;
        });
    }

    private void off(String channel, Object fn) {
        List<Callable> list = listeners.get(channel);
        if (list != null) list.remove(fn);
    }
}
