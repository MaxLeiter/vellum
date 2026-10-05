package dev.vellum.mod.client;

import com.google.gson.JsonElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HUD overlays: Vellum pages drawn over the HUD (above titles), sized to the GUI-scaled window.
 *
 * <pre>{@code
 * VellumHud.register(id("quest_tracker"), "mymod:vellum/tracker.html");   // client setup
 * VellumHud.show(id("quest_tracker"));
 * VellumHud.push(id("quest_tracker"), questJson);                          // vellum.data on the page
 *
 * // A toast with buttons the player can click whenever the cursor is free (chat, inventories, any screen):
 * VellumHud.register(id("ask"), "mymod:vellum/ask.html", VellumHud.Input.WHEN_CURSOR_FREE);
 * VellumHud.show(id("ask")).onMessage("answer", value -> reply(value.getAsString()));
 * }</pre>
 * A page hides itself with {@code vellum.close()}. Overlays are drawn in registration order. Render thread only.
 */
public final class VellumHud {
    /** Whether an overlay takes pointer input. */
    public enum Input {
        /** None: the page is something to look at (the default). It never sees the pointer. */
        NONE,
        /**
         * While a screen is open, so the cursor is free (chat, an inventory, a Vellum screen, any other screen), the
         * overlay is drawn above that screen and gets the pointer first: hover, {@code title} tooltips, clicks and
         * the wheel go to its content. Where only the page's background ({@code html}, {@code body}) or nothing of it
         * is under the pointer, input falls through to the screen; so does a wheel the page does not use. Without
         * a screen the overlay is drawn in the HUD as with {@link #NONE}. Keyboard input stays with the screen.
         */
        WHEN_CURSOR_FREE
    }

    private static final Map<Identifier, Overlay> OVERLAYS = new LinkedHashMap<>();

    private VellumHud() {}

    /** Registers a non-interactive overlay ({@link Input#NONE}). */
    public static void register(Identifier id, String url) {
        register(id, url, Input.NONE);
    }

    public static void register(Identifier id, String url, Input input) {
        OVERLAYS.put(id, new Overlay(url, input));
    }

    /**
     * Shows the overlay and returns its page's driver, for {@code onMessage} and {@code onClose} handlers (which last
     * until it is hidden: showing it again makes a new driver). Showing a shown overlay returns its current driver.
     */
    public static DocumentDriver show(Identifier id) {
        return overlay(id).show();
    }

    /** Hides the overlay and discards its document (its data is kept for the next {@link #show}). */
    public static void hide(Identifier id) {
        overlay(id).hide();
    }

    public static boolean isShown(Identifier id) {
        return overlay(id).shown;
    }

    /** Replaces the overlay's {@code vellum.data}; it is kept while the overlay is hidden. */
    public static void push(Identifier id, JsonElement data) {
        Overlay o = overlay(id);
        o.data = data.toString();
        if (o.driver != null) o.driver.pushData(o.data);
    }

    private static Overlay overlay(Identifier id) {
        Overlay o = OVERLAYS.get(id);
        if (o == null) throw new IllegalArgumentException("No Vellum HUD overlay registered as " + id);
        return o;
    }

    // ---- Loader hooks ----

