package dev.vellum.mod.client.replaced;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.EntityFocus;
import dev.vellum.engine.style.EntityFraming;
import dev.vellum.mod.client.render.McCanvas;
import dev.vellum.mod.client.render.Scene;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws a live entity fitted into a box, as vanilla's inventory does for the player, as a {@link Scene}. Any number of
 * entities, even of one type, can be drawn in a frame. The framing is the 26.x EntityPortrait's: the body fit (the
 * largest size at which the model fits under a small top and side margin at the current pitch and any turn, placed by
 * {@code object-position}) and the eyes fit ({@code -mc-entity-focus: eyes}).
 *
 * <p>On Minecraft 1.21.1 entities have no render states: the entity's own fields are posed for the draw and put back
 * after it, as vanilla's inventory poses the player. Its body turn, head, walk and age are set from the pose; its scale
 * attribute is divided out; it has no shadow and is lit full bright. A mod can't supply a render state of its own
 * ({@code VellumEntities.registerPortraitState} is 26.x only). The model's reach is measured from the vertices the
 * renderer draws, in a neutral pose. Display entities' idle animations follow the clock.
 */
public final class EntityPortrait {
    /** Free space above the entity, as a fraction of the box height. */
    private static final float TOP_MARGIN = 0.08f;
    /** Free space on each side, as a fraction of the box width. */
    private static final float SIDE_MARGIN = 0.05f;

    /**
     * How the entity is shown, in degrees. {@code yaw} turns it (0 faces the viewer, positive turns its front to the
     * right) and {@code pitch} views it from above (positive) or below. With {@code followMouse} it turns its head
     * toward the pointer ({@link #draw}); {@code scale} multiplies the size that fits the box and {@code walk} swings
     * its limbs at that speed (0 stands still).
     */
    public record Pose(float yaw, float pitch, boolean followMouse, float scale, float walk) {
        public static final Pose FRONT = new Pose(0, 0, false, 1, 0);
    }

    /** How far a type's model reaches, in blocks from its feet at scale 1 ({@link #reach}). */
    private record Reach(float radius, float bottom, float top) {}

    private record ReachKey(EntityType<?> type, boolean baby, float width, float height) {}

    private static final Map<ReachKey, Reach> MEASURED = new HashMap<>();

    /**
     * Display entities get negative ids: renderers need one, and real entities' ids are positive. They never tick, so
     * their idle animations play on the clock.
     */
    private static int nextDisplayId = -1;

    private EntityPortrait() {}

    /** An entity for display only, never added to the world. */
    public static @Nullable Entity create(EntityType<?> type, Level level) {
        if (!type.isEnabled(level.enabledFeatures())) return null;
        Entity entity = type.create(level);
        if (entity != null) entity.setId(nextDisplayId--);
        return entity;
    }

