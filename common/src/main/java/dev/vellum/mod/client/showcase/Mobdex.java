package dev.vellum.mod.client.showcase;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.vellum.mod.client.DocumentDriver;
import dev.vellum.mod.client.VellumClientCommands;
import dev.vellum.mod.client.VellumScreens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.stats.Stats;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.item.Item;
//? if >=26 {
import net.minecraft.world.item.component.TypedEntityData;
//?} else
//import net.minecraft.world.item.SpawnEggItem;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Live data for the Mobdex showcase page ({@code /vellum showcase mobdex}, or its card in the showcase gallery):
 * every living entity type in the registry, vanilla and modded, with its translated name, spawn category, default
 * attributes, size and spawn egg, and the player's kill statistics. The page adds its own flavour text for vanilla
 * mobs.
 *
 * <p>Statistics live on the server; the client's copy is only filled in when asked for, as the vanilla statistics
 * screen does. Loading the page asks, and while a Mobdex is open the statistics are compared every client tick
 * ({@link #tick}) and the pages get the new numbers when they change.
 *
 * <p>The data: {@code {player, mobs: [{id, name, category, hp, atk, def, spd, width, height, egg, killed,
 * killedBy}]}}, merged into what the opener passed (such as {@code start}: {@code {mob, filter, query}}); {@code egg}
 * is the spawn egg's item id or null.
 */
public final class Mobdex {
    public static final String URL = VellumClientCommands.showcaseUrl("mobdex");

    /** The statistics the open pages show ({@link #stats}). */
    private static int[] shown = new int[0];

    private Mobdex() {}

    /** The page is loading in {@code driver} ({@code VellumScreens.onPageLoad}): give it data, ask for statistics. */
    public static void load(DocumentDriver driver) {
        List<EntityType<?>> types = types();
        driver.merge(data(types));
        shown = stats(types);
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection != null) connection.send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_STATS));
    }

    /** Client tick: when the statistics changed (they arrived, or the player defeated a mob), open pages show them. */
    public static void tick() {
        List<DocumentDriver> pages = VellumScreens.pages(URL);
        if (pages.isEmpty()) return;
        List<EntityType<?>> types = types();
        int[] stats = stats(types);
        if (Arrays.equals(stats, shown)) return;
        shown = stats;
        JsonObject data = data(types);
        for (DocumentDriver page : pages) page.merge(data);
    }

    /** Living entity types: those with default attributes (both loaders add modded ones there), except the player. */
    private static List<EntityType<?>> types() {
        List<EntityType<?>> types = new ArrayList<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (type != EntityTypes.PLAYER && DefaultAttributes.hasSupplier(type)) types.add(type);
        }
        return types;
    }

    /** Kills and deaths for each type, from the client's copy of the player's statistics. */
    private static int[] stats(List<EntityType<?>> types) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return new int[0];
        StatsCounter stats = player.getStats();
        int[] values = new int[types.size() * 2];
        for (int i = 0; i < types.size(); i++) {
            values[2 * i] = stats.getValue(Stats.ENTITY_KILLED, types.get(i));
            values[2 * i + 1] = stats.getValue(Stats.ENTITY_KILLED_BY, types.get(i));
        }
        return values;
    }

    /** The page's fields, from the registries and the client's copy of the player's statistics. */
    private static JsonObject data(List<EntityType<?>> types) {
        Minecraft mc = Minecraft.getInstance();
        StatsCounter stats = mc.player == null ? null : mc.player.getStats();
        Map<EntityType<?>, String> eggs = eggs();
        JsonArray mobs = new JsonArray();
        for (EntityType<?> type : types) mobs.add(mob(type, eggs.get(type), stats));
        JsonObject data = new JsonObject();
        data.addProperty("player", mc.getUser().getName());
        data.add("mobs", mobs);
        return data;
    }

    /** The spawn egg item id of each entity type that has one (as {@code SpawnEggItem.byId}, in one pass). */
    private static Map<EntityType<?>, String> eggs() {
        Map<EntityType<?>, String> eggs = new HashMap<>();
        //? if >=26 {
        for (Holder<Item> item : BuiltInRegistries.ITEM.componentLookup().findAll(DataComponents.ENTITY_DATA)) {
            TypedEntityData<EntityType<?>> data = item.components().get(DataComponents.ENTITY_DATA);
            if (data != null) eggs.putIfAbsent(data.type(), item.getRegisteredName());
        }
        //?} else {
        /*for (SpawnEggItem egg : SpawnEggItem.eggs()) {
            eggs.putIfAbsent(egg.getType(egg.getDefaultInstance()), BuiltInRegistries.ITEM.getKey(egg).toString());
        }
        *///?}
        return eggs;
    }

    @SuppressWarnings("unchecked")
    private static JsonObject mob(EntityType<?> type, @Nullable String egg, @Nullable StatsCounter stats) {
        AttributeSupplier attributes = DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) type);
        EntityDimensions size = type.getDimensions();
        JsonObject mob = new JsonObject();
        mob.addProperty("id", BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
        mob.addProperty("name", type.getDescription().getString());
        mob.addProperty("category", type.getCategory().getSerializedName());
        mob.addProperty("hp", value(attributes, Attributes.MAX_HEALTH));
        mob.addProperty("atk", value(attributes, Attributes.ATTACK_DAMAGE));
        mob.addProperty("def", value(attributes, Attributes.ARMOR));
        mob.addProperty("spd", value(attributes, Attributes.MOVEMENT_SPEED));
        mob.addProperty("width", size.width());
        mob.addProperty("height", size.height());
        mob.addProperty("egg", egg);
        mob.addProperty("killed", stats == null ? 0 : stats.getValue(Stats.ENTITY_KILLED, type));
        mob.addProperty("killedBy", stats == null ? 0 : stats.getValue(Stats.ENTITY_KILLED_BY, type));
        return mob;
    }

    /** A default attribute value, rounded for display; 0 when the type does not have it. */
    private static double value(AttributeSupplier attributes, Holder<Attribute> attribute) {
        return attributes.hasAttribute(attribute) ? Math.round(attributes.getValue(attribute) * 1000) / 1000.0 : 0;
    }
}
