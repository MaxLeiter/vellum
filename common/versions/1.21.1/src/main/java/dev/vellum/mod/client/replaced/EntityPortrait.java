package dev.vellum.mod.client.replaced;

import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.mod.client.render.McCanvas;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Draws an entity fitted into a box. On Minecraft 1.21.1 Vellum does not draw entities in 3D yet: the box shows the
 * entity's spawn egg, centred at up to 32 px (nothing for entities without one, such as players), multiplied by the
 * opacity like any item.
 */
public final class EntityPortrait {
    /** How the entity would be shown, in degrees (see the 26.x EntityPortrait); unused by the fallback. */
    public record Pose(float yaw, float pitch, boolean followMouse, float scale, float walk) {
        public static final Pose FRONT = new Pose(0, 0, false, 1, 0);
    }

    /** Display entities get negative ids: renderers need one, and real entities' ids are positive. */
    private static int nextDisplayId = -1;

    private EntityPortrait() {}

    /** An entity for display only, never added to the world. */
    public static @Nullable Entity create(EntityType<?> type, Level level) {
        if (!type.isEnabled(level.enabledFeatures())) return null;
        Entity entity = type.create(level);
        if (entity != null) entity.setId(nextDisplayId--);
        return entity;
    }

    /** Draws {@code entity}'s fallback (its spawn egg) into the box {@code (x, y, width, height)}. */
    public static void draw(McCanvas canvas, Entity entity, Pose pose, ComputedStyle style, int tint, float x, float y, float width, float height) {
        SpawnEggItem egg = SpawnEggItem.byId(entity.getType());
        if (egg == null) return;
        float size = Math.min(32, Math.min(width, height));
        canvas.drawItem(new ItemStack(egg), x + (width - size) / 2, y + (height - size) / 2, size, false);
    }
}
