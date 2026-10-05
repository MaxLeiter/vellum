package dev.vellum.mod.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.vellum.engine.host.Urls;
import dev.vellum.mod.Constants;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.function.Function;
import java.util.Map;
import java.util.function.Consumer;
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
 * // With fields of your own in vellum.data, next to title, inventory and slots:
 * VellumScreens.registerContainer(MyMenus.BOT, "mymod:vellum/bot.html", menu -> {
 *     JsonObject data = new JsonObject();
 *     data.addProperty("tier", menu.tier());
 *     return data;
 * });
 * }</pre>
 * Render thread only.
 */
public final class VellumScreens {
    /** A menu type whose screen is a Vellum page, with the mod's extra {@code vellum.data} fields (or null). */
    public record ContainerBinding<M extends AbstractContainerMenu>(Supplier<? extends MenuType<M>> type, String url,
                                                                    @Nullable Function<? super M, ? extends JsonObject> data) {
        public ContainerBinding(Supplier<? extends MenuType<M>> type, String url) {
            this(type, url, null);
        }

        public VellumContainerScreen<M> create(M menu, Inventory inventory, Component title) {
            return new VellumContainerScreen<>(menu, inventory, title, url, data);
        }
    }

    private static final List<ContainerBinding<?>> CONTAINERS = new ArrayList<>();
    private static boolean containersRegistered;
    private static final Map<String, Consumer<DocumentDriver>> PAGE_HOOKS = new HashMap<>();

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
        registerContainer(type, url, null);
    }

    /**
     * As {@link #registerContainer(MenuType, String)}, with fields of the mod's own in the page's {@code vellum.data}
     * (an entity id, a tier...). {@code data} gets the screen's menu and returns the fields to add next to
     * {@code title}, {@code inventory} and {@code slots} (a field with one of those names replaces it), or null for
     * none. It is called when the screen opens and every client tick after; the page gets new data (and its
     * {@code vellum.on('data')} listeners run) whenever the fields or the slots changed.
     */
    public static <M extends AbstractContainerMenu> void registerContainer(MenuType<M> type, String url,
                                                                           @Nullable Function<? super M, ? extends JsonObject> data) {
        registerContainer(() -> type, url, data);
    }

    /** As {@link #registerContainer(MenuType, String, Function)}, for a type that is registered later. */
    public static <M extends AbstractContainerMenu> void registerContainer(Supplier<? extends MenuType<M>> type, String url,
                                                                           @Nullable Function<? super M, ? extends JsonObject> data) {
        if (containersRegistered) Constants.LOG.error("Vellum: registerContainer({}) called after menu screens were registered", url);
        CONTAINERS.add(new ContainerBinding<>(type, url, data));
    }

    /** Loader hook: the bindings to register as menu screens, once. */
    public static List<ContainerBinding<?>> takeContainers() {
        containersRegistered = true;
        return Collections.unmodifiableList(CONTAINERS);
    }

    /**
     * Runs {@code hook} whenever the page {@code url} loads in a screen or overlay (opened, reached by a link, or
     * reloaded), before its scripts: to give a client-side page live data ({@code driver.push}, or
     * {@code driver.merge} to keep what the opener passed) or handle its messages. {@link #pages} finds it later to
     * push updates.
     */
    public static void onPageLoad(String url, Consumer<DocumentDriver> hook) {
        PAGE_HOOKS.merge(url, hook, Consumer::andThen);
    }

    /** The drivers of the screens and overlays showing page {@code url} now (queries and fragments ignored). */
    public static List<DocumentDriver> pages(String url) {
        return DocumentDriver.showing(url);
    }

    /** {@link DocumentDriver}: {@code url} is loading. */
    static void pageLoading(String url, DocumentDriver driver) {
        Consumer<DocumentDriver> hook = PAGE_HOOKS.get(Urls.withoutQuery(url));
        if (hook != null) hook.accept(driver);
    }

    private static VellumScreen show(VellumScreen screen, @Nullable String data) {
        if (data != null) screen.driver().pushData(data);
        Minecraft.getInstance().gui.setScreen(screen);
        return screen;
    }
}
