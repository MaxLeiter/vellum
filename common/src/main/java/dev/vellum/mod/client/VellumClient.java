package dev.vellum.mod.client;

import dev.vellum.mod.Constants;
import dev.vellum.mod.client.render.McFontMetrics;
import dev.vellum.mod.client.render.McImages;
import dev.vellum.mod.client.showcase.Mobdex;
import dev.vellum.mod.net.ClosePayload;
import dev.vellum.mod.net.DataPayload;
import dev.vellum.mod.net.OpenPayload;
import dev.vellum.mod.server.VellumDemos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.function.Consumer;

/**
 * Client entry points. Only loader client entrypoints (Fabric's {@code ClientModInitializer}, NeoForge's
 * {@code dist = CLIENT} mod class) may reference this class; handlers run on the client thread.
 */
public final class VellumClient {
    public static final Identifier DEMO_HUD = Constants.id("demo_hud");
    public static final Identifier DEMO_TOAST = Constants.id("demo_toast");

    private static Consumer<CustomPacketPayload> serverSender = p ->
            Constants.LOG.warn("Vellum: no client network sender installed; dropped {}", p.type().id());

    private VellumClient() {}

    /** Called by the loader's client entrypoint with its way of sending serverbound payloads. */
    public static void init(Consumer<CustomPacketPayload> sender) {
        serverSender = sender;
        VellumResources.init();
        VellumScreens.registerContainer(VellumDemos.CHEST, VellumDemos.CHEST_PAGE);
        VellumHud.register(DEMO_HUD, "vellum:vellum/demo/hud.html");
        VellumHud.register(DEMO_TOAST, "vellum:vellum/demo/toast.html", VellumHud.Input.WHEN_CHAT_OPEN);
        VellumScreens.onPageLoad(Mobdex.URL, Mobdex::load);
    }

    public static void sendToServer(CustomPacketPayload payload) {
        serverSender.accept(payload);
    }

    /** Handles every clientbound payload in {@code VellumNetwork.CLIENTBOUND}; {@link ServerPages} decides what servers may show. */
    public static void handle(CustomPacketPayload payload) {
        switch (payload) {
            case OpenPayload p -> ServerPages.open(p);
            case DataPayload p -> ServerPages.data(p);
            case ClosePayload p -> ServerPages.close(p);
            default -> Constants.LOG.warn("Vellum: unhandled payload {}", payload.type().id());
        }
    }

    /** Resources reloaded (F3+T, resource packs): fonts and images may have changed, so reload every open page. */
    public static void onResourceReload() {
        McFontMetrics.INSTANCE.clearCache();
        McImages.clearCaches();
        DocumentDriver.reloadAll();
    }

    /** Called by the loaders at the end of every client tick. */
    public static void tick() {
        ServerPages.tick();
        Mobdex.tick();
    }
}
