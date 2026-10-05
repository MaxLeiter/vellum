package dev.vellum.mod.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2fc;
import org.jspecify.annotations.Nullable;

/**
 * Arbitrary quads with per-vertex colours (and UVs when textured), for geometry vanilla has no call for: fractional
 * rectangles, rounded corners, gradients, borders, rotated images. On {@code RenderPipelines.GUI} (POSITION_COLOR,
 * QUADS) or {@code GUI_TEXTURED}; GuiRenderer batches them with vanilla's own elements.
 *
 * @param xy     4 vertices per quad, already in vanilla's winding (GUI pipelines cull back faces)
 * @param uv     4 UVs per quad, or null for untextured pipelines
 * @param colors one ARGB colour per vertex
 * @param bounds the transformed bounding box clipped to the scissor; never null (null elements are dropped)
 */
record QuadsRenderState(RenderPipeline pipeline, TextureSetup textureSetup, Matrix3x2fc pose, float[] xy, float @Nullable [] uv,
                        int[] colors, @Nullable ScreenRectangle scissorArea, ScreenRectangle bounds) implements GuiElementRenderState {
    @Override
    public void buildVertices(VertexConsumer consumer) {
        for (int i = 0, n = colors.length; i < n; i++) {
            VertexConsumer v = consumer.addVertexWith2DPose(pose, xy[i * 2], xy[i * 2 + 1]);
            if (uv != null) v.setUv(uv[i * 2], uv[i * 2 + 1]);
            v.setColor(colors[i]);
        }
    }
}
