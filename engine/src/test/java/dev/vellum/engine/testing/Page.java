package dev.vellum.engine.testing;

import com.google.gson.JsonParser;
import dev.vellum.engine.css.StyleEngine;
import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.event.Event;
import dev.vellum.engine.event.InputEvent;
import dev.vellum.engine.event.Modifiers;
import dev.vellum.engine.event.TransitionEvent;
import dev.vellum.engine.input.InputHandler;
import dev.vellum.engine.paint.HitResult;
import dev.vellum.engine.style.ComputedStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * A page loaded by {@link TestHost#load}, driven the way a host drives a document: frames at explicit times
 * ({@link #frame}), input at viewport points through the real hit test ({@link #click}, {@link #hover},
 * {@link #type}...), painting onto a {@link RecordingCanvas}. Element arguments are aimed where pointer input reaches
 * them ({@link #centre}).
 */
public final class Page {
    public static final Modifiers NONE = Modifiers.NONE;
    public static final Modifiers SHIFT = new Modifiers(true, false, false, false);
    public static final Modifiers ALT = new Modifiers(false, false, true, false);
    /** Ctrl and Meta together, so {@link Modifiers#shortcut()} holds on every OS. */
    public static final Modifiers SHORTCUT = new Modifiers(false, true, false, true);
    public static final Modifiers SHORTCUT_SHIFT = new Modifiers(true, true, false, true);

    public final TestHost host;
    public final Document doc;
    public final InputHandler input;
    /** Events recorded by {@link #listen}. */
    public final List<String> log = new ArrayList<>();
    private double now;

    Page(TestHost host, Document doc) {
        this.host = host;
        this.doc = doc;
        this.input = doc.input();
    }

    // ---- Time ----

    /** Runs a frame 16 ms after the last one. */
    public Page frame() {
        return frame(now + 16);
    }

    /** Runs a frame at {@code ms}: timers, scrolling, template updates, restyle, animations and layout. */
    public Page frame(double ms) {
        now = ms;
        doc.frame(ms);
        return this;
    }

    /** Runs the next frame and reports whether it laid the document out again. */
    public boolean frameLaysOut() {
        return frameLaysOut(now + 16);
    }

    /**
     * Runs a frame at {@code ms} and reports whether it laid the document out again (every layout builds a new box
     * tree).
     */
    public boolean frameLaysOut(double ms) {
        Object tree = doc.layoutEngine().root();
        frame(ms);
        return doc.layoutEngine().root() != tree;
    }

    // ---- Queries ----

    /** The style of a lone {@code <div>} with the given inline declarations, on a fresh page. */
    public static ComputedStyle styleOf(String declarations) {
        return new TestHost().load("<div id=t style='" + declarations.replace("'", "&#39;") + "'></div>").style("#t");
    }

    public Element byId(String id) {
        Element e = doc.getElementById(id);
        if (e == null) throw new AssertionError("No element #" + id);
        return e;
    }

    public Element query(String selector) {
        Element e = doc.querySelector(selector);
        if (e == null) throw new AssertionError("No element matches " + selector);
        return e;
    }

    /** The used style of the element matching {@code selector} (after animations). */
    public ComputedStyle style(String selector) {
        return query(selector).style;
    }

    /** A property's computed value as {@code getComputedStyle} serializes it. */
    public String computed(String selector, String property) {
        return StyleEngine.computedValue(style(selector), property);
    }

    /**
     * Where pointer input reaches an element, in viewport px: the centre of the part of it that shows, as
     * {@link Document#pointerTarget} finds it (scrolling it into view when none shows). Fails when the hit test does
     * not find the element there.
     */
    public float[] centre(Element e) {
        float[] at = doc.pointerTarget(e);
        if (at != null) return at;
        float[] r = e.getBoundingClientRect();
        float x = r[0] + r[2] / 2, y = r[1] + r[3] / 2;
        HitResult hit = doc.hitTest(x, y);
        throw new AssertionError("Pointer input can't reach " + e + "; the centre of its border box (" + x + ", " + y
                + ") hits " + (hit == null ? "nothing" : hit.element()));
    }

    // ---- Input ----

    public boolean move(float x, float y) {
        return input.mouseMove(x, y, NONE);
    }

    /** Moves the pointer to the element's centre. */
    public Page hover(Element e) {
        float[] at = centre(e);
        move(at[0], at[1]);
        return this;
    }

    public boolean down(float x, float y) {
        return input.mouseDown(x, y, 0, NONE);
    }

    public boolean up(float x, float y) {
        return input.mouseUp(x, y, 0, NONE);
    }

    /** A primary click (press and release) at a viewport point. */
    public Page click(float x, float y) {
        return click(x, y, NONE);
    }

    public Page click(float x, float y, Modifiers mods) {
        input.mouseDown(x, y, 0, mods);
        input.mouseUp(x, y, 0, mods);
        return this;
    }

    /** Moves to the element's centre and clicks there. */
    public Page click(Element e) {
        float[] at = centre(e);
        move(at[0], at[1]);
        return click(at[0], at[1]);
    }

    public boolean wheel(float x, float y, float dx, float dy) {
        return input.wheel(x, y, dx, dy, NONE);
    }

    /**
     * Presses and releases a key; single characters get the matching {@code KeyX} code. Returns whether the keydown
     * was consumed.
     */
    public boolean key(String key) {
        return key(key, NONE);
    }

    public boolean key(String key, Modifiers mods) {
        String code = key.length() == 1 ? "Key" + key.toUpperCase() : key;
        boolean consumed = input.keyDown(key, code, mods);
        input.keyUp(key, code, mods);
        return consumed;
    }

    /** Types text like a keyboard: a keydown, a typed character and a keyup per character. */
    public Page type(String text) {
        text.codePoints().forEach(cp -> {
            String s = new String(Character.toChars(cp));
            input.keyDown(s, "", NONE);
            input.charTyped(s);
            input.keyUp(s, "", NONE);
        });
        return this;
    }

    // ---- Events ----

    /**
     * Records events of the given types reaching {@code el} in {@link #log} as {@code type:target}, the target
     * being its id (else its tag). Input events with data add {@code =data}, transition and animation events
     * {@code =name@elapsedSeconds}.
     */
    public void listen(Element el, String... types) {
        for (String type : types) el.addEventListener(type, this::record);
    }

    private void record(Event e) {
        String target = e.target() instanceof Element t ? (t.id().isEmpty() ? t.tagName() : t.id()) : "#document";
        String data = switch (e) {
            case InputEvent ie when ie.data != null -> "=" + ie.data;
            case TransitionEvent t -> "=" + t.name + "@" + t.elapsedSeconds;
            default -> "";
        };
        log.add(e.type + ":" + target + data);
    }

    /** The log so far, then clears it. */
    public List<String> takeLog() {
        List<String> out = List.copyOf(log);
        log.clear();
        return out;
    }

    // ---- Painting ----

    /** Paints the document onto a new recording canvas, checking every save was restored. */
    public RecordingCanvas paint() {
        return paint(new RecordingCanvas());
    }

    /** Paints onto {@code canvas} (set up with a device pixel size, say) and returns it. */
    public RecordingCanvas paint(RecordingCanvas canvas) {
        doc.paint(canvas);
        if (!canvas.balanced()) throw new AssertionError("unbalanced save/restore");
        return canvas;
    }

    // ---- Scripts ----

    /** Runs {@code code} as a classic script; the DOM reflects template changes at the next frame. */
    public void run(String code) {
        doc.scripts().evaluate(code, "test:eval.js");
    }

    /** {@code String(expression)} evaluated in the page. */
    public String eval(String expression) {
        String json = doc.scripts().evaluateToJson("String(" + expression + ")", "test:eval.js");
        if (json == null) throw new AssertionError("No value for " + expression + "; errors: " + host.errors);
        return JsonParser.parseString(json).getAsString();
    }

    /** The errors reported so far, one per line. */
    public String errors() {
        return String.join("\n", host.errors);
    }
}
