package dev.vellum.engine.input;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Node;
import dev.vellum.engine.event.Event;
import dev.vellum.engine.event.Modifiers;

/**
 * Turns host input into DOM events and default actions: hover/active/focus state, click synthesis, wheel and
 * scrollbar scrolling (smooth), keyboard focus navigation, text editing in inputs, and the cursor.
 * STUB: implemented by the input workstream.
 */
public final class InputHandler {
    private final Document document;

    public InputHandler(Document document) {
        this.document = document;
    }

    /** Mouse moved to (x, y) in viewport px. Returns true if the document is under the pointer. */
    public boolean mouseMove(float x, float y, Modifiers mods) { return false; }

    /** A mouse button went down. {@code button}: 0 left, 1 middle, 2 right. Returns true if consumed. */
    public boolean mouseDown(float x, float y, int button, Modifiers mods) { return false; }

    public boolean mouseUp(float x, float y, int button, Modifiers mods) { return false; }

    /** Wheel input; deltas in px, positive scrolls down/right. Returns true if consumed. */
    public boolean wheel(float x, float y, float deltaX, float deltaY, Modifiers mods) { return false; }

    /** A key went down. {@code key}/{@code code} use DOM names. Returns true if consumed (default prevented or handled). */
    public boolean keyDown(String key, String code, int keyCode, boolean repeat, Modifiers mods) { return false; }

    public boolean keyUp(String key, String code, int keyCode, Modifiers mods) { return false; }

    /** Text input (already composed characters). Returns true if consumed. */
    public boolean charTyped(String text) { return false; }

    /** Per-frame work before restyle: smooth scrolling, caret blink, drag auto-scroll. */
    public void tick(double nowMs) {}

    /** Called after each relayout (re-validate hover under the pointer, clamp scroll offsets). */
    public void afterLayout() {}

    /** Called before a node leaves the document. */
    public void nodeRemoving(Node node) {}

    /** Default action for an uncancelled click on {@code target}. */
    public void activationBehavior(Element target, Event event) {}

    /** True when a text field has focus, so hosts can suppress their own key bindings (e.g. inventory key). */
    public boolean wantsKeyboard() { return false; }
}
