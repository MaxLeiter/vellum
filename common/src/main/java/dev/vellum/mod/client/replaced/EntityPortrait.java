package dev.vellum.mod.client.replaced;

import dev.vellum.engine.style.EntityFocus;
import dev.vellum.engine.style.Length;
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
import java.util.function.BiFunction;

/**
 * Draws a live entity fitted into a box, as vanilla's inventory does for the player, as a {@link Scene} (a
 * picture-in-picture render, so it can be tinted and faded). Any number of entities, even of one type, can be drawn
 * in a frame.
 *
 * <p>The body fit: the entity is drawn at the largest size at which its model ({@link EntityReach}: heads, tails, arms
 * and tentacles reach past bounding boxes) fits under a small margin at the top and sides, seen at the current pitch
 * and at any turn. So tall mobs fit the height and long or wide ones the width, and spinning one never changes its
 * size. The room it takes is placed by {@code object-position}; unset, it stands on the bottom edge, centred, and
 * parts that hang below the feet (a ghast's tentacles) rest on the edge instead.
 *
 * <p>The eyes fit: the box's shorter side spans 0.7 of the entity's eye height, which frames a player's head and
 * shoulders, and {@code object-position} places the point at its eye height on its upright axis ({@code 50% 40%}
 * unset). Turning, tilting and gazing keep that point where it is.
 *
 * <p>The render state comes from the function a mod registered for the entity's type
 * ({@code VellumEntities.registerPortraitState}), else from its renderer; {@link #draw} documents what it changes.
 */
public final class EntityPortrait {
    /** Free space above the entity, as a fraction of the box height. */
    private static final float TOP_MARGIN = 0.08f;
    /** Free space on each side, as a fraction of the box width. */
    private static final float SIDE_MARGIN = 0.05f;
    /** With the eyes focus, the box's shorter side spans this much of the eye height... */
    private static final float EYES_SPAN = 0.7f;
    /** ...and at least this many blocks, so mobs with their eyes near the ground (silverfish) still show a head. */
    private static final float MIN_EYES_SPAN = 0.4f;
    /** Where the eyes go when {@code object-position} is unset. */
    private static final Length EYES_X = Length.PERCENT_50, EYES_Y = Length.percent(40);

    /** Render state functions mods registered, by entity type. */
    private static final Map<EntityType<?>, BiFunction<?, Float, ? extends @Nullable EntityRenderState>> STATES = new ConcurrentHashMap<>();

    /**
     * How the entity is shown, in degrees. {@code yaw} turns it (0 faces the viewer, positive turns its front to the
     * right) and {@code pitch} views it from above (positive) or below. {@code gaze} turns its head (null: see
     * {@link #draw}); {@code scale} multiplies the size that fits the box and {@code walk} swings its limbs at that
     * speed (0 stands still).
     */
    public record Pose(float yaw, float pitch, @Nullable Gaze gaze, float scale, float walk) {
        public static final Pose FRONT = new Pose(0, 0, null, 1, 0);
    }

    /**
     * Turns the head toward a point, in degrees (right and up are positive), and, as in vanilla's inventory, leans
     * the body that way.
     */
    public record Gaze(float yaw, float pitch) {}

    /**
     * What fills the box ({@code -mc-entity-focus}) and where it goes ({@code object-position}'s axes, which are
     * {@link Length#AUTO} when unset).
     */
    public record Framing(EntityFocus focus, Length x, Length y) {
        public static final Framing BODY = new Framing(EntityFocus.BODY, Length.AUTO, Length.AUTO);
    }

    /**
     * Display entities get negative ids: renderers need one, and real entities' ids are positive. They never tick, so
     * their idle animations play on the clock.
     */
    private static int nextDisplayId = -1;

    private EntityPortrait() {}

