//? if >=26 {
package dev.vellum.mod.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
//? if >=26.3 {
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;
//?} else {
/*import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.vertex.VertexFormat;
*///?}
import dev.vellum.mod.Constants;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2fc;
import org.joml.Vector2f;
import org.jspecify.annotations.Nullable;

/**
 * One rounded box, border or box-shadow drawn by the signed-distance shader ({@code assets/vellum/shaders/core/sdf_box}):
 * a single quad whose four vertices carry the whole shape, the shader working out each pixel's coverage. See
 * {@link McCanvas#fillRoundedRect}, {@link McCanvas#fillBorder} and {@link McCanvas#fillBoxShadow}.
 *
 * <p>Vertex layout (GuiRenderer binds no custom uniforms, so the shape travels per vertex in vanilla's attribute names,
 * which are all {@code BufferBuilder} can write):
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
 * @param x0     the quad in local space: the shape's bounds, grown by the blur and a device pixel for the edge
 * @param cx     the origin of {@code UV0}: the box's centre, moved by the offset for an outer shadow
 * @param a      a box's border width (0 fills it); a shadow's sigma
 * @param b      a shadow's spread
 * @param bounds the transformed bounding box clipped to the scissor; never null (null elements are dropped)
 */
record SdfRenderState(RenderPipeline pipeline, Matrix3x2fc pose, float x0, float y0, float x1, float y1, float cx, float cy,
                      float halfWidth, float halfHeight, short[] radii, float a, float b, boolean inset, boolean cutout,
                      int color, @Nullable ScreenRectangle scissorArea, ScreenRectangle bounds) implements GuiElementRenderState {
    private static final VertexFormat BOX_FORMAT = VertexFormat.builder(0)
            .addAttribute("Position", GpuFormat.RGB32_FLOAT)
            .addAttribute("Color", GpuFormat.RGBA8_UNORM)
            .addAttribute("UV0", GpuFormat.RG32_FLOAT)
            //? if >=26.3
            .addAttribute("UV3", GpuFormat.RG32_FLOAT)
            .addAttribute("UV1", GpuFormat.RG16_SINT)
            .addAttribute("UV2", GpuFormat.RG16_SINT)
            .addAttribute("LineWidth", GpuFormat.R32_FLOAT)
            .build();
    private static final VertexFormat SHADOW_FORMAT = VertexFormat.builder(0)
            .addAttribute("Position", GpuFormat.RGB32_FLOAT)
            .addAttribute("Color", GpuFormat.RGBA8_UNORM)
            .addAttribute("UV0", GpuFormat.RG32_FLOAT)
            //? if >=26.3
            .addAttribute("UV3", GpuFormat.RG32_FLOAT)
            .addAttribute("UV1", GpuFormat.RG16_SINT)
            .addAttribute("UV2", GpuFormat.RG16_SINT)
            .addAttribute("LineWidth", GpuFormat.R32_FLOAT)
            .addAttribute("Normal", GpuFormat.RGBA8_SNORM)
            .build();

    /** Rounded boxes and their borders. */
    static final RenderPipeline BOX = pipeline("sdf_box", BOX_FORMAT, false);
    /** Box shadows, outer and inset. */
    static final RenderPipeline SHADOW = pipeline("sdf_shadow", SHADOW_FORMAT, true);

    /** How long to wait before compiling the pipelines again after they failed. */
    private static final long RETRY_NANOS = 5_000_000_000L;
    private static boolean compiled;
    private static long failedAt;
    private static boolean warned;

    /**
     * Whether the pipelines are compiled and usable: compiled the first time they are asked for (vanilla compiles
     * pipelines it was not told about on first use). A failure (the shaders not loaded yet, a driver that rejects them) is
     * logged once and asked again after a few seconds; until then McCanvas tessellates.
     */
    static boolean available() {
        if (compiled) return true;
        long now = System.nanoTime();
        if (failedAt != 0 && now - failedAt < RETRY_NANOS) return false;
        try {
            //? if >=26.3 {
            compiled = RenderSystem.getCompiledPipelineNullable(BOX) != null && RenderSystem.getCompiledPipelineNullable(SHADOW) != null;
            //?} else {
            /*// 26.2's device keeps a pipeline that failed until resources reload, so a retry only succeeds after one.
            var device = RenderSystem.getDevice();
            compiled = device.precompilePipeline(BOX).isValid() && device.precompilePipeline(SHADOW).isValid();
            *///?}
        } catch (RuntimeException e) {
            compiled = false;
            if (!warned) Constants.LOG.warn("Vellum's shape shader failed to compile; drawing shapes as quads", e);
            warned = true;
        }
        if (!compiled) {
            if (!warned) Constants.LOG.warn("Vellum's shape shader is not available; drawing shapes as quads");
            warned = true;
            failedAt = now == 0 ? 1 : now;
        }
        return compiled;
    }

    private static RenderPipeline pipeline(String name, VertexFormat format, boolean shadow) {
        Identifier shader = Constants.id("core/sdf_box");
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(Constants.id("pipeline/" + name))
                .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                .withVertexShader(shader)
                .withFragmentShader(shader)
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withVertexBinding(0, format)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withCull(false); // one quad per shape, in any winding a transform leaves it
        if (shadow) builder.withShaderDefine("SHADOW");
        //? if <26.3
        //builder.withShaderDefine("VELLUM_26_2");
        return builder.build();
    }

    @Override
    public TextureSetup textureSetup() {
        return TextureSetup.noTexture();
    }

    //? if >=26.3 {
    @Override
    public void buildVertices(VertexConsumer consumer) {
        vertex(consumer, x0, y0);
        vertex(consumer, x0, y1);
        vertex(consumer, x1, y1);
        vertex(consumer, x1, y0);
    }

    private void vertex(VertexConsumer consumer, float x, float y) {
        Vector2f screen = pose.transformPosition(x, y, new Vector2f());
        consumer.addVertex(screen.x, screen.y, b)
                .setColor(color)
                .setUv(x - cx, y - cy)
                .setUv3(halfWidth, halfHeight)
                .setUv1(radii[0], radii[1])
                .setUv2(radii[2], radii[3])
                .setLineWidth(a);
        if (pipeline == SHADOW) consumer.setNormal(inset ? 1 : 0, cutout ? 1 : 0, 0);
    }
    //?} else {
    /*@Override
    public void buildVertices(VertexConsumer consumer) {
        // The same margin on every side, at least as much as the quad asked for on each.
        float margin = Math.max(Math.max(cx - x0, x1 - cx) - halfWidth, Math.max(cy - y0, y1 - cy) - halfHeight);
        float ex = halfWidth + margin, ey = halfHeight + margin;
        vertex(consumer, -ex, -ey, margin);
        vertex(consumer, -ex, ey, margin);
        vertex(consumer, ex, ey, margin);
        vertex(consumer, ex, -ey, margin);
    }

    private void vertex(VertexConsumer consumer, float u, float v, float margin) {
        Vector2f screen = pose.transformPosition(cx + u, cy + v, new Vector2f());
        consumer.addVertex(screen.x, screen.y, margin)
                .setColor(color)
                .setUv(u, v)
                .setLineWidth(a);
        if (pipeline == SHADOW) {
            int bits = Float.floatToRawIntBits(b);
            consumer.setUv1(radii[0], radii[0]).setUv2(bits & 0xFFFF, bits >>> 16).setNormal(inset ? 1 : 0, cutout ? 1 : 0, 0);
        } else {
            consumer.setUv1(radii[0], radii[1]).setUv2(radii[2], radii[3]);
        }
    }
    *///?}
}
//?}
