package dev.vellum.engine.input;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.event.Event;
import dev.vellum.engine.event.InputEvent;
import dev.vellum.engine.event.Modifiers;
import dev.vellum.engine.layout.Box;
import dev.vellum.engine.paint.HitResult;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Cursor;
import dev.vellum.engine.testing.TestHost;

import java.util.ArrayList;
import java.util.List;

/**
 * A parsed document (no restyle or layout: those are other workstreams' stubs) with hand-built boxes, a fake hit
 * tester, an event log and a clock.
 */
final class Fixture {
    static final Modifiers NONE = Modifiers.NONE;
    static final Modifiers SHIFT = new Modifiers(true, false, false, false);
    static final Modifiers ALT = new Modifiers(false, false, true, false);
    /** Ctrl and Meta together, so {@link Modifiers#shortcut()} holds on every OS. */
    static final Modifiers SHORTCUT = new Modifiers(false, true, false, true);
    static final Modifiers SHORTCUT_SHIFT = new Modifiers(true, true, false, true);

    /** A host that also records cursor changes. */
    static final class Host extends TestHost {
        final List<Cursor> cursors = new ArrayList<>();

        @Override
        public void setCursor(Cursor cursor) { cursors.add(cursor); }
    }

    final Host host = new Host();
    final Document doc;
    final InputHandler input;
    final List<String> log = new ArrayList<>();
    /** When set, every hit test returns this element; otherwise the deepest box containing the point wins. */
    Element forcedHit;

    Fixture(String html) {
        doc = Document.parse(host, "test:x.html", html);
        input = doc.input();
        input.setHitTester(this::hitTest);
    }

    Element el(String id) {
        return doc.getElementById(id);
    }

    /** Gives an element a block box at (x, y) relative to {@code parent}'s box (or the viewport), with a default style. */
    Box box(Element el, Box parent, float x, float y, float w, float h) {
        if (el.style == null) el.style = new ComputedStyle();
        Box b = new Box(Box.Kind.BLOCK, el, el.style);
        b.x = x;
        b.y = y;
        b.width = w;
        b.height = h;
        b.scrollWidth = w;
        b.scrollHeight = h;
        if (parent != null) parent.add(b);
        el.box = b;
        return b;
    }

    Box box(String id, Box parent, float x, float y, float w, float h) {
        return box(el(id), parent, x, y, w, h);
    }

    private HitResult hitTest(float x, float y) {
        if (forcedHit != null) return new HitResult(forcedHit, forcedHit.box, 0, 0, null, 0);
        Element found = null;
        for (Element e : doc.getElementsByTagName("*")) {
            if (e.box != null && Dom.inside(e.box.clientRect(), x, y)) found = e;
        }
        return found == null ? null : new HitResult(found, found.box, 0, 0, null, 0);
    }

    /** Logs events of the given types reaching {@code el}, as "type:targetId" (plus "=data" for input events with data). */
    void listen(Element el, String... types) {
        for (String type : types) el.addEventListener(type, this::record);
    }

    private void record(Event e) {
        String target = e.target() instanceof Element t ? (t.id().isEmpty() ? t.tagName() : t.id()) : "#document";
        String data = e instanceof InputEvent ie && ie.data != null ? "=" + ie.data : "";
        log.add(e.type + ":" + target + data);
    }

    void time(double ms) {
        doc.scheduler().run(ms);
    }

    // ---- Input shortcuts ----

    void move(float x, float y) { input.mouseMove(x, y, NONE); }
    void down(float x, float y) { input.mouseDown(x, y, 0, NONE); }
    void up(float x, float y) { input.mouseUp(x, y, 0, NONE); }

    void click(float x, float y) {
        down(x, y);
        up(x, y);
    }

    boolean key(String key) { return key(key, NONE); }

    boolean key(String key, Modifiers mods) {
        String code = key.length() == 1 ? "Key" + key.toUpperCase() : key;
        boolean consumed = input.keyDown(key, code, mods);
        input.keyUp(key, code, mods);
        return consumed;
    }

    /** Types text like a keyboard: a keydown and a charTyped per character. */
    void type(String text) {
        text.codePoints().forEach(cp -> {
            String s = new String(Character.toChars(cp));
            input.keyDown(s, "", NONE);
            input.charTyped(s);
            input.keyUp(s, "", NONE);
        });
    }
}
