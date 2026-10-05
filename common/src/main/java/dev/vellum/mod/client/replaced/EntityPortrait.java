package dev.vellum.mod.client.replaced;

import dev.vellum.mod.client.render.McCanvas;
import dev.vellum.mod.client.render.Scene;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * Draws a live entity fitted into a box, as vanilla's inventory does for the player, as a {@link Scene} (a
 * picture-in-picture render, so it can be tinted and faded). Any number of entities, even of one type, can be drawn
 * in a frame.
 *
 * <p>The fit: the entity stands on the bottom edge of the box, centred, at the largest size at which its model
 * ({@link EntityReach}: heads, tails, arms and tentacles reach past bounding boxes) fits under a small margin at the
 * top and sides, seen at the current pitch and at any turn. So tall mobs fit the height and long or wide ones the
 * width, parts that hang below the feet (a ghast's tentacles) rest on the edge instead, and spinning one never
 * changes its size.
 */
public final class EntityPortrait {
    /** Free space above the entity, as a fraction of the box height. */
    private static final float TOP_MARGIN = 0.08f;
    /** Free space on each side, as a fraction of the box width. */
    private static final float SIDE_MARGIN = 0.05f;

    /**
     * How the entity is shown, in degrees. {@code yaw} turns it (0 faces the viewer, positive turns its front to the
     * right) and {@code pitch} views it from above (positive) or below. {@code gazeYaw}/{@code gazePitch} turn its
     * head toward a point (right and up are positive) and, as in vanilla's inventory, lean it that way. {@code scale}
     * multiplies the size that fits the box and {@code walk} swings its limbs at that speed (0 stands still).
     */
    public record Pose(float yaw, float pitch, float gazeYaw, float gazePitch, float scale, float walk) {
        public static final Pose FRONT = new Pose(0, 0, 0, 0, 1, 0);
    }

    /**
     * Display entities get negative ids: renderers need one, and real entities' ids are positive. They never tick, so
     * their idle animations play on the clock.
     */
    private static int nextDisplayId = -1;

    private EntityPortrait() {}

    /** An entity for display only, never added to the world: monsters show in peaceful worlds too. */
    public static @Nullable Entity create(EntityType<?> type, Level level) {
        if (!type.isEnabled(level.enabledFeatures())) return null;
        Entity entity = type.create(level, new EntitySpawnRequest(EntitySpawnReason.LOAD, true));
        if (entity != null) entity.setId(nextDisplayId--);
        return entity;
    }

    /**
     * Draws {@code entity} into the box {@code (x, y, width, height)} in the canvas's current transform, multiplied by
     * {@code tint} (ARGB; white for none). Nothing is extracted for a box that is clipped away or transparent.
     */
    public static void draw(McCanvas canvas, Entity entity, Pose pose, int tint, float x, float y, float width, float height) {
        if (!canvas.sceneVisible(tint, x, y, width, height)) return;
        EntityRenderState state = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity).createRenderState(entity, 1.0F);
        state.shadowPieces.clear();
        state.outlineColor = 0;
        state.nameTag = null;
        state.lightCoords = LightCoordsUtil.FULL_BRIGHT;
        if (entity.getId() < 0) state.ageInTicks = Util.getMillis() / 50F;

        // Leaning toward the gaze tilts the view the other way: looking up shows it from below.
        Quaternionf view = new Quaternionf().rotateX((pose.gazePitch() - pose.pitch()) * Mth.DEG_TO_RAD);
        Quaternionf rotation = new Quaternionf().rotateZ(Mth.PI).mul(view);
        if (state instanceof LivingEntityRenderState living) {
            living.bodyRot = 180.0F - pose.yaw() - pose.gazeYaw();
            living.yRot = -pose.gazeYaw();
            living.xRot = -pose.gazePitch();
            living.walkAnimationPos = living.ageInTicks * pose.walk();
            living.walkAnimationSpeed = pose.walk();
            // Ignore the entity's own scale (scaled mobs): fit the box instead.
            living.boundingBoxWidth /= living.scale;
            living.boundingBoxHeight /= living.scale;
            living.scale = 1.0F;
        } else {
            rotation.mul(new Quaternionf().rotateY(pose.yaw() * Mth.DEG_TO_RAD));
        }

        EntityReach reach = EntityReach.of(state);
        float sweep = 2 * reach.radius(), tall = Math.max(0.3F, reach.top() - reach.bottom());
        float sin = Math.abs(Mth.sin(pose.pitch() * Mth.DEG_TO_RAD)), cos = Math.abs(Mth.cos(pose.pitch() * Mth.DEG_TO_RAD));
        float pixelsPerBlock = Math.min(height * (1 - TOP_MARGIN) / (tall * cos + sweep * sin),
                width * (1 - 2 * SIDE_MARGIN) / sweep) * pose.scale();
        // Lift what hangs below the feet onto the edge, and, seen from above or below, the near or far side of what
        // it sweeps, which dips below them.
        float lift = -reach.bottom() * cos + sweep / 2 * sin;
        canvas.drawScene(Scene.entity(state, lift, rotation, view), pixelsPerBlock, tint, x, y, width, height);
    }
}
