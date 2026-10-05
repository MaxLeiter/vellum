package dev.vellum.mod.client;

import com.google.gson.JsonElement;
import dev.vellum.mod.Constants;
import dev.vellum.mod.net.OpenPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Client API: open Vellum pages on this client, and give container menus Vellum screens.
 *
 * <pre>{@code
 * VellumScreen screen = VellumScreens.open("mymod:vellum/settings.html");
 * screen.driver().onMessage("save", value -> saveSettings(value));
 *
 * // In client setup, for your own menu type (a MenuType or a registry holder):
 * VellumScreens.registerContainer(MyMenus.FORGE, "mymod:vellum/forge.html");
 * }</pre>
 * Render thread only.
 */
public final class VellumScreens {
    /** A menu type whose screen is a Vellum page. */
    public record ContainerBinding<M extends AbstractContainerMenu>(Supplier<? extends MenuType<M>> type, String url) {
        public VellumContainerScreen<M> create(M menu, Inventory inventory, Component title) {
            return new VellumContainerScreen<>(menu, inventory, title, url);
        }
    }

    private static final List<ContainerBinding<?>> CONTAINERS = new ArrayList<>();
    private static boolean containersRegistered;

    private VellumScreens() {}

    /** Opens a page from resources ({@code namespace:path/page.html} under {@code assets/}). */
    public static VellumScreen open(String url) {
        return open(url, null);
    }

    /** Opens a page with initial {@code vellum.data}. */
    public static VellumScreen open(String url, @Nullable JsonElement data) {
        return show(new VellumScreen(url, null, -1), data == null ? null : data.toString());
    }

    /** Opens inline HTML (with inline {@code <style>} and {@code <script>}); absolute URLs still load resources. */
    public static VellumScreen openInline(String html, @Nullable JsonElement data) {
        return show(new VellumScreen("", html, -1), data == null ? null : data.toString());
    }

    /**
     * Shows {@code url} as the screen of every menu of {@code type}. Call during client setup, before the game
     * finishes loading (both loaders register menu screens once).
     */
    public static <M extends AbstractContainerMenu> void registerContainer(MenuType<M> type, String url) {
        registerContainer(() -> type, url);
    }

    /** As {@link #registerContainer(MenuType, String)}, for a type that is registered later (a registry holder). */
    public static <M extends AbstractContainerMenu> void registerContainer(Supplier<? extends MenuType<M>> type, String url) {
        if (containersRegistered) Constants.LOG.error("Vellum: registerContainer({}) called after menu screens were registered", url);
        CONTAINERS.add(new ContainerBinding<>(type, url));
    }

    /** Loader hook: the bindings to register as menu screens, once. */
    public static List<ContainerBinding<?>> takeContainers() {
        containersRegistered = true;
        return Collections.unmodifiableList(CONTAINERS);
    }

    /** A server session's page; replacing another session's screen tells that server it was closed. */
    static void openSession(OpenPayload p) {
        VellumScreen screen = new VellumScreen(p.html().isEmpty() ? p.url() : "", p.html().isEmpty() ? null : p.html(), p.session());
        show(screen, p.data());
    }

    private static VellumScreen show(VellumScreen screen, @Nullable String data) {
        if (data != null) screen.driver().pushData(data);
        Minecraft.getInstance().gui.setScreen(screen);
        return screen;
    }
}
