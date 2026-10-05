package dev.vellum.mod.client.replaced;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.UvMapping;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * How far an entity's model reaches, in blocks from its feet: {@code radius} from its upright axis (what it sweeps as
 * it turns), {@code bottom} (negative when parts hang below its feet, like a ghast's tentacles) and {@code top}. Heads,
 * tails, arms and tentacles reach past bounding boxes.
 *
 * <p>Measured by submitting the entity as its renderer would draw it (with every renderer's own transforms and
 * scales) into a collector that only looks at where the models' cubes are. Renderers that draw something else than
 * models (an ender dragon's custom geometry) are measured by their bounding box.
 */
record EntityReach(float radius, float bottom, float top) {
    /** Measures {@code state}, never less than its bounding box. */
    static EntityReach of(EntityRenderState state) {
        Collector collector = new Collector();
        Minecraft.getInstance().getEntityRenderDispatcher().submit(state, new CameraRenderState(), 0, 0, 0, new PoseStack(), collector);
        float boxRadius = state.boundingBoxWidth / (float) Math.sqrt(2), height = state.boundingBoxHeight;
        if (!collector.measured) return new EntityReach(state.boundingBoxWidth, 0, height); // turning takes twice the box
        return new EntityReach(Math.max(boxRadius, collector.radius), Math.min(0, collector.bottom), Math.max(height, collector.top));
    }

    /** Takes every submitted model's cubes, as posed for drawing; ignores everything else. */
    private static final class Collector extends SubmitNodeStorage {
        boolean measured;
        float radius, bottom = Float.POSITIVE_INFINITY, top = Float.NEGATIVE_INFINITY;

        @Override
        public <S> void submitModel(Model<? super S> model, S state, PoseStack pose, RenderType renderType, int lightCoords,
                                    int overlayCoords, int tintedColor, @Nullable UvMapping uvMapping, int outlineColor) {
            model.setupAnim(state);
            model.root().getExtentsForGui(pose, this::include);
        }

        private void include(Vector3fc p) {
            measured = true;
            radius = Math.max(radius, (float) Math.sqrt(p.x() * p.x() + p.z() * p.z()));
            bottom = Math.min(bottom, p.y());
            top = Math.max(top, p.y());
        }
    }
}
