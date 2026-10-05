package dev.vellum.engine.event;

/**
 * keydown / keyup. {@code key} follows DOM naming ("a", "Enter", "ArrowLeft", "Escape", "Tab", "Backspace"...);
 * {@code code} is the physical key ("KeyA", "Digit1"...); {@code keyCode} is the legacy code browsers report for
 * that physical key on a US layout, derived from {@code code} (0 when unknown), so it is the same in every host.
 */
public class KeyboardEvent extends Event {
    public final String key;
    public final String code;
    public final int keyCode;
    public final boolean repeat;
    public final Modifiers modifiers;

    public KeyboardEvent(String type, String key, String code, boolean repeat, Modifiers modifiers) {
        super(type, true, true);
        this.key = key;
        this.code = code;
        this.keyCode = keyCode(code);
        this.repeat = repeat;
        this.modifiers = modifiers;
    }

    /** The legacy {@code keyCode} of a {@code code}. */
    static int keyCode(String code) {
        if (code.length() == 4 && code.startsWith("Key") && code.charAt(3) >= 'A' && code.charAt(3) <= 'Z') {
            return code.charAt(3);
        }
        int digit = code.startsWith("Digit") ? number(code, 5) : -1;
        if (digit >= 0 && digit <= 9) return '0' + digit;
        int numpad = code.startsWith("Numpad") ? number(code, 6) : -1;
        if (numpad >= 0 && numpad <= 9) return 96 + numpad;
        int function = code.startsWith("F") ? number(code, 1) : -1;
        if (function >= 1 && function <= 12) return 111 + function;
        return switch (code) {
            case "Backspace" -> 8;
            case "Tab" -> 9;
            case "Enter", "NumpadEnter" -> 13;
            case "ShiftLeft", "ShiftRight" -> 16;
            case "ControlLeft", "ControlRight" -> 17;
            case "AltLeft", "AltRight" -> 18;
            case "CapsLock" -> 20;
            case "Escape" -> 27;
            case "Space" -> 32;
            case "PageUp" -> 33;
            case "PageDown" -> 34;
            case "End" -> 35;
            case "Home" -> 36;
            case "ArrowLeft" -> 37;
            case "ArrowUp" -> 38;
            case "ArrowRight" -> 39;
            case "ArrowDown" -> 40;
            case "Insert" -> 45;
            case "Delete" -> 46;
            case "MetaLeft" -> 91;
            case "MetaRight" -> 92;
            case "Semicolon" -> 186;
            case "Equal" -> 187;
            case "Comma" -> 188;
            case "Minus" -> 189;
            case "Period" -> 190;
            case "Slash" -> 191;
            case "Backquote" -> 192;
            case "BracketLeft" -> 219;
            case "Backslash" -> 220;
            case "BracketRight" -> 221;
            case "Quote" -> 222;
            default -> 0;
        };
    }

    /** The one- or two-digit number that makes up {@code s} from {@code from} on, or -1 when that is not one. */
    private static int number(String s, int from) {
        if (from >= s.length() || s.length() - from > 2) return -1;
        int n = 0;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return -1;
            n = n * 10 + c - '0';
        }
        return n;
    }
}
