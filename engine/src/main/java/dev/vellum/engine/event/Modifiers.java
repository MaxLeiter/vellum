package dev.vellum.engine.event;

/** Modifier key state at the time of an input event. */
public record Modifiers(boolean shift, boolean ctrl, boolean alt, boolean meta) {
    public static final Modifiers NONE = new Modifiers(false, false, false, false);

    /** Ctrl on Windows/Linux, Cmd on macOS: the modifier for copy, paste, select-all. */
    public boolean shortcut() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac") ? meta : ctrl;
    }
}
