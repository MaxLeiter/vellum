package dev.vellum.mod.client.showcase;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.vellum.mod.client.DocumentDriver;
import dev.vellum.mod.client.VellumClientCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.Holder;
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
import net.minecraft.world.item.SpawnEggItem;
import org.jspecify.annotations.Nullable;

import java.lang.ref.WeakReference;

/**
 * Live data for the Mobdex showcase page ({@code /vellum showcase mobdex}, or its card in the showcase gallery):
 * every living entity type in the registry, vanilla and modded, with its translated name, spawn category, default
 * attributes, size and spawn egg, and the player's kill statistics. The page adds its own flavour text for vanilla
 * mobs.
 *
 * <p>Statistics live on the server; the client's copy is only filled in when asked for, as the vanilla statistics
 * screen does. Loading the page asks, and the page gets the new numbers when they arrive ({@link #statsUpdated}).
 *
 * <p>The data: {@code {player, start, mobs: [{id, name, category, hp, atk, def, spd, width, height, egg, killed,
 * killedBy}]}}. {@code start} ({@code {mob, filter, query}}, chosen by whoever opened the page) is kept; {@code egg}
 * is the spawn egg's item id or null.
 */
public final class Mobdex {
    public static final String URL = VellumClientCommands.showcaseUrl("mobdex");

    /** The driver showing the Mobdex, for pushing statistics when they arrive. */
    private static WeakReference<DocumentDriver> shown = new WeakReference<>(null);

    private Mobdex() {}

    /** The page is loading in {@code driver} ({@code VellumScreens.onPageLoad}): give it data, ask for statistics. */
    public static void load(DocumentDriver driver) {
        driver.push(data(start(driver)));
        shown = new WeakReference<>(driver);
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection != null) connection.send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_STATS));
    }

    /** The client's statistics changed: a Mobdex still on screen shows the new numbers. */
    public static void statsUpdated() {
        DocumentDriver driver = shown.get();
        if (driver != null && driver.document() != null && driver.url().equals(URL)) driver.push(data(start(driver)));
    }

    /** The opening view that whoever opened the page passed, kept across updates. */
    private static @Nullable JsonObject start(DocumentDriver driver) {
        return driver.data() instanceof JsonObject data && data.get("start") instanceof JsonObject start ? start : null;
    }

    /** The page's data, from the registries and the client's copy of the player's statistics. */
    private static JsonObject data(@Nullable JsonObject start) {
        Minecraft mc = Minecraft.getInstance();
        StatsCounter stats = mc.player == null ? null : mc.player.getStats();
        JsonArray mobs = new JsonArray();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            // Living entities are exactly those with default attributes (both loaders add modded ones there).
            if (type == EntityTypes.PLAYER || !DefaultAttributes.hasSupplier(type)) continue;
            mobs.add(mob(type, stats));
        }
        JsonObject data = new JsonObject();
        data.addProperty("player", mc.getUser().getName());
        if (start != null) data.add("start", start);
        data.add("mobs", mobs);
        return data;
    }

    @SuppressWarnings("unchecked")
    private static JsonObject mob(EntityType<?> type, @Nullable StatsCounter stats) {
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
        mob.addProperty("egg", SpawnEggItem.byId(type).map(Holder::getRegisteredName).orElse(null));
        mob.addProperty("killed", stats == null ? 0 : stats.getValue(Stats.ENTITY_KILLED, type));
        mob.addProperty("killedBy", stats == null ? 0 : stats.getValue(Stats.ENTITY_KILLED_BY, type));
        return mob;
    }

    /** A default attribute value, rounded for display; 0 when the type does not have it. */
    private static double value(AttributeSupplier attributes, Holder<Attribute> attribute) {
        return attributes.hasAttribute(attribute) ? Math.round(attributes.getValue(attribute) * 1000) / 1000.0 : 0;
    }
}
