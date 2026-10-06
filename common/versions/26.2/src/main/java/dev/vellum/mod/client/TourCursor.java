package dev.vellum.mod.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The dev tour's in-game cursor ({@link DevTour}), which is off on 26.2: it hides and reads the system cursor through
 * SDL, which only 26.3 has. The loaders' hooks are here so they compile, and never run.
 */
public final class TourCursor {
    public static final boolean ENABLED = false;

    private TourCursor() {}

    public static void extractAboveScreen(GuiGraphicsExtractor g) {}

    public static void extractHud(GuiGraphicsExtractor g, DeltaTracker delta) {}
}
