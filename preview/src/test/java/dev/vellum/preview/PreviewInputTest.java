package dev.vellum.preview;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Command-line options and key mapping. */
class PreviewInputTest {
    @Test
    void parsesOptions() {
        Preview.Options options = Preview.Options.parse("ui/menu.html", "--scale", "3", "--size", "320x200", "--snapshot", "out.png", "--frames", "5");
        assertEquals(new Preview.Options(Path.of("ui/menu.html"), false, 3, 320, 200, null, Path.of("out.png"), 5), options);
        assertEquals(new Preview.Options(null, true, 2, 427, 240, null, null, 1), Preview.Options.parse("--canvas-test"));
        assertThrows(IllegalArgumentException.class, () -> Preview.Options.parse());
        assertThrows(IllegalArgumentException.class, () -> Preview.Options.parse("a.html", "--size", "big"));
        assertThrows(IllegalArgumentException.class, () -> Preview.Options.parse("a.html", "--scale"));
        assertThrows(IllegalArgumentException.class, () -> Preview.Options.parse("a.html", "b.html"));
    }

    @Test
    void mapsKeysToDomNames() {
        assertEquals(new DomKeys.Key("a", "KeyA"), DomKeys.of(key(KeyEvent.VK_A, 'a', 0, KeyEvent.KEY_LOCATION_STANDARD)));
        assertEquals(new DomKeys.Key("A", "KeyA"), DomKeys.of(key(KeyEvent.VK_A, 'A', InputEvent.SHIFT_DOWN_MASK, KeyEvent.KEY_LOCATION_STANDARD)));
        // Ctrl+A types a control character; the DOM key is still "a".
        assertEquals(new DomKeys.Key("a", "KeyA"), DomKeys.of(key(KeyEvent.VK_A, '\u0001', InputEvent.CTRL_DOWN_MASK, KeyEvent.KEY_LOCATION_STANDARD)));
        assertEquals(new DomKeys.Key("Enter", "Enter"), DomKeys.of(key(KeyEvent.VK_ENTER, '\n', 0, KeyEvent.KEY_LOCATION_STANDARD)));
        assertEquals(new DomKeys.Key(" ", "Space"), DomKeys.of(key(KeyEvent.VK_SPACE, ' ', 0, KeyEvent.KEY_LOCATION_STANDARD)));
        assertEquals(new DomKeys.Key("Shift", "ShiftRight"),
                DomKeys.of(key(KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED, InputEvent.SHIFT_DOWN_MASK, KeyEvent.KEY_LOCATION_RIGHT)));
        assertEquals(new DomKeys.Key("/", "Slash"), DomKeys.of(key(KeyEvent.VK_SLASH, '/', 0, KeyEvent.KEY_LOCATION_STANDARD)));
        assertEquals(new DomKeys.Key("F5", "F5"), DomKeys.of(key(KeyEvent.VK_F5, KeyEvent.CHAR_UNDEFINED, 0, KeyEvent.KEY_LOCATION_STANDARD)));
    }

    private static KeyEvent key(int code, char c, int modifiers, int location) {
        return new KeyEvent(new JPanel(), KeyEvent.KEY_PRESSED, 0, modifiers, code, c, location);
    }
}
