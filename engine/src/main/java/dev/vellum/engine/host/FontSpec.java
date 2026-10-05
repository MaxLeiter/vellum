package dev.vellum.engine.host;

import dev.vellum.engine.style.ComputedStyle;

import java.util.List;

/**
 * A font request: the family list from CSS, the size in px (8 is Minecraft's native size), bold and italic.
 * Letter spacing is applied by the engine, not the host.
 */
public record FontSpec(List<String> families, float size, boolean bold, boolean italic) {
    public static FontSpec of(ComputedStyle s) {
        return new FontSpec(s.fontFamily, s.fontSize, s.isBold(), s.fontItalic);
    }

    /** Scale relative to the native 8px em. */
    public float scale() {
        return size / ComputedStyle.DEFAULT_FONT_SIZE;
    }
}
