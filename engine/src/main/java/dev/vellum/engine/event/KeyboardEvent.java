package dev.vellum.engine.event;

/**
 * keydown / keyup. {@code key} follows DOM naming ("a", "Enter", "ArrowLeft", "Escape", "Tab", "Backspace"...);
 * {@code code} is the physical key ("KeyA", "Digit1"...); {@code keyCode} is the host's native key code.
 */
public class KeyboardEvent extends Event {
    public final String key;
    public final String code;
    public final int keyCode;
    public final boolean repeat;
    public final Modifiers modifiers;

    public KeyboardEvent(String type, String key, String code, int keyCode, boolean repeat, Modifiers modifiers) {
        super(type, true, true);
        this.key = key;
        this.code = code;
        this.keyCode = keyCode;
        this.repeat = repeat;
        this.modifiers = modifiers;
    }
}
