package dev.vellum.mod.client;

import com.google.gson.JsonElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * HUD overlays: Vellum pages drawn over the HUD (above titles), sized to the GUI-scaled window.
 *
 * <pre>{@code
 * VellumHud.register(id("quest_tracker"), "mymod:vellum/tracker.html");   // client setup
 * VellumHud.show(id("quest_tracker"));
 * VellumHud.push(id("quest_tracker"), questJson);                          // vellum.data on the page
 *
 * // A toast with buttons the player can click while chat is open:
 * VellumHud.register(id("ask"), "mymod:vellum/ask.html", VellumHud.Input.WHEN_CHAT_OPEN);
 * VellumHud.show(id("ask")).onMessage("answer", value -> reply(value.getAsString()));
 *
 * // Or over the screens you choose:
 * VellumHud.register(id("ask"), "mymod:vellum/ask.html", screen -> screen instanceof ChatScreen || screen instanceof MyScreen);
 * }</pre>
 * A page hides itself with {@code vellum.close()}. Overlays are drawn in registration order. Render thread only.
 *
 * <p>An interactive overlay is drawn above the open screen and gets its pointer input first when the screen is one
 * it is interactive over. Otherwise (no screen, or another screen) it is a plain overlay: drawn in the HUD layer,
 * under any screen, without the pointer.
 */
public final class VellumHud {
    /** Over which screens an overlay takes pointer input; see {@link #register(Identifier, String, Predicate)}. */
    public enum Input {
        /** None: the page is something to look at (the default). It never sees the pointer. */
        NONE(screen -> false),
        /**
         * Over every screen, since any open screen frees the cursor (chat, an inventory, a Vellum screen, a menu). The
         * overlay covers full screens too; {@link #WHEN_CHAT_OPEN} or a predicate keeps it to the screens it is for.
         */
        WHEN_CURSOR_FREE(screen -> true),
        /** Over chat ({@link ChatScreen}, also chat in bed): press T and click. Under other screens. */
        WHEN_CHAT_OPEN(screen -> screen instanceof ChatScreen);

        private final Predicate<Screen> interactiveOver;

        Input(Predicate<Screen> interactiveOver) {
            this.interactiveOver = interactiveOver;
        }
    }

    private static final Map<Identifier, Overlay> OVERLAYS = new LinkedHashMap<>();

    private VellumHud() {}

    /** Registers a non-interactive overlay ({@link Input#NONE}). */
    public static void register(Identifier id, String url) {
        register(id, url, Input.NONE);
    }

    public static void register(Identifier id, String url, Input input) {
        register(id, url, input.interactiveOver);
    }

    /**
     * Registers an overlay that is interactive over the screens {@code interactiveOver} accepts (asked every frame and
     * for every pointer event, with the open screen). Over such a screen the overlay is drawn above it and gets the
     * pointer first: hover, {@code title} tooltips, clicks and the wheel go to its content. Where only the page's
     * background ({@code html}, {@code body}) or nothing of it is under the pointer, input falls through to the
     * screen; so does a wheel the page does not use. Keyboard input stays with the screen. With no screen, or one the
     * predicate rejects, the overlay is drawn in the HUD layer (under the screen) and does not see the pointer.
     */
    public static void register(Identifier id, String url, Predicate<Screen> interactiveOver) {
        OVERLAYS.put(id, new Overlay(url, interactiveOver));
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

    /** The driver of a shown overlay once it has drawn, or null. */
    static @Nullable DocumentDriver driver(Identifier id) {
        Overlay o = OVERLAYS.get(id);
        return o == null || !o.shown ? null : o.driver;
    }

    /**
     * The open screen when the overlay showing {@code driver}'s page has the pointer over it (it was drawn above the
     * screen with the pointer last frame), so real pointer input reaches the page; null otherwise.
     */
    static @Nullable Screen pointerScreen(DocumentDriver driver) {
        for (Overlay o : pointerOverlays()) if (o.driver == driver) return openScreen();
        return null;
    }

    /** The driver of the overlay a click at a GUI point over the open screen goes to, or null when none takes it. */
    static @Nullable DocumentDriver pointerDriverAt(double x, double y) {
        Overlay o = overlayAt(x, y);
        return o == null ? null : o.driver;
    }

    private static Overlay overlay(Identifier id) {
        Overlay o = OVERLAYS.get(id);
        if (o == null) throw new IllegalArgumentException("No Vellum HUD overlay registered as " + id);
        return o;
    }

    // ---- Loader hooks ----

    /** The HUD layer both loaders register above the title layer, without the overlays drawn above the open screen. */
    public static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (!hudVisible()) return;
        Screen screen = openScreen();
        for (Overlay o : OVERLAYS.values()) {
            if (o.shown && !o.aboveScreen(screen)) o.extract(g, false, 0);
        }
    }

