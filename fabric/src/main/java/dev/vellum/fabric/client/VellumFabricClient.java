package dev.vellum.fabric.client;

import dev.vellum.mod.Constants;
import dev.vellum.mod.client.DevAutopilot;
import dev.vellum.mod.client.TourCursor;
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
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.inventory.AbstractContainerMenu;
//? if >=26 {
import dev.vellum.mod.client.render.GuiSceneRenderer;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
//?} else {
/*import dev.vellum.mod.client.input.MouseButtonEvent;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
*///?}

/** Fabric client entrypoint (fabric.mod.json "client"): registration only; the logic lives in common. */
public final class VellumFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        VellumClient.init(ClientPlayNetworking::send);
        for (VellumNetwork.Clientbound<?> c : VellumNetwork.CLIENTBOUND) register(c);
        //? if >=26 {
        PictureInPictureRendererRegistry.register(context -> new GuiSceneRenderer());
        HudElementRegistry.attachElementAfter(VanillaHudElements.TITLE_AND_SUBTITLE, Constants.id("hud"), VellumHud::extract);
        //?} else
        /*HudRenderCallback.EVENT.register(VellumHud::extract); // 1.21.1 has no HUD layers: after the whole HUD*/
        // Interactive HUD overlays: drawn above every screen and given its pointer input first (per-screen events).
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            //? if >=26 {
            ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, a) -> VellumHud.extractAboveScreen(g, a));
            // The dev tour's cursor, over everything: after the screen's tooltips and the overlays above it.
            if (TourCursor.ENABLED) ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, a) -> TourCursor.extractAboveScreen(g));
            ScreenMouseEvents.allowMouseClick(screen).register((s, e) -> !VellumHud.mouseClicked(e));
            ScreenMouseEvents.allowMouseRelease(screen).register((s, e) -> !VellumHud.mouseReleased(e));
            ScreenMouseEvents.allowMouseDrag(screen).register((s, e, dx, dy) -> !VellumHud.mouseDragged(e));
            //?} else {
            /*ScreenEvents.afterRender(screen).register((s, g, mouseX, mouseY, a) -> VellumHud.extractAboveScreen(g, a));
            ScreenMouseEvents.allowMouseClick(screen).register((s, x, y, button) -> !VellumHud.mouseClicked(MouseButtonEvent.now(x, y, button)));
            ScreenMouseEvents.allowMouseRelease(screen).register((s, x, y, button) -> !VellumHud.mouseReleased(MouseButtonEvent.now(x, y, button)));
            *///?}
            ScreenMouseEvents.allowMouseScroll(screen).register((s, x, y, dx, dy) -> !VellumHud.mouseScrolled(x, y, dx, dy));
        });
        // After every mod's client entrypoint has had the chance to call VellumScreens.registerContainer.
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            for (VellumScreens.ContainerBinding<?> b : VellumScreens.takeContainers()) registerMenuScreen(b);
        });
        //? if >=26 {
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(Constants.id("documents"),
                (ResourceManagerReloadListener) resources -> VellumClient.onResourceReload());
        //?} else {
        /*ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override
            public Identifier getFabricId() {
                return Constants.id("documents");
            }

            @Override
            public void onResourceManagerReload(ResourceManager resources) {
                VellumClient.onResourceReload();
            }
        });
        *///?}
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(VellumClientCommands.create()));
        ClientTickEvents.END_CLIENT_TICK.register(client -> VellumClient.tick());
        if (DevAutopilot.ENABLED) ClientTickEvents.END_CLIENT_TICK.register(DevAutopilot::tick);
        //? if >=26
        if (TourCursor.ENABLED) HudElementRegistry.addLast(Constants.id("tour_cursor"), TourCursor::extractHud);
    }

    private static <T extends CustomPacketPayload> void register(VellumNetwork.Clientbound<T> c) {
        ClientPlayNetworking.registerGlobalReceiver(c.type(), (payload, context) -> context.client().execute(() -> VellumClient.handle(payload)));
    }

    private static <M extends AbstractContainerMenu> void registerMenuScreen(VellumScreens.ContainerBinding<M> b) {
        MenuScreens.register(b.type().get(), b::create);
    }
}
