package dev.vellum.mod.client;

import com.google.gson.JsonElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HUD overlays: non-interactive Vellum pages drawn over the HUD (above titles), sized to the GUI-scaled window.
 *
 * <pre>{@code
 * VellumHud.register(id("quest_tracker"), "mymod:vellum/tracker.html");   // client setup
 * VellumHud.show(id("quest_tracker"));
 * VellumHud.push(id("quest_tracker"), questJson);                          // vellum.data on the page
 * }</pre>
 * A page hides itself with {@code vellum.close()}. Overlays are drawn in registration order. Render thread only.
 */
public final class VellumHud {
    private static final Map<Identifier, Overlay> OVERLAYS = new LinkedHashMap<>();

    private VellumHud() {}

    public static void register(Identifier id, String url) {
        OVERLAYS.put(id, new Overlay(url));
    }

    public static void show(Identifier id) {
        overlay(id).shown = true;
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

    /** The HUD layer both loaders register above the title layer. */
    public static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.hud.isHidden()) return;
        for (Overlay o : OVERLAYS.values()) if (o.shown) o.extract(g);
    }

    private static Overlay overlay(Identifier id) {
        Overlay o = OVERLAYS.get(id);
        if (o == null) throw new IllegalArgumentException("No Vellum HUD overlay registered as " + id);
        return o;
    }

    private static final class Overlay implements DocumentDriver.Owner {
        private final String url;
        private @Nullable DocumentDriver driver;
        private @Nullable String data;
        private boolean shown;
        private int width, height;

        Overlay(String url) {
            this.url = url;
        }

        void extract(GuiGraphicsExtractor g) {
            if (driver == null) {
                driver = new DocumentDriver(this, url, null, -1);
                if (data != null) driver.pushData(data);
                width = -1;
            }
            if (g.guiWidth() != width || g.guiHeight() != height) {
                width = g.guiWidth();
                height = g.guiHeight();
                driver.resize(width, height);
            }
            driver.extract(g, -1, -1);
        }

        void hide() {
            shown = false;
            if (driver != null) driver.close();
            driver = null;
        }

        @Override
        public void closeDocument() {
            hide();
        }
    }
}
