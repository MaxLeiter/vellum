package dev.vellum.mod.client.replaced;

import dev.vellum.mod.client.render.McCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Draws a live entity fitted into a box, as vanilla's inventory does for the player: a picture-in-picture render
 * through {@code GuiGraphicsExtractor.entity}. Several entities (even of one type) can be drawn in one frame: both
 * loaders pool picture-in-picture renderers per frame, where vanilla alone keeps one per render-state class.
 */
public final class EntityPortrait {
    /**
     * How the entity is posed, in degrees. {@code yaw} turns the body (0 faces the viewer), {@code headYaw} turns the
     * head further, {@code pitch} tilts the head (and the view) up for positive values; {@code scale} multiplies the
     * size that fits the box; {@code walkPhase}/{@code walkSpeed} drive the limb animation (0/0 stands still).
     */
    public record Pose(float yaw, float headYaw, float pitch, float scale, float walkPhase, float walkSpeed) {
        public static final Pose FRONT = new Pose(0, 0, 0, 1, 0, 0);
    }

    /** Display entities get negative ids: renderers need one, and real entities' ids are positive. */
    private static int nextDisplayId = -1;

    private EntityPortrait() {}

    /** An entity for display only, never added to the world. */
    public static @Nullable Entity create(EntityType<?> type, Level level) {
        Entity entity = type.create(level, EntitySpawnReason.LOAD);
        if (entity != null) entity.setId(nextDisplayId--);
        return entity;
    }

    /** Draws {@code entity} into the box {@code (x, y, width, height)} in the canvas's current transform. */
    public static void draw(McCanvas canvas, Entity entity, Pose pose, float x, float y, float width, float height) {
        EntityRenderState state = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity).createRenderState(entity, 1.0F);
        state.shadowPieces.clear();
        state.outlineColor = 0;
        state.nameTag = null;

        Quaternionf tilt = new Quaternionf().rotateX(pose.pitch() * Mth.DEG_TO_RAD);
        Quaternionf rotation = new Quaternionf().rotateZ(Mth.PI).mul(tilt);
        if (state instanceof LivingEntityRenderState living) {
            living.bodyRot = 180.0F + pose.yaw();
            living.yRot = pose.headYaw();
            living.xRot = -pose.pitch();
            living.walkAnimationPos = pose.walkPhase();
            living.walkAnimationSpeed = pose.walkSpeed();
            // Ignore the entity's own scale (babies, scaled mobs): fit the box instead.
            living.boundingBoxWidth /= living.scale;
            living.boundingBoxHeight /= living.scale;
            living.scale = 1.0F;
        } else {
            rotation.mul(new Quaternionf().rotateY(pose.yaw() * Mth.DEG_TO_RAD));
        }

        // Fit the bounding box, with a little margin, into the box.
        float bw = Math.max(0.3F, state.boundingBoxWidth), bh = Math.max(0.3F, state.boundingBoxHeight);
        float pixelsPerBlock = Math.min(height / (bh * 1.1F), width / (bw * 1.6F)) * pose.scale();
        canvas.drawEntity(state, pixelsPerBlock, new Vector3f(0, bh / 2, 0), rotation, tilt, x, y, width, height);
    }
}
