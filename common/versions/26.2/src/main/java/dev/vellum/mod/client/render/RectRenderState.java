package dev.vellum.mod.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2fc;
import org.jspecify.annotations.Nullable;

/**
 * One rectangle in one colour, at fractional coordinates under any transform: {@link McCanvas}'s fills and texture
 * blits, which vanilla only offers at whole pixels. The vertices go (x0,y0) (x0,y1) (x1,y1) (x1,y0), vanilla's winding
 * when x0 < x1 and y0 < y1 (McCanvas swaps x0 and x1 under a mirroring transform, as GUI pipelines cull back faces).
 *
 * @param textured whether to emit UVs ({@code GUI_TEXTURED}) or not ({@code GUI})
 * @param bounds   the transformed bounding box clipped to the scissor; never null (null elements are dropped)
 */
record RectRenderState(RenderPipeline pipeline, TextureSetup textureSetup, Matrix3x2fc pose, float x0, float y0, float x1,
                       float y1, boolean textured, float u0, float v0, float u1, float v1, int color,
                       @Nullable ScreenRectangle scissorArea, ScreenRectangle bounds) implements GuiElementRenderState {
    @Override
    public void buildVertices(VertexConsumer consumer) {
        vertex(consumer, x0, y0, u0, v0);
        vertex(consumer, x0, y1, u0, v1);
        vertex(consumer, x1, y1, u1, v1);
        vertex(consumer, x1, y0, u1, v0);
    }

    private void vertex(VertexConsumer consumer, float x, float y, float u, float v) {
        VertexConsumer vertex = consumer.addVertexWith2DPose(pose, x, y);
        if (textured) vertex.setUv(u, v);
        vertex.setColor(color);
    }
}
