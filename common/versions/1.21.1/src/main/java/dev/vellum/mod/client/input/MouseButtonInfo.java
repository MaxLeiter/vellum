package dev.vellum.mod.client.input;

/**
 * A mouse button and the modifiers held, on Minecraft 1.21.1.
 *
 * @param button    the GLFW button: 0 left, 1 right, 2 middle
 * @param modifiers {@code GLFW_MOD_*} bits
 */
public record MouseButtonInfo(int button, int modifiers) {}
