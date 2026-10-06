package dev.vellum.mod.client.replaced;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.entity.EntityType;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * How far an entity's model reaches, in blocks from its feet: {@code radius} from its upright axis (what it sweeps as
 * it turns), {@code bottom} (negative when parts hang below its feet, like a ghast's tentacles) and {@code top}. Heads,
 * tails, arms and tentacles reach past bounding boxes.
 *
 * <p>Measured by submitting the entity as its renderer would draw it (with every renderer's own transforms and
 * scales, and every layer, whatever order it is submitted in) into a collector that only looks at where the models'
 * cubes are. Renderers that draw something else than models (an ender dragon's custom geometry) are measured by their
 * bounding box. Measured once per entity type, age and size (slimes come in sizes), and separately for states a mod
 * supplied (which may show other parts), in a neutral pose.
 */
record EntityReach(float radius, float bottom, float top) {
    private record Key(EntityType<?> type, boolean baby, float width, float height, boolean supplied) {}

    private static final Map<Key, EntityReach> MEASURED = new HashMap<>();

    /**
     * The reach of the entity {@code state} shows, never less than its bounding box; {@code supplied} when a mod's
     * function made the state.
     */
    static EntityReach of(EntityRenderState state, boolean supplied) {
        Key key = new Key(state.entityType, state instanceof LivingEntityRenderState living && living.isBaby,
                state.boundingBoxWidth, state.boundingBoxHeight, supplied);
        EntityReach reach = MEASURED.get(key);
        if (reach == null) MEASURED.put(key, reach = measure(state));
        return reach;
    }

    /**
     * Measures {@code state} in a neutral pose (looking ahead, standing still, at age 0), so the first pose a type
     * happens to be drawn in (a turned head, a stride) doesn't size every later portrait of it. The state's own pose
     * is put back afterwards.
     */
    private static EntityReach measure(EntityRenderState state) {
        float age = state.ageInTicks;
        state.ageInTicks = 0;
        if (!(state instanceof LivingEntityRenderState living)) {
            try {
                return submitted(state);
            } finally {
                state.ageInTicks = age;
            }
        }
        float yRot = living.yRot, xRot = living.xRot, walkPos = living.walkAnimationPos, walkSpeed = living.walkAnimationSpeed;
        living.yRot = living.xRot = living.walkAnimationPos = living.walkAnimationSpeed = 0;
        try {
            return submitted(state);
        } finally {
            state.ageInTicks = age;
            living.yRot = yRot;
            living.xRot = xRot;
            living.walkAnimationPos = walkPos;
            living.walkAnimationSpeed = walkSpeed;
        }
    }

    private static EntityReach submitted(EntityRenderState state) {
        Collector collector = new Collector();
        Minecraft.getInstance().getEntityRenderDispatcher().submit(state, new CameraRenderState(), 0, 0, 0, new PoseStack(), collector);
        Measure m = collector.measure;
        float boxRadius = state.boundingBoxWidth / (float) Math.sqrt(2), height = state.boundingBoxHeight;
        if (m.bottom == Float.POSITIVE_INFINITY) return new EntityReach(state.boundingBoxWidth, 0, height); // turning takes twice the box
        return new EntityReach(Math.max(boxRadius, m.radius), Math.min(0, m.bottom), Math.max(height, m.top));
    }

    /** Routes every submit, whatever its order, to one collection that measures models. */
    private static final class Collector extends SubmitNodeStorage {
        final Measure measure = new Measure();

        @Override
        public SubmitNodeCollection order(int order) {
            return measure;
        }
    }

    /** Takes every submitted model's cubes, as posed for drawing; drops everything else. */
    private static final class Measure extends SubmitNodeCollection {
        float radius, bottom = Float.POSITIVE_INFINITY, top = Float.NEGATIVE_INFINITY;

        Measure() {
            super();
        }

        @Override
        public <S> void submitModel(Model<? super S> model, S state, PoseStack pose, RenderType renderType, int lightCoords,
                                    int overlayCoords, int tintedColor, @Nullable TextureAtlasSprite sprite, int outlineColor,
                                    ModelFeatureRenderer.@Nullable CrumblingOverlay crumblingOverlay) {
            model.setupAnim(state);
            model.root().getExtentsForGui(pose, this::include);
        }

        private void include(Vector3fc p) {
            radius = Math.max(radius, (float) Math.sqrt(p.x() * p.x() + p.z() * p.z()));
            bottom = Math.min(bottom, p.y());
            top = Math.max(top, p.y());
        }
    }
}
