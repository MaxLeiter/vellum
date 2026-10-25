//? if >=26 {
package dev.vellum.mod.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
//? if >=26.3
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
//? if <26.3
//import com.mojang.blaze3d.PrimitiveTopology;
import dev.vellum.engine.paint.SmoothShape;
import dev.vellum.mod.Constants;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2fc;
import org.jspecify.annotations.Nullable;

/**
 * One rounded box, border or box-shadow drawn by the signed-distance shader ({@code assets/vellum/shaders/core/sdf_box}):
 * a single quad whose four vertices carry the whole shape, the shader working out each pixel's coverage. See
 * {@link McCanvas#fillRoundedRect}, {@link McCanvas#fillBorder} and {@link McCanvas#fillBoxShadow}.
 *
 * <p>Vertex layout (GuiRenderer binds no custom uniforms, so the shape travels per vertex in vanilla's attribute names,
 * which are all {@code BufferBuilder} can write), in terms of the {@link SmoothShape}:
 * <ul>
 *   <li>{@code Position}: the quad corner on screen; z is {@code b}.</li>
 *   <li>{@code Color}: the colour, opacity included.</li>
 *   <li>{@code UV0}: the corner in the box's local space, from {@code (cx, cy)}.</li>
 *   <li>{@code UV3}: the box's half width and height.</li>
 *   <li>{@code UV1}, {@code UV2}: the corner radii in 1/16 px, top-left, top-right, bottom-right, bottom-left.</li>
 *   <li>{@code LineWidth}: {@code a}.</li>
 *   <li>{@code Normal} (shadows): x = inset, y = leave the box uncovered.</li>
 * </ul>
 *
 * <p>26.2's {@code BufferBuilder} writes no {@code UV3}, so there the quad is the box grown by one margin on every
 * side, {@code Position.z} carries that margin, and the shader takes the half size as {@code |UV0|} minus it (the
 * {@code VELLUM_26_2} parts of the shader). A shadow's {@code UV1} then carries its one corner radius and {@code UV2}
 * the bits of {@code b}, low then high.
 *
 * @param shape  the shape, in the canvas's coordinates under {@code pose}
 * @param edge   room for the antialiased edge, added to the shape's quad on every side: a device pixel
 * @param radii  the shape's corner radii in 1/16 px, top-left, top-right, bottom-right, bottom-left
 * @param color  the shape's colour with the canvas's opacity
 * @param bounds the transformed bounding box clipped to the scissor; never null (null elements are dropped)
 */
