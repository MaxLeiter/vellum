package dev.vellum.mod.client.replaced;

import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.EntityFocus;
import dev.vellum.engine.style.EntityFraming;
import dev.vellum.mod.client.VellumEntities;
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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws a live entity fitted into a box, as vanilla's inventory does for the player, as a {@link Scene} (a
 * picture-in-picture render, so it can be tinted and faded). Any number of entities, even of one type, can be drawn
 * in a frame.
 *
 * <p>The body fit: the entity is drawn at the largest size at which its model ({@link EntityReach}: heads, tails, arms
 * and tentacles reach past bounding boxes) fits under a small margin at the top and sides, seen at the current pitch
 * and at any turn. So tall mobs fit the height and long or wide ones the width, and spinning one never changes its
 * size. The room it takes is placed by {@code object-position} ({@link EntityFraming#body}); unset, it stands on the
 * bottom edge, centred, and parts that hang below the feet (a ghast's tentacles) rest on the edge instead.
 *
 * <p>The eyes fit ({@link EntityFraming#eyes}): the box's shorter side spans 0.7 of the entity's eye height, which
 * frames a player's head and shoulders, and {@code object-position} places the point at its eye height on its upright
 * axis ({@code 50% 40%} unset). Turning, tilting and gazing keep that point where it is.
 *
 * <p>The render state comes from the function a mod registered for the entity's type
 * ({@code VellumEntities.registerPortraitState}), else from its renderer; {@link #draw} documents what it changes.
 */
public final class EntityPortrait {
    /** Free space above the entity, as a fraction of the box height. */
    private static final float TOP_MARGIN = 0.08f;
    /** Free space on each side, as a fraction of the box width. */
    private static final float SIDE_MARGIN = 0.05f;

    /** Render state functions mods registered, by entity type. */
    private static final Map<EntityType<?>, VellumEntities.PortraitState<?>> STATES = new ConcurrentHashMap<>();

    /**
     * How the entity is shown, in degrees. {@code yaw} turns it (0 faces the viewer, positive turns its front to the
     * right) and {@code pitch} views it from above (positive) or below. With {@code followMouse} it turns its head
     * toward the pointer ({@link #draw}); {@code scale} multiplies the size that fits the box and {@code walk} swings
     * its limbs at that speed (0 stands still).
     */
    public record Pose(float yaw, float pitch, boolean followMouse, float scale, float walk) {
        public static final Pose FRONT = new Pose(0, 0, false, 1, 0);
    }

    /**
     * Display entities get negative ids: renderers need one, and real entities' ids are positive. They never tick, so
     * their idle animations play on the clock.
     */
    private static int nextDisplayId = -1;

    private EntityPortrait() {}

    /** For {@code VellumEntities.registerPortraitState}: GUI renders of {@code type} start from {@code state}. */
    public static <T extends Entity> void registerState(EntityType<T> type, VellumEntities.PortraitState<? super T> state) {
        STATES.put(type, state);
    }

    /** An entity for display only, never added to the world: monsters show in peaceful worlds too. */
    public static @Nullable Entity create(EntityType<?> type, Level level) {
        if (!type.isEnabled(level.enabledFeatures())) return null;
        Entity entity = type.create(level, new EntitySpawnRequest(EntitySpawnReason.LOAD, true));
        if (entity != null) entity.setId(nextDisplayId--);
        return entity;
    }

    /**
     * Draws {@code entity} into the box {@code (x, y, width, height)} in the canvas's current transform, multiplied by
     * {@code tint} (ARGB; white for none), framed by {@code style}'s {@code -mc-entity-focus} and
     * {@code object-position}. Nothing is extracted for a box that is clipped away or transparent.
     *
     * <p>Whatever its render state came from, a GUI picture has no shadow, outline, name tag, score, leash or
     * passenger offset, is lit full bright, and is posed here: the body's turn ({@code bodyRot}), its limbs
     * ({@code walkAnimationPos}, {@code walkAnimationSpeed}) and its size ({@code scale} is 1, its bounding box and
     * eye height as at scale 1) are set from the pose; display entities' {@code ageInTicks} follows the clock. With
     * {@code followMouse} the head ({@code yRot}, {@code xRot}) turns toward the canvas's pointer as vanilla's
     * inventory turns the player's, as far as {@code style}'s {@code -mc-gaze-reach} and {@code -mc-gaze-limit} let
     * it, and the body leans half of that turn. Otherwise the head looks ahead, except that a state a mod supplied
     * keeps its own head.
     */
    public static void draw(McCanvas canvas, Entity entity, Pose pose, ComputedStyle style, int tint, float x, float y, float width, float height) {
        if (!canvas.sceneVisible(tint, x, y, width, height)) return;
        float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        EntityRenderState supplied = supplied(entity, partialTick);
        EntityRenderState state = supplied != null ? supplied
                : Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity).createRenderState(entity, partialTick);
        state.shadowPieces.clear();
        state.outlineColor = 0;
        state.nameTag = null;
        state.scoreText = null;
        state.leashStates = null;
        state.passengerOffset = null;
        state.lightCoords = LightCoordsUtil.FULL_BRIGHT;
        if (entity.getId() < 0) state.ageInTicks = Util.getMillis() / 50F;

        // The point it looks from: with the eyes focus, also where its eyes go.
        float[] eyes = EntityFraming.gazeOrigin(style, x, y, width, height);
        boolean gazes = pose.followMouse() && canvas.mouseX() >= 0;
        float gazeYaw = gazes ? style.gazeYaw(canvas.mouseX() - eyes[0]) : 0;
        float gazePitch = gazes ? style.gazePitch(eyes[1] - canvas.mouseY()) : 0;
        // The body leans half of the gaze and the head turns the other half on top.
        float leanYaw = gazeYaw / 2, leanPitch = gazePitch / 2;
        // Leaning toward the gaze tilts the view the other way: looking up shows it from below.
        float tilt = leanPitch - pose.pitch();
        Quaternionf view = new Quaternionf().rotateX(tilt * Mth.DEG_TO_RAD);
        Quaternionf rotation = new Quaternionf().rotateZ(Mth.PI).mul(view);
        if (state instanceof LivingEntityRenderState living) {
            living.bodyRot = 180.0F - pose.yaw() - leanYaw;
            if (pose.followMouse() || supplied == null) {
                living.yRot = -leanYaw;
                living.xRot = -leanPitch;
            }
            living.walkAnimationPos = living.ageInTicks * pose.walk();
            living.walkAnimationSpeed = pose.walk();
            // Ignore the entity's own scale (scaled mobs): fit the box instead.
            living.boundingBoxWidth /= living.scale;
            living.boundingBoxHeight /= living.scale;
            living.eyeHeight /= living.scale;
            living.scale = 1.0F;
        } else {
            rotation.mul(new Quaternionf().rotateY(pose.yaw() * Mth.DEG_TO_RAD));
        }

        // Where the entity's origin (its feet, on its upright axis) goes, in local px, and how big a block is.
        EntityFraming frame;
        if (style.entityFocus == EntityFocus.EYES) {
            frame = EntityFraming.eyes(eyes[0], eyes[1], width, height, state.eyeHeight, tilt, pose.scale());
        } else {
            EntityReach reach = EntityReach.of(state, supplied != null);
            float sweep = 2 * reach.radius(), tall = Math.max(0.3F, reach.top() - reach.bottom());
            float sin = Math.abs(Mth.sin(pose.pitch() * Mth.DEG_TO_RAD)), cos = Math.abs(Mth.cos(pose.pitch() * Mth.DEG_TO_RAD));
            // The room it takes, seen from this pitch. Its origin is lifted off the room's bottom by what hangs below
            // the feet and, seen from above or below, by the near or far side of what it sweeps.
            float roomHeight = tall * cos + sweep * sin, lift = -reach.bottom() * cos + sweep / 2 * sin;
            float fitted = Math.min(height * (1 - TOP_MARGIN) / roomHeight, width * (1 - 2 * SIDE_MARGIN) / sweep) * pose.scale();
            frame = EntityFraming.body(style, x, y, width, height, fitted, sweep, roomHeight, lift);
        }
        float pixelsPerBlock = frame.pixelsPerBlock();
        float dx = (frame.originX() - x - width / 2) / pixelsPerBlock, up = (y + height - frame.originY()) / pixelsPerBlock;
        canvas.drawScene(Scene.entity(state, dx, up, rotation, view), pixelsPerBlock, tint, x, y, width, height);
    }

    /** The state the function registered for the entity's type makes, or null when there is none or it made none. */
    @SuppressWarnings("unchecked")
    private static @Nullable EntityRenderState supplied(Entity entity, float partialTick) {
        VellumEntities.PortraitState<Entity> state = (VellumEntities.PortraitState<Entity>) STATES.get(entity.getType());
        return state == null ? null : state.create(entity, partialTick);
    }
}
