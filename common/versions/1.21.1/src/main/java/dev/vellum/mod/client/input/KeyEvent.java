package dev.vellum.mod.client.input;

import org.lwjgl.glfw.GLFW;

/**
 * A key press or release on Minecraft 1.21.1, as a screen gets it ({@code keyPressed(key, scancode, modifiers)}).
 *
 * @param key       the GLFW key code ({@code GLFW_KEY_*}): the key's position on a US keyboard
 * @param scancode  the platform's scancode
 * @param modifiers {@code GLFW_MOD_*} bits
 */
public record KeyEvent(int key, int scancode, int modifiers) {
    public boolean isEscape() {
        return key == GLFW.GLFW_KEY_ESCAPE;
    }

    /** Enter or the keypad's Enter. */
    public boolean isConfirmation() {
        return key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER;
    }

    /** Enter, the keypad's Enter or Space. */
    public boolean isSelection() {
        return isConfirmation() || key == GLFW.GLFW_KEY_SPACE;
    }

    public boolean hasShiftDown() {
        return (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
    }

    public boolean hasControlDown() {
        return (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
    }

    public boolean hasAltDown() {
        return (modifiers & GLFW.GLFW_MOD_ALT) != 0;
    }
}
