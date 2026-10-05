package dev.vellum.mod.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.vellum.engine.event.Modifiers;

/**
 * SDL key events to DOM {@code KeyboardEvent} names. {@code code} comes from the scancode (the physical key:
 * {@code KeyA}, {@code Digit1}, {@code ArrowLeft}); {@code key} from the keycode (the layout's character: {@code a},
 * {@code A} with shift, {@code Enter}, {@code Escape}).
 */
final class KeyNames {
    private KeyNames() {}

    static String code(int scancode) {
        if (scancode >= InputConstants.KEY_A && scancode <= InputConstants.KEY_Z) return "Key" + (char) ('A' + scancode - InputConstants.KEY_A);
        if (scancode >= InputConstants.KEY_1 && scancode <= InputConstants.KEY_9) return "Digit" + (scancode - InputConstants.KEY_1 + 1);
        if (scancode >= InputConstants.KEY_F1 && scancode <= InputConstants.KEY_F12) return "F" + (scancode - InputConstants.KEY_F1 + 1);
        return switch (scancode) {
            case InputConstants.KEY_0 -> "Digit0";
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
            case InputConstants.KEY_LGUI -> "MetaLeft";
            case InputConstants.KEY_RGUI -> "MetaRight";
            default -> "Unidentified";
        };
    }

    static String key(int scancode, int keycode, boolean shift) {
        String named = switch (scancode) {
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
            case InputConstants.KEY_LGUI, InputConstants.KEY_RGUI -> "Meta";
            default -> null;
        };
        if (named != null) return named;
        if (scancode >= InputConstants.KEY_F1 && scancode <= InputConstants.KEY_F12) return code(scancode);
        // Printable keys: SDL keycodes are the unshifted character of the current layout.
        if (keycode > 0 && keycode < 0x40000000 && Character.isValidCodePoint(keycode) && !Character.isISOControl(keycode)) {
            String ch = Character.toString(keycode);
            return shift ? ch.toUpperCase() : ch;
        }
        return "Unidentified";
    }

    static Modifiers modifiers(int mods) {
        return new Modifiers((mods & InputConstants.MOD_SHIFT) != 0, (mods & InputConstants.MOD_CONTROL) != 0,
                (mods & InputConstants.MOD_ALT) != 0, (mods & InputConstants.MOD_SUPER) != 0);
    }

    /** Modifier state now, for input that carries none (pointer moves, scrolling). */
    static Modifiers current() {
        return new Modifiers(isDown(InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT), isDown(InputConstants.KEY_LCONTROL, InputConstants.KEY_RCONTROL),
                isDown(InputConstants.KEY_LALT, InputConstants.KEY_RALT), isDown(InputConstants.KEY_LGUI, InputConstants.KEY_RGUI));
    }

    private static boolean isDown(int left, int right) {
        return InputConstants.isKeyDown(left) || InputConstants.isKeyDown(right);
    }

    /** SDL mouse buttons (1 left, 2 middle, 3 right) to DOM buttons (0, 1, 2); others pass through shifted. */
    static int button(int sdlButton) {
        return sdlButton - 1;
    }
}
