package dev.vellum.mod.client;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;

import java.util.List;

/**
 * Client commands: {@code /vellum open <url> | demo [name] | reload | canvastest}. Generic in the command source so
 * each loader registers the same tree with its own client source type.
 */
public final class VellumClientCommands {
    /** The client-side demo pages, under {@code assets/vellum/vellum/demo/} (the gallery is {@code index.html}). */
    public static final List<String> DEMOS = List.of("gallery", "settings", "layout", "animation", "templates", "map", "hud");
    /**
     * Demos run by the server ({@code VellumCommand}). The client forwards them itself: Fabric's client dispatcher
     * claims every {@code /vellum ...} command once a client {@code vellum} node exists.
     */
    public static final List<String> SERVER_DEMOS = List.of("chest", "live");
    /** The show-off pages under {@code assets/vellum/vellum/showcase/}. */
    public static final List<String> SHOWCASE = List.of("title", "hud", "shop", "mobdex", "journal", "console", "models");

    private VellumClientCommands() {}

    public static <S> LiteralArgumentBuilder<S> create() {
        LiteralArgumentBuilder<S> demo = LiteralArgumentBuilder.<S>literal("demo").executes(c -> later(() -> demo("gallery")));
        for (String name : DEMOS) demo.then(LiteralArgumentBuilder.<S>literal(name).executes(c -> later(() -> demo(name))));
        demo.then(LiteralArgumentBuilder.<S>literal("toast").executes(c -> later(VellumClientCommands::toast)));
        for (String name : SERVER_DEMOS) demo.then(LiteralArgumentBuilder.<S>literal(name).executes(c -> askServer("vellum demo " + name)));
        LiteralArgumentBuilder<S> showcase = LiteralArgumentBuilder.<S>literal("showcase")
                .executes(c -> later(() -> VellumScreens.open(showcaseUrl("index"))));
        for (String name : SHOWCASE) showcase.then(LiteralArgumentBuilder.<S>literal(name).executes(c -> later(() -> VellumScreens.open(showcaseUrl(name)))));
        return LiteralArgumentBuilder.<S>literal("vellum")
                .then(LiteralArgumentBuilder.<S>literal("open")
                        .then(RequiredArgumentBuilder.<S, String>argument("url", StringArgumentType.greedyString())
                                .executes(c -> {
                                    String url = StringArgumentType.getString(c, "url");
                                    return later(() -> VellumScreens.open(url));
                                })))
                .then(demo)
                .then(showcase)
                .then(LiteralArgumentBuilder.<S>literal("reload").executes(c -> later(VellumClient::onResourceReload)))
                .then(LiteralArgumentBuilder.<S>literal("canvastest").executes(c -> later(() -> Minecraft.getInstance().gui.setScreen(new CanvasTestScreen()))));
    }

    /** Opens a demo page; {@code hud} toggles the demo HUD overlay instead. */
    public static void demo(String name) {
        if (name.equals("hud")) {
            if (VellumHud.isShown(VellumClient.DEMO_HUD)) VellumHud.hide(VellumClient.DEMO_HUD);
            else VellumHud.show(VellumClient.DEMO_HUD);
            return;
        }
        JsonObject data = new JsonObject();
        data.addProperty("source", "client");
        data.addProperty("player", Minecraft.getInstance().getUser().getName());
        VellumScreens.open(demoUrl(name), data);
    }

    /**
     * Toggles the interactive toast overlay (a HUD overlay with buttons: press T and click them). The answer comes
     * back as a message and is echoed in chat.
     */
    public static void toast() {
        if (VellumHud.isShown(VellumClient.DEMO_TOAST)) {
            VellumHud.hide(VellumClient.DEMO_TOAST);
            return;
        }
        VellumHud.show(VellumClient.DEMO_TOAST)
                .onMessage("answer", value -> Minecraft.getInstance().gui.hud.getChat()
                        .addClientSystemMessage(Component.literal("Rivet's request: " + value.getAsString())));
    }

    public static String demoUrl(String name) {
        return "vellum:vellum/demo/" + (name.equals("gallery") ? "index" : name) + ".html";
    }

    public static String showcaseUrl(String name) {
        return "vellum:vellum/showcase/" + name + ".html";
    }

    /** Sends a command straight to the server, past the client dispatcher (which would take it again). */
    private static int askServer(String command) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection != null) connection.send(new ServerboundChatCommandPacket(command));
        return 1;
    }

    /** Runs after the chat screen that sent the command has closed (it would close a screen opened right away). */
    private static int later(Runnable action) {
        Minecraft.getInstance().schedule(action);
        return 1;
    }
}
