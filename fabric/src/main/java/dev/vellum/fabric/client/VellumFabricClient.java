package dev.vellum.fabric.client;

import dev.vellum.mod.Constants;
import dev.vellum.mod.client.DevAutopilot;
import dev.vellum.mod.client.VellumClient;
import dev.vellum.mod.client.VellumClientCommands;
import dev.vellum.mod.client.VellumHud;
import dev.vellum.mod.client.VellumScreens;
import dev.vellum.mod.net.VellumNetwork;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** Fabric client entrypoint (fabric.mod.json "client"): registration only; the logic lives in common. */
public final class VellumFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        VellumClient.init(ClientPlayNetworking::send);
        for (VellumNetwork.Clientbound<?> c : VellumNetwork.CLIENTBOUND) register(c);
        HudElementRegistry.attachElementAfter(VanillaHudElements.TITLE_AND_SUBTITLE, Constants.id("hud"), VellumHud::extract);
        // After every mod's client entrypoint has had the chance to call VellumScreens.registerContainer.
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            for (VellumScreens.ContainerBinding<?> b : VellumScreens.takeContainers()) registerMenuScreen(b);
        });
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(Constants.id("documents"),
                (ResourceManagerReloadListener) resources -> VellumClient.onResourceReload());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(VellumClientCommands.create()));
        if (DevAutopilot.ENABLED) ClientTickEvents.END_CLIENT_TICK.register(DevAutopilot::tick);
    }

    private static <T extends CustomPacketPayload> void register(VellumNetwork.Clientbound<T> c) {
        ClientPlayNetworking.registerGlobalReceiver(c.type(), (payload, context) -> context.client().execute(() -> VellumClient.handle(payload)));
    }

    private static <M extends AbstractContainerMenu> void registerMenuScreen(VellumScreens.ContainerBinding<M> b) {
        MenuScreens.register(b.type().get(), b::create);
    }
}
