package dev.vellum.neoforge.client;

import dev.vellum.mod.Constants;
import dev.vellum.mod.client.DevAutopilot;
import dev.vellum.mod.client.McClient;
import dev.vellum.mod.client.TourCursor;
import dev.vellum.mod.client.VellumClient;
import dev.vellum.mod.client.VellumClientCommands;
import dev.vellum.mod.client.VellumHud;
import dev.vellum.mod.client.VellumScreens;
import dev.vellum.mod.client.replaced.ItemTooltips;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;
//? if >=26 {
import dev.vellum.mod.client.render.GuiSceneRenderState;
import dev.vellum.mod.client.render.GuiSceneRenderer;
import dev.vellum.mod.net.VellumNetwork;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterPictureInPictureRenderersEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
//?} else {
/*import net.minecraft.client.input.MouseButtonEvent;
import dev.vellum.neoforge.VellumNeoForge;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.network.PacketDistributor;
*///?}

/** Client-only entry point: FML constructs this class on the physical client only. */
@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
public final class VellumNeoForgeClient {
    public VellumNeoForgeClient(IEventBus modBus, ModContainer container) {
        //? if >=26 {
        VellumClient.init(payload -> ClientPacketDistributor.sendToServer(payload));
        // <item tooltip>: pass the stack on to NeoForge's tooltip events, as vanilla's item tooltips do.
        ItemTooltips.install((g, font, lines, stack, x, y) -> g.setTooltipForNextFrame(font, lines, stack.getTooltipImage(),
                stack, x, y, stack.get(DataComponents.TOOLTIP_STYLE), true));
        modBus.addListener(VellumNeoForgeClient::registerPayloadHandlers);
        modBus.addListener((RegisterPictureInPictureRenderersEvent e) -> e.register(GuiSceneRenderState.class, GuiSceneRenderer::new));
        modBus.addListener((AddClientReloadListenersEvent e) ->
                e.addListener(Constants.id("documents"), (ResourceManagerReloadListener) resources -> VellumClient.onResourceReload()));
        //?} else {
        /*VellumClient.init(payload -> PacketDistributor.sendToServer(payload));
        // <item tooltip>: pass the stack on to NeoForge's tooltip events, as vanilla's item tooltips do.
        ItemTooltips.install((g, font, lines, stack, x, y) -> g.renderTooltip(font, lines, stack.getTooltipImage(), stack, x, y));
        VellumNeoForge.clientHandler = VellumClient::handle; // on the client main thread (the registrar's default)
        modBus.addListener((RegisterClientReloadListenersEvent e) ->
                e.registerReloadListener((ResourceManagerReloadListener) resources -> VellumClient.onResourceReload()));
        *///?}
        modBus.addListener((RegisterGuiLayersEvent e) -> e.registerAbove(VanillaGuiLayers.TITLE, Constants.id("hud"), VellumHud::extract));
        modBus.addListener((RegisterMenuScreensEvent e) -> {
            for (VellumScreens.ContainerBinding<?> b : VellumScreens.takeContainers()) registerMenuScreen(e, b);
        });
        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent e) -> e.getDispatcher().register(VellumClientCommands.create()));
        // Interactive HUD overlays: drawn above the screen (not its background layers) and given its pointer input first.
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Render.Post e) -> {
            if (e.getScreen() == McClient.screen()) VellumHud.extractAboveScreen(e.getGuiGraphics(), e.getPartialTick());
        });
        //? if >=26 {
        NeoForge.EVENT_BUS.addListener((ScreenEvent.MouseButtonPressed.Pre e) -> {
            if (VellumHud.mouseClicked(e.getMouseButtonEvent())) e.setCanceled(true);
        });
        NeoForge.EVENT_BUS.addListener((ScreenEvent.MouseButtonReleased.Pre e) -> {
            if (VellumHud.mouseReleased(e.getMouseButtonEvent())) e.setCanceled(true);
        });
        NeoForge.EVENT_BUS.addListener((ScreenEvent.MouseDragged.Pre e) -> {
            if (VellumHud.mouseDragged(e.getMouseButtonEvent())) e.setCanceled(true);
        });
        //?} else {
        /*NeoForge.EVENT_BUS.addListener((ScreenEvent.MouseButtonPressed.Pre e) -> {
            if (VellumHud.mouseClicked(MouseButtonEvent.now(e.getMouseX(), e.getMouseY(), e.getButton()))) e.setCanceled(true);
        });
        NeoForge.EVENT_BUS.addListener((ScreenEvent.MouseButtonReleased.Pre e) -> {
            if (VellumHud.mouseReleased(MouseButtonEvent.now(e.getMouseX(), e.getMouseY(), e.getButton()))) e.setCanceled(true);
        });
        NeoForge.EVENT_BUS.addListener((ScreenEvent.MouseDragged.Pre e) -> {
            if (VellumHud.mouseDragged(MouseButtonEvent.now(e.getMouseX(), e.getMouseY(), e.getMouseButton()))) e.setCanceled(true);
        });
        *///?}
        NeoForge.EVENT_BUS.addListener((ScreenEvent.MouseScrolled.Pre e) -> {
            if (VellumHud.mouseScrolled(e.getMouseX(), e.getMouseY(), e.getScrollDeltaX(), e.getScrollDeltaY())) e.setCanceled(true);
        });
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> VellumClient.tick());
        if (DevAutopilot.ENABLED) NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> DevAutopilot.tick(Minecraft.getInstance()));
        //? if >=26 {
        // The dev tour's cursor, over everything: after the screen's tooltips and the overlays above it, else last in the HUD.
        if (TourCursor.ENABLED) {
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (ScreenEvent.Render.Post e) -> {
                if (e.getScreen() == Minecraft.getInstance().gui.screen()) TourCursor.extractAboveScreen(e.getGuiGraphics());
            });
            modBus.addListener((RegisterGuiLayersEvent e) -> e.registerAboveAll(Constants.id("tour_cursor"), TourCursor::extractHud));
        }
        //?}
    }

    //? if >=26 {
    private static void registerPayloadHandlers(RegisterClientPayloadHandlersEvent event) {
        // Runs on the client main thread (HandlerThread.MAIN is the default for this overload).
        for (VellumNetwork.Clientbound<?> c : VellumNetwork.CLIENTBOUND) register(event, c);
    }

    private static <T extends CustomPacketPayload> void register(RegisterClientPayloadHandlersEvent event, VellumNetwork.Clientbound<T> c) {
        event.register(c.type(), (payload, context) -> VellumClient.handle(payload));
    }
    //?}

    private static <M extends AbstractContainerMenu> void registerMenuScreen(RegisterMenuScreensEvent event, VellumScreens.ContainerBinding<M> b) {
        event.register(b.type().get(), b::create);
    }
}
