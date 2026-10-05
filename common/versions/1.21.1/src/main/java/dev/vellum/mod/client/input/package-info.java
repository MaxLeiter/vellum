/**
 * Input events for Minecraft 1.21.1, which passes input to screens as plain numbers (GLFW key codes, scancodes,
 * buttons and modifier bits). These records have the shape of 26.x's {@code net.minecraft.client.input} records, so
 * Vellum's input code, and the {@code DocumentDriver.onKey} handlers mods add, are the same on both versions. The
 * values are GLFW's: {@code KeyEvent.key()} is a GLFW key code ({@code GLFW_KEY_*}), mouse buttons are GLFW buttons
 * (0 left, 1 right, 2 middle), and modifiers are {@code GLFW_MOD_*} bits.
 */
package dev.vellum.mod.client.input;