    /**
     * Draws {@code entity} into the box {@code (x, y, width, height)} in the canvas's current transform, tinted by
     * {@code tint} (ARGB; white for none; models can't fade on 1.21.1, so under half opacity nothing is drawn),
     * framed by {@code style}'s {@code -mc-entity-focus} and {@code object-position}. With {@code followMouse} the head
     * turns toward the canvas's pointer as vanilla's inventory turns the player's, as far as {@code style}'s
     * {@code -mc-gaze-reach} and {@code -mc-gaze-limit} let it, and the body leans half of that turn; otherwise the
     * head looks ahead.
     */
    public static void draw(McCanvas canvas, Entity entity, Pose pose, ComputedStyle style, int tint, float x, float y, float width, float height) {
        if (!canvas.sceneVisible(tint, x, y, width, height)) return;
        Minecraft mc = Minecraft.getInstance();
        boolean display = entity.getId() < 0;
        float partialTick = display ? Util.getMillis() % 50 / 50F : mc.getTimer().getGameTimeDeltaPartialTick(false);
        float age = display ? Util.getMillis() / 50F : entity.tickCount + partialTick;

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
        LivingEntity living = entity instanceof LivingEntity l ? l : null;
        if (living == null) rotation.mul(new Quaternionf().rotateY(pose.yaw() * Mth.DEG_TO_RAD));

        // Sizes at scale 1: the entity's own scale (scaled mobs) is divided out, to fit the box instead.
        float ownScale = living != null ? living.getScale() : 1;
        float eyeHeight = entity.getEyeHeight() / ownScale;
        EntityFraming frame;
        if (style.entityFocus == EntityFocus.EYES) {
            frame = EntityFraming.eyes(eyes[0], eyes[1], width, height, eyeHeight, tilt, pose.scale());
        } else {
            Reach reach = reach(entity, ownScale);
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
        // The entity's scale attribute is applied by its renderer: a block of the picture is pixelsPerBlock / ownScale.
        Scene scene = Scene.entity(entity, partialTick, dx * ownScale, up * ownScale, rotation, view);

        Posed posed = Posed.save(entity, living);
        try {
            if (display) entity.tickCount = (int) age;
            if (living != null) {
                float body = 180.0F - pose.yaw() - leanYaw;
                living.yBodyRot = living.yBodyRotO = body;
                living.yHeadRot = living.yHeadRotO = body - leanYaw;
                living.setYRot(body);
                living.yRotO = body;
                living.setXRot(-leanPitch);
                living.xRotO = -leanPitch;
                living.walkAnimation.setSpeed(pose.walk());
                living.walkAnimation.position = age * pose.walk();
            }
            canvas.drawScene(scene, pixelsPerBlock / ownScale, tint, x, y, width, height);
        } finally {
            posed.restore(entity, living);
        }
    }

    /** The fields {@link #draw} poses, to put back: world entities keep moving after the GUI has drawn them. */
    private record Posed(int tickCount, float yRot, float yRotO, float xRot, float xRotO, float body, float bodyO,
                         float head, float headO, float walkSpeed, float walkPosition) {
        static Posed save(Entity e, @Nullable LivingEntity l) {
            return new Posed(e.tickCount, e.getYRot(), e.yRotO, e.getXRot(), e.xRotO, l == null ? 0 : l.yBodyRot, l == null ? 0 : l.yBodyRotO,
                    l == null ? 0 : l.yHeadRot, l == null ? 0 : l.yHeadRotO, l == null ? 0 : l.walkAnimation.speed(),
                    l == null ? 0 : l.walkAnimation.position);
        }

        void restore(Entity e, @Nullable LivingEntity l) {
            e.tickCount = tickCount;
            e.setYRot(yRot);
            e.yRotO = yRotO;
            e.setXRot(xRot);
            e.xRotO = xRotO;
            if (l == null) return;
            l.yBodyRot = body;
            l.yBodyRotO = bodyO;
            l.yHeadRot = head;
            l.yHeadRotO = headO;
            l.walkAnimation.setSpeed(walkSpeed);
            l.walkAnimation.position = walkPosition;
        }
    }

    /**
     * How far the entity's model reaches at scale 1, never less than its bounding box: measured once per type, age
     * and size by drawing it, in a neutral pose (looking ahead, standing still, at age 0), into a buffer that only
     * reads where the entity's vertices are.
     */
    private static Reach reach(Entity entity, float ownScale) {
        LivingEntity living = entity instanceof LivingEntity l ? l : null;
        float width = entity.getBbWidth() / ownScale, height = entity.getBbHeight() / ownScale;
        ReachKey key = new ReachKey(entity.getType(), living != null && living.isBaby(), width, height);
        Reach reach = MEASURED.get(key);
        if (reach == null) MEASURED.put(key, reach = measure(entity, living, width, height, ownScale));
        return reach;
    }

    private static Reach measure(Entity entity, @Nullable LivingEntity living, float width, float height, float ownScale) {
        Extent extent = new Extent();
        Posed posed = Posed.save(entity, living);
        try {
            entity.tickCount = 0;
            entity.setXRot(0);
            entity.xRotO = 0;
            if (living != null) {
                living.yHeadRot = living.yHeadRotO = living.yBodyRot;
                living.yBodyRotO = living.yBodyRot;
                living.walkAnimation.setSpeed(0);
                living.walkAnimation.position = 0;
            }
            var dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
            dispatcher.setRenderShadow(false);
            dispatcher.render(entity, 0, 0, 0, 0, 1, new PoseStack(),
                    type -> type.format() == DefaultVertexFormat.NEW_ENTITY ? extent : Extent.IGNORED, LightTexture.FULL_BRIGHT);
            dispatcher.setRenderShadow(true);
        } catch (RuntimeException e) {
            extent.bottom = Float.POSITIVE_INFINITY; // a renderer that can't draw without a world: the box it is
        } finally {
            posed.restore(entity, living);
        }
        float boxRadius = width / (float) Math.sqrt(2);
        if (extent.bottom == Float.POSITIVE_INFINITY) return new Reach(width, 0, height); // turning takes twice the box
        return new Reach(Math.max(boxRadius, extent.radius / ownScale), Math.min(0, extent.bottom / ownScale),
                Math.max(height, extent.top / ownScale));
    }

    /** Takes the positions of the vertices drawn into it; everything else is dropped. */
    private static final class Extent implements VertexConsumer {
        static final Extent IGNORED = new Extent();

        float radius, bottom = Float.POSITIVE_INFINITY, top = Float.NEGATIVE_INFINITY;

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            if (this != IGNORED) {
                radius = Math.max(radius, (float) Math.sqrt(x * x + z * z));
                bottom = Math.min(bottom, y);
                top = Math.max(top, y);
            }
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }
    }
}