    /**
     * After the open screen has been drawn (NeoForge {@code ScreenEvent.Render.Post} for the current screen, Fabric
     * {@code ScreenEvents.afterExtract}): the overlays interactive over it, above it, with the pointer.
     */
    public static void extractAboveScreen(GuiGraphicsExtractor g, float partialTick) {
        if (!hudVisible()) return;
        Screen screen = openScreen();
        boolean first = true;
        for (Overlay o : OVERLAYS.values()) {
            if (!o.shown || !o.aboveScreen(screen)) continue;
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

    /** The open screen while it is drawn (no loading overlay hides it): the one interactive overlays may be over. */
    private static @Nullable Screen openScreen() {
        Minecraft mc = Minecraft.getInstance();
        return mc.gui.overlay() == null ? mc.gui.screen() : null;
    }

    /** The overlays that have the pointer now (above the open screen), topmost (last drawn) first. */
    private static List<Overlay> pointerOverlays() {
        List<Overlay> list = new ArrayList<>();
        if (!hudVisible()) return list;
        Screen screen = openScreen();
        for (Overlay o : OVERLAYS.values()) {
            if (o.shown && o.tracking && o.driver != null && o.aboveScreen(screen)) list.addFirst(o);
        }
        return list;
    }

    /** The topmost interactive overlay with content (not just its page background) at a GUI point, or null. */
    private static @Nullable Overlay overlayAt(double x, double y) {
        for (Overlay o : pointerOverlays()) if (o.driver.contentAt(x, y) != null) return o;
        return null;
    }

    private static final class Overlay implements DocumentDriver.Owner {
        private final String url;
        private final Predicate<Screen> interactiveOver;
        private @Nullable DocumentDriver driver;
        private @Nullable String data;
        private boolean shown;
        private int width, height;
        /** Drawn with the pointer last frame (above a screen), so it is hovered and takes clicks. */
        private boolean tracking;
        /** Mouse buttons whose press went to this overlay (bit {@code 1 << button}); their release and drags do too. */
        private int buttons;

        Overlay(String url, Predicate<Screen> interactiveOver) {
            this.url = url;
            this.interactiveOver = interactiveOver;
        }

        /** Whether it is drawn above {@code screen} with the pointer (rather than in the HUD layer). */
        boolean aboveScreen(@Nullable Screen screen) {
            return screen != null && interactiveOver.test(screen);
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
         * Draws the page. With {@code pointer} (above a screen) the page follows the mouse as a screen's does, and a
         * tooltip it asks for is drawn right away: the screen's deferred pass, which draws tooltips, is over.
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
            tracking = true;
            Minecraft mc = Minecraft.getInstance();
            int x = (int) mc.mouseHandler.getScaledXPos(mc.getWindow()), y = (int) mc.mouseHandler.getScaledYPos(mc.getWindow());
            page.extract(g, x, y);
            if (page.requestedTooltip()) g.extractDeferredElements(x, y, partialTick);
        }

        /** The pointer went back to the game (the screen closed): hover and presses end. */
        private void leave() {
            tracking = false;
            buttons = 0;
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
