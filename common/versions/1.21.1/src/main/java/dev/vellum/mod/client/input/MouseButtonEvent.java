package dev.vellum.mod.client.input;

import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** A mouse button pressed or released at a GUI point, on Minecraft 1.21.1. */
public record MouseButtonEvent(double x, double y, MouseButtonInfo buttonInfo) {
    /** {@code button} at (x, y) with the modifiers held now: 1.21.1's screens get none with a click. */
    public static MouseButtonEvent now(double x, double y, int button) {
        long window = Minecraft.getInstance().getWindow().getWindow();
        int modifiers = 0;
        if (down(window, GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT)) modifiers |= GLFW.GLFW_MOD_SHIFT;
        if (down(window, GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL)) modifiers |= GLFW.GLFW_MOD_CONTROL;
        if (down(window, GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT)) modifiers |= GLFW.GLFW_MOD_ALT;
        if (down(window, GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER)) modifiers |= GLFW.GLFW_MOD_SUPER;
        return new MouseButtonEvent(x, y, new MouseButtonInfo(button, modifiers));
    }

    private static boolean down(long window, int left, int right) {
        return GLFW.glfwGetKey(window, left) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(window, right) == GLFW.GLFW_PRESS;
    }

    /** The GLFW button: 0 left, 1 right, 2 middle. */
    public int button() {
        return buttonInfo.button();
    }

    /** {@code GLFW_MOD_*} bits. */
    public int modifiers() {
        return buttonInfo.modifiers();
    }
}
