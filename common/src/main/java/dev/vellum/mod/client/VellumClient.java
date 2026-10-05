package dev.vellum.mod.client;

import dev.vellum.mod.Constants;
import dev.vellum.mod.client.render.McFontMetrics;
import dev.vellum.mod.client.replaced.McReplaced;
import dev.vellum.mod.net.ClosePayload;
import dev.vellum.mod.net.DataPayload;
import dev.vellum.mod.net.OpenPayload;
import dev.vellum.mod.server.VellumDemos;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Client entry points. Only loader client entrypoints (Fabric's {@code ClientModInitializer}, NeoForge's
 * {@code dist = CLIENT} mod class) may reference this class; handlers run on the client thread.
 */
public final class VellumClient {
    public static final Identifier DEMO_HUD = Constants.id("demo_hud");

    private static Consumer<CustomPacketPayload> serverSender = p ->
            Constants.LOG.warn("Vellum: no client network sender installed; dropped {}", p.type().id());

    private VellumClient() {}

    /** Called by the loader's client entrypoint with its way of sending serverbound payloads. */
    public static void init(Consumer<CustomPacketPayload> sender) {
        serverSender = sender;
        VellumConfig.load();
        VellumResources.init();
        VellumScreens.registerContainer(VellumDemos.CHEST, VellumDemos.CHEST_PAGE);
        VellumHud.register(DEMO_HUD, "vellum:vellum/demo/hud.html");
    }

    public static void sendToServer(CustomPacketPayload payload) {
        serverSender.accept(payload);
    }

    /** Handles every clientbound payload in {@code VellumNetwork.CLIENTBOUND}. */
    public static void handle(CustomPacketPayload payload) {
        switch (payload) {
            case OpenPayload p -> VellumScreens.openSession(p);
            case DataPayload p -> {
                VellumScreen screen = sessionScreen(p.session());
                if (screen != null) screen.driver().pushData(p.data());
            }
            case ClosePayload p -> {
                VellumScreen screen = sessionScreen(p.session());
                if (screen != null) {
                    screen.driver().closedByServer();
                    screen.onClose();
                }
            }
            default -> Constants.LOG.warn("Vellum: unhandled payload {}", payload.type().id());
        }
    }

    /** Resources reloaded (F3+T, resource packs): fonts and images may have changed, so reload every open page. */
    public static void onResourceReload() {
        McFontMetrics.INSTANCE.clearCache();
        McReplaced.clearCaches();
        DocumentDriver.reloadAll();
    }

    private static @Nullable VellumScreen sessionScreen(int session) {
        return Minecraft.getInstance().gui.screen() instanceof VellumScreen s && s.driver().session() == session ? s : null;
    }
}
