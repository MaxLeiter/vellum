package dev.vellum.mod.server;

import com.google.gson.JsonObject;
import dev.vellum.mod.registry.VellumRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Server halves of the demos: a chest whose screen is the {@code inventory.html} page (proving that {@code <slot>}
 * elements are real slots), and a live session that exercises the server API both ways.
 */
public final class VellumDemos {
    public static final String CHEST_PAGE = "vellum:vellum/demo/chest.html";
    public static final String LIVE_PAGE = "vellum:vellum/demo/templates.html";

    /** A plain 9×3 chest menu with its own type, so the client can give it a Vellum screen without touching chests. */
    public static final MenuType<ChestMenu> CHEST = new MenuType<>(VellumDemos::clientChest, FeatureFlags.VANILLA_SET);

    private VellumDemos() {}

    public static void init() {
        VellumRegistry.add(Registries.MENU, "demo_chest", () -> CHEST);
    }

    private static ChestMenu clientChest(int containerId, Inventory inventory) {
        return new ChestMenu(CHEST, containerId, inventory, new SimpleContainer(27), 3);
    }

    /** Opens a chest of sample items. */
    public static void openChest(ServerPlayer player) {
        SimpleContainer chest = new SimpleContainer(27);
        ItemStack[] samples = {new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.GOLDEN_APPLE, 3), new ItemStack(Items.OAK_LOG, 64),
                new ItemStack(Items.ENDER_PEARL, 16), new ItemStack(Items.BOOK), new ItemStack(Items.TORCH, 32), new ItemStack(Items.ELYTRA)};
        for (ItemStack s : samples) chest.addItem(s);
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new ChestMenu(CHEST, id, inventory, chest, 3),
                Component.translatable("vellum.demo.chest")));
    }

    /**
     * Opens the templates page as a server session: the page asks for fresh stats every second ({@code refresh}) and
     * the server pushes them; {@code say} echoes a message into the player's chat.
     */
    public static VellumSession openLive(ServerPlayer player) {
        VellumSession session = VellumServer.open(player, LIVE_PAGE, stats(player));
        return session
                .onMessage("refresh", (p, value) -> session.push(stats(p)))
                .onMessage("say", (p, value) -> p.sendSystemMessage(Component.literal("[Vellum] " + value.getAsString())));
    }

    private static JsonObject stats(ServerPlayer p) {
        JsonObject o = new JsonObject();
        o.addProperty("source", "server");
        o.addProperty("player", p.getGameProfile().name());
        o.addProperty("health", Math.round(p.getHealth()));
        o.addProperty("x", p.getBlockX());
        o.addProperty("y", p.getBlockY());
        o.addProperty("z", p.getBlockZ());
        //? if >=26 {
        o.addProperty("time", p.level().getOverworldClockTime() % 24000);
        //?} else
        /*o.addProperty("time", p.level().getDayTime() % 24000);*/
        return o;
    }
}
