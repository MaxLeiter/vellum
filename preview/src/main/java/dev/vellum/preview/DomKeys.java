package dev.vellum.preview;

import java.awt.event.KeyEvent;
import java.util.Locale;
import java.util.Map;

import static java.awt.event.KeyEvent.*;
import static java.util.Map.entry;

/** Maps AWT key events to DOM {@code key} and {@code code} names. */
final class DomKeys {
    /** A DOM key: {@code key} is null in the table when it is the typed character. */
    record Key(String key, String code) {}

    private static final Map<Integer, Key> KEYS = Map.ofEntries(
            entry(VK_ENTER, new Key("Enter", "Enter")),
            entry(VK_ESCAPE, new Key("Escape", "Escape")),
            entry(VK_BACK_SPACE, new Key("Backspace", "Backspace")),
            entry(VK_TAB, new Key("Tab", "Tab")),
            entry(VK_SPACE, new Key(" ", "Space")),
            entry(VK_DELETE, new Key("Delete", "Delete")),
            entry(VK_INSERT, new Key("Insert", "Insert")),
            entry(VK_HOME, new Key("Home", "Home")),
            entry(VK_END, new Key("End", "End")),
            entry(VK_PAGE_UP, new Key("PageUp", "PageUp")),
            entry(VK_PAGE_DOWN, new Key("PageDown", "PageDown")),
            entry(VK_LEFT, new Key("ArrowLeft", "ArrowLeft")),
            entry(VK_UP, new Key("ArrowUp", "ArrowUp")),
            entry(VK_RIGHT, new Key("ArrowRight", "ArrowRight")),
            entry(VK_DOWN, new Key("ArrowDown", "ArrowDown")),
            entry(VK_CAPS_LOCK, new Key("CapsLock", "CapsLock")),
            entry(VK_SHIFT, new Key("Shift", "Shift")),
            entry(VK_CONTROL, new Key("Control", "Control")),
            entry(VK_ALT, new Key("Alt", "Alt")),
            entry(VK_META, new Key("Meta", "Meta")),
            entry(VK_MINUS, new Key(null, "Minus")),
            entry(VK_EQUALS, new Key(null, "Equal")),
            entry(VK_OPEN_BRACKET, new Key(null, "BracketLeft")),
            entry(VK_CLOSE_BRACKET, new Key(null, "BracketRight")),
            entry(VK_BACK_SLASH, new Key(null, "Backslash")),
            entry(VK_SEMICOLON, new Key(null, "Semicolon")),
            entry(VK_QUOTE, new Key(null, "Quote")),
            entry(VK_BACK_QUOTE, new Key(null, "Backquote")),
            entry(VK_COMMA, new Key(null, "Comma")),
            entry(VK_PERIOD, new Key(null, "Period")),
            entry(VK_SLASH, new Key(null, "Slash")));

    private DomKeys() {}

    static Key of(KeyEvent e) {
        int vk = e.getKeyCode();
        Key known = KEYS.get(vk);
        if (known != null) {
            String code = known.code();
            if (vk == VK_SHIFT || vk == VK_CONTROL || vk == VK_ALT || vk == VK_META) {
                code += e.getKeyLocation() == KEY_LOCATION_RIGHT ? "Right" : "Left";
            }
            return new Key(known.key() != null ? known.key() : typed(e), code);
        }
        if (vk >= VK_A && vk <= VK_Z) return new Key(typed(e), "Key" + (char) vk);
        if (vk >= VK_0 && vk <= VK_9) return new Key(typed(e), "Digit" + (char) vk);
        if (vk >= VK_NUMPAD0 && vk <= VK_NUMPAD9) return new Key(typed(e), "Numpad" + (vk - VK_NUMPAD0));
        if (vk >= VK_F1 && vk <= VK_F12) return new Key("F" + (vk - VK_F1 + 1), "F" + (vk - VK_F1 + 1));
        return new Key(typed(e), "Unidentified");
    }

    /**
     * The {@code code} a DOM key name ({@code "Enter"}, {@code "a"}, {@code "F5"}) comes from on a US keyboard (the
     * left one of modifier pairs), for input that names keys rather than pressing them; {@code "Unidentified"} when
     * no key in the table gives it.
     */
    static String codeOf(String key) {
        for (Key k : KEYS.values()) {
            if (!key.equals(k.key())) continue;
            boolean modifier = switch (key) {
                case "Shift", "Control", "Alt", "Meta" -> true;
                default -> false;
            };
            return modifier ? k.code() + "Left" : k.code();
        }
        if (key.length() == 1 && Character.isLetter(key.charAt(0)) && key.charAt(0) < 128) {
            return "Key" + key.toUpperCase(Locale.ROOT);
        }
        if (key.length() == 1 && Character.isDigit(key.charAt(0))) return "Digit" + key;
        if (key.matches("F([1-9]|1[0-2])")) return key;
        return "Unidentified";
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
