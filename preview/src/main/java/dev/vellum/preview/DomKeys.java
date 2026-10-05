package dev.vellum.preview;

import java.awt.event.KeyEvent;
import java.util.Map;

import static java.awt.event.KeyEvent.*;
import static java.util.Map.entry;

/** Maps AWT key events to DOM {@code key} and {@code code} names and legacy {@code keyCode}s. */
final class DomKeys {
    /** A DOM key: {@code key} is null in the table when it is the typed character. */
    record Key(String key, String code, int keyCode) {}

    private static final Map<Integer, Key> KEYS = Map.ofEntries(
            entry(VK_ENTER, new Key("Enter", "Enter", 13)),
            entry(VK_ESCAPE, new Key("Escape", "Escape", 27)),
            entry(VK_BACK_SPACE, new Key("Backspace", "Backspace", 8)),
            entry(VK_TAB, new Key("Tab", "Tab", 9)),
            entry(VK_SPACE, new Key(" ", "Space", 32)),
            entry(VK_DELETE, new Key("Delete", "Delete", 46)),
            entry(VK_INSERT, new Key("Insert", "Insert", 45)),
            entry(VK_HOME, new Key("Home", "Home", 36)),
            entry(VK_END, new Key("End", "End", 35)),
            entry(VK_PAGE_UP, new Key("PageUp", "PageUp", 33)),
            entry(VK_PAGE_DOWN, new Key("PageDown", "PageDown", 34)),
            entry(VK_LEFT, new Key("ArrowLeft", "ArrowLeft", 37)),
            entry(VK_UP, new Key("ArrowUp", "ArrowUp", 38)),
            entry(VK_RIGHT, new Key("ArrowRight", "ArrowRight", 39)),
            entry(VK_DOWN, new Key("ArrowDown", "ArrowDown", 40)),
            entry(VK_CAPS_LOCK, new Key("CapsLock", "CapsLock", 20)),
            entry(VK_SHIFT, new Key("Shift", "Shift", 16)),
            entry(VK_CONTROL, new Key("Control", "Control", 17)),
            entry(VK_ALT, new Key("Alt", "Alt", 18)),
            entry(VK_META, new Key("Meta", "Meta", 91)),
            entry(VK_MINUS, new Key(null, "Minus", 189)),
            entry(VK_EQUALS, new Key(null, "Equal", 187)),
            entry(VK_OPEN_BRACKET, new Key(null, "BracketLeft", 219)),
            entry(VK_CLOSE_BRACKET, new Key(null, "BracketRight", 221)),
            entry(VK_BACK_SLASH, new Key(null, "Backslash", 220)),
            entry(VK_SEMICOLON, new Key(null, "Semicolon", 186)),
            entry(VK_QUOTE, new Key(null, "Quote", 222)),
            entry(VK_BACK_QUOTE, new Key(null, "Backquote", 192)),
            entry(VK_COMMA, new Key(null, "Comma", 188)),
            entry(VK_PERIOD, new Key(null, "Period", 190)),
            entry(VK_SLASH, new Key(null, "Slash", 191)));

    private DomKeys() {}

    static Key of(KeyEvent e) {
        int vk = e.getKeyCode();
        Key known = KEYS.get(vk);
        if (known != null) {
            String code = known.code();
            if (vk == VK_SHIFT || vk == VK_CONTROL || vk == VK_ALT || vk == VK_META) {
                code += e.getKeyLocation() == KEY_LOCATION_RIGHT ? "Right" : "Left";
            }
            return new Key(known.key() != null ? known.key() : typed(e), code, known.keyCode());
        }
        // AWT's codes for letters, digits, the numpad and F1-F12 equal the DOM's.
        if (vk >= VK_A && vk <= VK_Z) return new Key(typed(e), "Key" + (char) vk, vk);
        if (vk >= VK_0 && vk <= VK_9) return new Key(typed(e), "Digit" + (char) vk, vk);
        if (vk >= VK_NUMPAD0 && vk <= VK_NUMPAD9) return new Key(typed(e), "Numpad" + (vk - VK_NUMPAD0), vk);
        if (vk >= VK_F1 && vk <= VK_F12) return new Key("F" + (vk - VK_F1 + 1), "F" + (vk - VK_F1 + 1), vk);
        return new Key(typed(e), "Unidentified", vk);
    }

    /** The character the key produces; for letters with Ctrl/Cmd (where AWT reports a control character) the letter. */
    private static String typed(KeyEvent e) {
        char c = e.getKeyChar();
        if (c != CHAR_UNDEFINED && !Character.isISOControl(c)) return String.valueOf(c);
        int vk = e.getKeyCode();
        if (vk >= VK_A && vk <= VK_Z) return String.valueOf((char) (e.isShiftDown() ? vk : Character.toLowerCase(vk)));
        if (vk >= VK_0 && vk <= VK_9) return String.valueOf((char) vk);
        return "Unidentified";
    }
}