    /** The HUD layer both loaders register above the title layer. Interactive overlays wait for the screen while one is open. */
    public static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (!hudVisible()) return;
        boolean cursorFree = cursorFree();
        for (Overlay o : OVERLAYS.values()) {
            if (o.shown && !(o.interactive() && cursorFree)) o.extract(g, false, 0);
        }
    }

    /**
     * After the open screen has been drawn (NeoForge {@code ScreenEvent.Render.Post} for the current screen, Fabric
     * {@code ScreenEvents.afterExtract}): interactive overlays, above the screen, with the pointer.
     */
    public static void extractAboveScreen(GuiGraphicsExtractor g, float partialTick) {
        if (!hudVisible() || !cursorFree()) return;
        boolean first = true;
        for (Overlay o : OVERLAYS.values()) {
            if (!o.shown || !o.interactive()) continue;
            if (first) g.nextStratum(); // above everything the screen drew, its tooltips included
            first = false;
            o.extract(g, true, partialTick);
        }
    }

    /** A button pressed over the open screen. True when an interactive overlay took it (the screen must not get it). */
    public static boolean mouseClicked(MouseButtonEvent e) {
        Overlay o = overlayAt(e.x(), e.y());
        if (o == null) return false;
        o.driver.mouseMoved(e.x(), e.y());
        o.driver.mouseClicked(e);
        o.buttons |= 1 << e.button();
        return true;
    }

    /** A button released over the open screen. True when its press went to an overlay, which gets the release too. */
    public static boolean mouseReleased(MouseButtonEvent e) {
        for (Overlay o : pointerOverlays()) {
            if ((o.buttons & 1 << e.button()) == 0) continue;
            o.buttons &= ~(1 << e.button());
            o.driver.mouseReleased(e);
            return true;
        }
        return false;
    }

    /** A drag over the open screen. True while an overlay holds a press (its page follows the pointer, the screen must not drag). */
    public static boolean mouseDragged(MouseButtonEvent e) {
        for (Overlay o : pointerOverlays()) {
            if (o.buttons == 0) continue;
            o.driver.mouseMoved(e.x(), e.y());
            return true;
        }
        return false;
    }

    /** The wheel over the open screen. True when the overlay under the pointer used it (scrolled, or its page cancelled it). */
    public static boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        Overlay o = overlayAt(x, y);
        return o != null && o.driver.mouseScrolled(x, y, scrollX, scrollY);
    }

    // ---- Internals ----

    private static boolean hudVisible() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && !mc.gui.hud.isHidden();
    }

    /** A screen is open (and drawn), so the cursor is free and interactive overlays take the pointer. */
    private static boolean cursorFree() {
        Minecraft mc = Minecraft.getInstance();
        return mc.gui.screen() != null && mc.gui.overlay() == null;
    }

    /** The interactive overlays that have the pointer now, topmost (last drawn) first. */
    private static List<Overlay> pointerOverlays() {
        List<Overlay> list = new ArrayList<>();
        if (!hudVisible() || !cursorFree()) return list;
        for (Overlay o : OVERLAYS.values()) if (o.shown && o.tracking && o.driver != null) list.addFirst(o);
        return list;
    }

    /** The topmost interactive overlay with content (not just its page background) at a GUI point, or null. */
    private static @Nullable Overlay overlayAt(double x, double y) {
        for (Overlay o : pointerOverlays()) if (o.driver.contentAt(x, y) != null) return o;
        return null;
    }

    private static final class Overlay implements DocumentDriver.Owner {
        private final String url;
        private final Input input;
        private @Nullable DocumentDriver driver;
        private @Nullable String data;
        private boolean shown;
        private int width, height;
        /** Drawn with the pointer last frame (above a screen), so it is hovered and takes clicks. */
        private boolean tracking;
        private double pointerX = Double.NaN, pointerY = Double.NaN;
        /** Mouse buttons whose press went to this overlay (bit {@code 1 << button}); their release and drags do too. */
        private int buttons;

        Overlay(String url, Input input) {
            this.url = url;
            this.input = input;
        }

        boolean interactive() {
            return input == Input.WHEN_CURSOR_FREE;
        }

        DocumentDriver show() {
            shown = true;
            if (driver == null) {
                driver = new DocumentDriver(this, url, null, -1);
                if (data != null) driver.pushData(data);
                width = -1; // loads on the first draw
            }
            return driver;
        }

        /**
         * Draws the page. With {@code pointer} (above a screen) the page follows the mouse, and a tooltip it asks for
         * is drawn right away: the screen's deferred pass, which draws tooltips, is over.
         */
        void extract(GuiGraphicsExtractor g, boolean pointer, float partialTick) {
            DocumentDriver page = show();
            if (g.guiWidth() != width || g.guiHeight() != height) {
                width = g.guiWidth();
                height = g.guiHeight();
                page.resize(width, height);
            }
            if (!pointer) {
                if (tracking) leave();
                page.extract(g, -1, -1);
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            double x = mc.mouseHandler.getScaledXPos(mc.getWindow()), y = mc.mouseHandler.getScaledYPos(mc.getWindow());
            if (!tracking || x != pointerX || y != pointerY) page.mouseMoved(x, y);
            tracking = true;
            pointerX = x;
            pointerY = y;
            page.extract(g, (int) x, (int) y);
            if (page.requestedTooltip()) g.extractDeferredElements((int) x, (int) y, partialTick);
        }

        /** The pointer went back to the game (the screen closed): hover and presses end. */
        private void leave() {
            tracking = false;
            buttons = 0;
            pointerX = pointerY = Double.NaN;
            if (driver != null) driver.mouseLeave();
        }

        void hide() {
            shown = false;
            tracking = false;
            buttons = 0;
            if (driver != null) driver.close();
            driver = null;
        }

        @Override
        public void closeDocument() {
            hide();
        }
    }
}
