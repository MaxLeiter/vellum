package dev.vellum.mod.client;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;

/**
 * The dev autopilot's pointer ({@link DevAutopilot}, and the tour it runs): for the whole run the pointer is where the
 * autopilot put it, wherever the real mouse is and whatever it does. Two things would move it otherwise:
 *
 * <ul>
 * <li>The real mouse. Its moves, clicks and wheel reach Minecraft's mouse handler as a player's do, so a hand on the
 * mouse, or the cursor just crossing the window, moves the pointer off what a check or the tour put it on. These are
 * dropped where the platform delivers them ({@link McClient#ignoreRealMouse}).
 * <li>On 26.3, Minecraft resyncing the handler to the system pointer whenever a screen opens or closes and when the GUI
 * resizes: a page opened under a pointer the autopilot left resting would open under the system's instead. This is
 * undone. The loaders call {@link #beforeScreenChange} as a screen inits or closes, while the handler still has the
 * autopilot's position, and {@link #hold} puts it back after each step the autopilot runs (before the frame that would
 * draw the page with the system's) and once a tick (for screens the server opens).
 * </ul>
 *
 * Input from code ({@link VellumAutomation}, {@link TourPointer}) goes through the handler as before. Nothing here
 * runs unless the autopilot is on. Render thread only.
 */
public final class DevPointer {
    private static boolean owned;
    /** Whether a screen change may have moved the handler to the system pointer since {@link #x}, {@link #y} were taken. */
    private static boolean changed;
    /** Where the autopilot had the pointer before that change, in screen px. */
    private static double x, y;

    private DevPointer() {}

    /** Takes the pointer from the real mouse for the rest of the run, and rests it at the window's centre. */
    static void own() {
        owned = true;
        McClient.ignoreRealMouse();
        Window w = Minecraft.getInstance().getWindow();
        McClient.moveMouse(w.getScreenWidth() / 2.0, w.getScreenHeight() / 2.0);
    }

    /**
     * Loader hook (NeoForge {@code ScreenEvent.Init.Pre} and {@code Closing}, Fabric {@code ScreenEvents.BEFORE_INIT}
     * and {@code remove}): a screen is about to open, resize or close, after which Minecraft may move the handler to the
     * system pointer.
     */
    public static void beforeScreenChange() {
        if (!owned || changed) return;
        MouseHandler mouse = Minecraft.getInstance().mouseHandler;
        x = mouse.xpos();
        y = mouse.ypos();
        changed = true;
    }

    /** Puts the pointer back where it was before the last screen change, if one moved it. */
    static void hold() {
        if (!changed) return;
        changed = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.mouseHandler.isMouseGrabbed()) return; // the game has the mouse, and gives it back at the window's centre
        if (McClient.screen() == null) {
            // Where the game leaves it on taking the mouse, which it can't while the window doesn't have focus.
            Window w = mc.getWindow();
            x = w.getScreenWidth() / 2.0;
            y = w.getScreenHeight() / 2.0;
        }
        if (mc.mouseHandler.xpos() != x || mc.mouseHandler.ypos() != y) McClient.moveMouse(x, y);
    }
}