    /** For {@code VellumEntities.registerPortraitState}: GUI renders of {@code type} start from {@code state}. */
    public static <T extends Entity> void registerState(EntityType<T> type,
                                                        BiFunction<? super T, Float, ? extends @Nullable EntityRenderState> state) {
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
     * Where an entity drawn into the box {@code (x, y, width, height)} with {@code framing} has its eyes, as far as
     * gazing goes: the eye point with the eyes focus, else a third down the middle of the box, as vanilla's
     * inventory has it.
     */
    public static float[] gazeOrigin(Framing framing, float x, float y, float width, float height) {
        if (framing.focus() == EntityFocus.EYES) return eyePoint(framing, x, y, width, height);
        return new float[] {x + width / 2, y + height / 3};
    }

    /**
     * Draws {@code entity} into the box {@code (x, y, width, height)} in the canvas's current transform, multiplied by
     * {@code tint} (ARGB; white for none). Nothing is extracted for a box that is clipped away or transparent.
     *
     * <p>Whatever its render state came from, a GUI picture has no shadow, outline, name tag, score, leash or
     * passenger offset, is lit full bright, and is posed here: the body's turn ({@code bodyRot}), its limbs
     * ({@code walkAnimationPos}, {@code walkAnimationSpeed}) and its size ({@code scale} is 1, its bounding box and
     * eye height as at scale 1) are set from the pose; display entities' {@code ageInTicks} follows the clock. The
     * head ({@code yRot}, {@code xRot}) turns to the gaze, or without one looks ahead, except that a state a mod
     * supplied keeps its own head.
     */
    public static void draw(McCanvas canvas, Entity entity, Pose pose, Framing framing, int tint, float x, float y, float width, float height) {
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

        Gaze gaze = pose.gaze();
        float gazeYaw = gaze == null ? 0 : gaze.yaw(), gazePitch = gaze == null ? 0 : gaze.pitch();
        // Leaning toward the gaze tilts the view the other way: looking up shows it from below.
        float tilt = gazePitch - pose.pitch();
        Quaternionf view = new Quaternionf().rotateX(tilt * Mth.DEG_TO_RAD);
        Quaternionf rotation = new Quaternionf().rotateZ(Mth.PI).mul(view);
        if (state instanceof LivingEntityRenderState living) {
            living.bodyRot = 180.0F - pose.yaw() - gazeYaw;
            if (gaze != null || supplied == null) {
                living.yRot = -gazeYaw;
                living.xRot = -gazePitch;
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
        float pixelsPerBlock, originX, originY;
        if (framing.focus() == EntityFocus.EYES) {
            pixelsPerBlock = Math.min(width, height) / Math.max(MIN_EYES_SPAN, EYES_SPAN * state.eyeHeight) * pose.scale();
            float[] eyes = eyePoint(framing, x, y, width, height);
            // The eye point is on the upright axis, so turning leaves it be; tilting the view swings it about the feet.
            originX = eyes[0];
            originY = eyes[1] + state.eyeHeight * Math.abs(Mth.cos(tilt * Mth.DEG_TO_RAD)) * pixelsPerBlock;
        } else {
            EntityReach reach = EntityReach.of(state, supplied != null);
            float sweep = 2 * reach.radius(), tall = Math.max(0.3F, reach.top() - reach.bottom());
            float sin = Math.abs(Mth.sin(pose.pitch() * Mth.DEG_TO_RAD)), cos = Math.abs(Mth.cos(pose.pitch() * Mth.DEG_TO_RAD));
            pixelsPerBlock = Math.min(height * (1 - TOP_MARGIN) / (tall * cos + sweep * sin),
                    width * (1 - 2 * SIDE_MARGIN) / sweep) * pose.scale();
            // The room it takes, seen from this pitch, placed in the box. Its origin is lifted off the room's bottom by
            // what hangs below the feet and, seen from above or below, by the near or far side of what it sweeps.
            float roomWidth = sweep * pixelsPerBlock, roomHeight = (tall * cos + sweep * sin) * pixelsPerBlock;
            float lift = -reach.bottom() * cos + sweep / 2 * sin;
            originX = x + framing.x().resolve(width - roomWidth, (width - roomWidth) / 2) + roomWidth / 2;
            originY = y + framing.y().resolve(height - roomHeight, height - roomHeight) + roomHeight - lift * pixelsPerBlock;
        }
        float dx = (originX - x - width / 2) / pixelsPerBlock, up = (y + height - originY) / pixelsPerBlock;
        canvas.drawScene(Scene.entity(state, dx, up, rotation, view), pixelsPerBlock, tint, x, y, width, height);
    }

    /** The state the function registered for the entity's type makes, or null when there is none or it made none. */
    @SuppressWarnings("unchecked")
    private static @Nullable EntityRenderState supplied(Entity entity, float partialTick) {
        BiFunction<Entity, Float, ? extends @Nullable EntityRenderState> state =
                (BiFunction<Entity, Float, ? extends @Nullable EntityRenderState>) STATES.get(entity.getType());
        return state == null ? null : state.apply(entity, partialTick);
    }

    /** Where the eyes focus puts the eye point in the box: {@code object-position}, or {@code 50% 40%} unset. */
    private static float[] eyePoint(Framing framing, float x, float y, float width, float height) {
        Length ex = framing.x().isAuto() ? EYES_X : framing.x(), ey = framing.y().isAuto() ? EYES_Y : framing.y();
        return new float[] {x + ex.resolve(width), y + ey.resolve(height)};
    }
}