record SdfRenderState(Matrix3x2fc pose, SmoothShape shape, float edge, short[] radii, int color,
                      @Nullable ScreenRectangle scissorArea, ScreenRectangle bounds) implements GuiElementRenderState {
    /** Rounded boxes and their borders. */
    static final RenderPipeline BOX = pipeline("sdf_box", false);
    /** Box shadows, outer and inset. */
    static final RenderPipeline SHADOW = pipeline("sdf_shadow", true);

    private static boolean compiled;
    /** The pipelines failed to compile; they are not asked for again until resources reload. */
    private static boolean failed;

    /**
     * Whether the pipelines are compiled and usable: compiled the first time they are asked for (vanilla compiles
     * pipelines it was not told about on first use). A failure (the shaders not loaded yet, a driver that rejects them) is
     * logged, and McCanvas tessellates until {@link #reload} asks again.
     */
    static boolean available() {
        if (compiled || failed) return compiled;
        RuntimeException error = null;
        try {
            //? if >=26.3 {
            compiled = RenderSystem.getCompiledPipelineNullable(BOX) != null && RenderSystem.getCompiledPipelineNullable(SHADOW) != null;
            //?} else {
            /*// 26.2's device keeps a pipeline that failed until resources reload, so asking again only helps after one.
            var device = RenderSystem.getDevice();
            compiled = device.precompilePipeline(BOX).isValid() && device.precompilePipeline(SHADOW).isValid();
            *///?}
        } catch (RuntimeException e) {
            error = e;
        }
        if (!compiled) {
            failed = true;
            Constants.LOG.warn("Vellum's shape shader is not available; drawing shapes as quads until resources reload", error);
        }
        return compiled;
    }

    /** Resources reloaded, shaders with them: {@link #available} compiles the pipelines again the next time it is asked. */
    static void reload() {
        compiled = false;
        failed = false;
    }

    private static RenderPipeline pipeline(String name, boolean shadow) {
        Identifier shader = Constants.id("core/sdf_box");
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(Constants.id("pipeline/" + name))
                .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                .withVertexShader(shader)
                .withFragmentShader(shader)
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withVertexBinding(0, format(shadow))
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withCull(false); // one quad per shape, in any winding a transform leaves it
        if (shadow) builder.withShaderDefine("SHADOW");
        //? if <26.3
        //builder.withShaderDefine("VELLUM_26_2");
        return builder.build();
    }

    /** The vertex layout above; shadows add {@code Normal}. */
    private static VertexFormat format(boolean shadow) {
        VertexFormat.Builder builder = VertexFormat.builder(0)
                .addAttribute("Position", GpuFormat.RGB32_FLOAT)
                .addAttribute("Color", GpuFormat.RGBA8_UNORM)
                .addAttribute("UV0", GpuFormat.RG32_FLOAT)
                //? if >=26.3
                .addAttribute("UV3", GpuFormat.RG32_FLOAT)
                .addAttribute("UV1", GpuFormat.RG16_SINT)
                .addAttribute("UV2", GpuFormat.RG16_SINT)
                .addAttribute("LineWidth", GpuFormat.R32_FLOAT);
        if (shadow) builder.addAttribute("Normal", GpuFormat.RGBA8_SNORM);
        return builder.build();
    }

    @Override
    public RenderPipeline pipeline() {
        return shape.shadow() ? SHADOW : BOX;
    }

    @Override
    public TextureSetup textureSetup() {
        return TextureSetup.noTexture();
    }

    //? if >=26.3 {
    @Override
    public void buildVertices(VertexConsumer consumer) {
        float x0 = shape.x0() - edge, y0 = shape.y0() - edge, x1 = shape.x1() + edge, y1 = shape.y1() + edge;
        vertex(consumer, x0, y0);
        vertex(consumer, x0, y1);
        vertex(consumer, x1, y1);
        vertex(consumer, x1, y0);
    }

    /** The vertex at (x, y) in the canvas's coordinates; the pose applied as {@code Matrix3x2f.transformPosition} does. */
    private void vertex(VertexConsumer consumer, float x, float y) {
        consumer.addVertex(pose.m00() * x + pose.m10() * y + pose.m20(), pose.m01() * x + pose.m11() * y + pose.m21(), shape.b())
                .setColor(color)
                .setUv(x - shape.cx(), y - shape.cy())
                .setUv3(shape.halfWidth(), shape.halfHeight())
                .setUv1(radii[0], radii[1])
                .setUv2(radii[2], radii[3])
                .setLineWidth(shape.a());
        if (shape.shadow()) consumer.setNormal(shape.inset() ? 1 : 0, shape.cutout() ? 1 : 0, 0);
    }
    //?} else {
    /*@Override
    public void buildVertices(VertexConsumer consumer) {
        float x0 = shape.x0() - edge, y0 = shape.y0() - edge, x1 = shape.x1() + edge, y1 = shape.y1() + edge;
        float cx = shape.cx(), cy = shape.cy(), halfWidth = shape.halfWidth(), halfHeight = shape.halfHeight();
        // The same margin on every side, at least as much as the quad asked for on each.
        float margin = Math.max(Math.max(cx - x0, x1 - cx) - halfWidth, Math.max(cy - y0, y1 - cy) - halfHeight);
        float ex = halfWidth + margin, ey = halfHeight + margin;
        vertex(consumer, -ex, -ey, margin);
        vertex(consumer, -ex, ey, margin);
        vertex(consumer, ex, ey, margin);
        vertex(consumer, ex, -ey, margin);
    }

    /^* The vertex at (u, v) from the box's centre; the pose applied as {@code Matrix3x2f.transformPosition} does. ^/
    private void vertex(VertexConsumer consumer, float u, float v, float margin) {
        float x = shape.cx() + u, y = shape.cy() + v;
        consumer.addVertex(pose.m00() * x + pose.m10() * y + pose.m20(), pose.m01() * x + pose.m11() * y + pose.m21(), margin)
                .setColor(color)
                .setUv(u, v)
                .setLineWidth(shape.a());
        if (shape.shadow()) {
            int bits = Float.floatToRawIntBits(shape.b());
            consumer.setUv1(radii[0], radii[0]).setUv2(bits & 0xFFFF, bits >>> 16).setNormal(shape.inset() ? 1 : 0, shape.cutout() ? 1 : 0, 0);
        } else {
            consumer.setUv1(radii[0], radii[1]).setUv2(radii[2], radii[3]);
        }
    }
    *///?}
}
//?}
