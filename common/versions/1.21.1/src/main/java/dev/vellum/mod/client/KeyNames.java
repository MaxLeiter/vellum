package dev.vellum.mod.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.vellum.engine.event.Modifiers;
import dev.vellum.mod.client.input.KeyEvent;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * Minecraft's input to DOM names, on 1.21.1 (GLFW). {@code code} comes from the GLFW key code, which names a key by
 * its place on a US keyboard ({@code KeyA}, {@code Digit1}, {@code ArrowLeft}); {@code key} from what GLFW says the
 * key types on the current layout ({@code a}, {@code A} with shift, {@code Enter}, {@code Escape}). Each Minecraft
 * version has its own KeyNames.
 */
final class KeyNames {
    /** The GLFW key codes, from space to the last one. */
    private static final int FIRST_KEY = GLFW.GLFW_KEY_SPACE, LAST_KEY = GLFW.GLFW_KEY_LAST;

    private KeyNames() {}

    /** The DOM {@code code} of a key event: the physical key. */
    static String code(KeyEvent e) {
        return code(e.key());
    }

    /** The DOM {@code key} of a key event: what the key means on the current layout, with shift. */
    static String key(KeyEvent e) {
        return key(e.key(), e.scancode(), e.hasShiftDown());
    }

    static String code(int key) {
        if (key >= InputConstants.KEY_A && key <= InputConstants.KEY_Z) return "Key" + (char) ('A' + key - InputConstants.KEY_A);
        if (key >= InputConstants.KEY_0 && key <= InputConstants.KEY_9) return "Digit" + (key - InputConstants.KEY_0);
        if (key >= InputConstants.KEY_F1 && key <= InputConstants.KEY_F12) return "F" + (key - InputConstants.KEY_F1 + 1);
        return switch (key) {
            case InputConstants.KEY_RETURN -> "Enter";
            case InputConstants.KEY_NUMPADENTER -> "NumpadEnter";
            case InputConstants.KEY_ESCAPE -> "Escape";
            case InputConstants.KEY_BACKSPACE -> "Backspace";
            case InputConstants.KEY_TAB -> "Tab";
            case InputConstants.KEY_SPACE -> "Space";
            case InputConstants.KEY_MINUS -> "Minus";
            case InputConstants.KEY_EQUALS -> "Equal";
            case InputConstants.KEY_LBRACKET -> "BracketLeft";
            case InputConstants.KEY_RBRACKET -> "BracketRight";
            case InputConstants.KEY_BACKSLASH -> "Backslash";
            case InputConstants.KEY_SEMICOLON -> "Semicolon";
            case InputConstants.KEY_APOSTROPHE -> "Quote";
            case InputConstants.KEY_GRAVE -> "Backquote";
            case InputConstants.KEY_COMMA -> "Comma";
            case InputConstants.KEY_PERIOD -> "Period";
            case InputConstants.KEY_SLASH -> "Slash";
            case InputConstants.KEY_LEFT -> "ArrowLeft";
            case InputConstants.KEY_RIGHT -> "ArrowRight";
            case InputConstants.KEY_UP -> "ArrowUp";
            case InputConstants.KEY_DOWN -> "ArrowDown";
            case InputConstants.KEY_HOME -> "Home";
            case InputConstants.KEY_END -> "End";
            case InputConstants.KEY_PAGEUP -> "PageUp";
            case InputConstants.KEY_PAGEDOWN -> "PageDown";
            case InputConstants.KEY_DELETE -> "Delete";
            case InputConstants.KEY_INSERT -> "Insert";
            case InputConstants.KEY_LSHIFT -> "ShiftLeft";
            case InputConstants.KEY_RSHIFT -> "ShiftRight";
            case InputConstants.KEY_LCONTROL -> "ControlLeft";
            case InputConstants.KEY_RCONTROL -> "ControlRight";
            case InputConstants.KEY_LALT -> "AltLeft";
            case InputConstants.KEY_RALT -> "AltRight";
            case InputConstants.KEY_LWIN -> "MetaLeft";
            case InputConstants.KEY_RWIN -> "MetaRight";
            default -> "Unidentified";
        };
    }

    static String key(int key, int scancode, boolean shift) {
        String named = switch (key) {
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> "Enter";
            case InputConstants.KEY_ESCAPE -> "Escape";
            case InputConstants.KEY_BACKSPACE -> "Backspace";
            case InputConstants.KEY_TAB -> "Tab";
            case InputConstants.KEY_SPACE -> " ";
            case InputConstants.KEY_LEFT -> "ArrowLeft";
            case InputConstants.KEY_RIGHT -> "ArrowRight";
            case InputConstants.KEY_UP -> "ArrowUp";
            case InputConstants.KEY_DOWN -> "ArrowDown";
            case InputConstants.KEY_HOME -> "Home";
            case InputConstants.KEY_END -> "End";
            case InputConstants.KEY_PAGEUP -> "PageUp";
            case InputConstants.KEY_PAGEDOWN -> "PageDown";
            case InputConstants.KEY_DELETE -> "Delete";
            case InputConstants.KEY_INSERT -> "Insert";
            case InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT -> "Shift";
            case InputConstants.KEY_LCONTROL, InputConstants.KEY_RCONTROL -> "Control";
            case InputConstants.KEY_LALT, InputConstants.KEY_RALT -> "Alt";
            case InputConstants.KEY_LWIN, InputConstants.KEY_RWIN -> "Meta";
            default -> null;
        };
        if (named != null) return named;
        if (key >= InputConstants.KEY_F1 && key <= InputConstants.KEY_F12) return code(key);
        // Printable keys: GLFW names the character the key types on the current layout, unshifted.
        String typed = GLFW.glfwGetKeyName(key, scancode);
        if (typed != null && !typed.isEmpty()) {
            int cp = typed.codePointAt(0);
            if (Character.charCount(cp) == typed.length() && !Character.isISOControl(cp)) return shift ? typed.toUpperCase() : typed;
        }
        return "Unidentified";
    }

    /**
     * The key event that gives DOM key {@code key} on the current keyboard layout: the inverse of {@link #key}, the
     * first GLFW key whose key, unshifted or with shift, is {@code key}. Null when no key gives it.
     */
    static @Nullable KeyEvent event(String key) {
        for (int code = FIRST_KEY; code <= LAST_KEY; code++) {
            int scancode = GLFW.glfwGetKeyScancode(code);
            if (scancode < 0) continue; // no such key on this keyboard
            if (key.equals(key(code, scancode, false))) return new KeyEvent(code, scancode, 0);
            if (key.equals(key(code, scancode, true))) return new KeyEvent(code, scancode, GLFW.GLFW_MOD_SHIFT);
        }
        return null;
    }

    /** Escape, with or without shift, as the keyboard sends it. */
    static KeyEvent escape(boolean shift) {
        return new KeyEvent(InputConstants.KEY_ESCAPE, GLFW.glfwGetKeyScancode(InputConstants.KEY_ESCAPE), shift ? GLFW.GLFW_MOD_SHIFT : 0);
    }

    static Modifiers modifiers(int mods) {
        return new Modifiers((mods & GLFW.GLFW_MOD_SHIFT) != 0, (mods & GLFW.GLFW_MOD_CONTROL) != 0,
                (mods & GLFW.GLFW_MOD_ALT) != 0, (mods & GLFW.GLFW_MOD_SUPER) != 0);
    }

    /** Modifier state now, for input that carries none (pointer moves, scrolling). */
    static Modifiers current() {
        return new Modifiers(isDown(InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT), isDown(InputConstants.KEY_LCONTROL, InputConstants.KEY_RCONTROL),
                isDown(InputConstants.KEY_LALT, InputConstants.KEY_RALT), isDown(InputConstants.KEY_LWIN, InputConstants.KEY_RWIN));
    }

    private static boolean isDown(int left, int right) {
        long window = Minecraft.getInstance().getWindow().getWindow();
        return InputConstants.isKeyDown(window, left) || InputConstants.isKeyDown(window, right);
    }

    /** GLFW mouse buttons (0 left, 1 right, 2 middle) to DOM buttons (0 left, 1 middle, 2 right); others pass through. */
    static int button(int glfwButton) {
        return switch (glfwButton) {
            case GLFW.GLFW_MOUSE_BUTTON_RIGHT -> 2;
            case GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> 1;
            default -> glfwButton;
        };
    }

    /** DOM buttons (0 left, 1 middle, 2 right) to GLFW's. */
    static int mcButton(int domButton) {
        return button(domButton); // the same swap both ways
    }
}
