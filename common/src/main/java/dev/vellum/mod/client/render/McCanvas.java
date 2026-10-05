package dev.vellum.mod.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.FontSpec;
import dev.vellum.engine.host.ReplacedContent;
import dev.vellum.engine.paint.Canvas;
import dev.vellum.mod.client.replaced.CanvasContent;
import dev.vellum.mod.client.replaced.McReplaced;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

/**
 * The engine's {@link Canvas} over {@link GuiGraphicsExtractor}. Create one per frame, paint, then {@link #finish()}.
 *
 * <ul>
 *   <li><b>Transforms.</b> Vanilla's pose stack is only 16 deep, so the canvas keeps its own matrix stack and sets
 *       the pose matrix before each vanilla call (one push for the whole document).</li>
 *   <li><b>Clipping.</b> {@link #clipRect} pushes a vanilla scissor: the transformed rectangle's bounding box,
 *       which vanilla intersects with the enclosing scissor. Rounded clips are not supported.</li>
 *   <li><b>Opacity.</b> There are no offscreen groups; the alpha stack is multiplied into every colour. Vanilla
 *       skips text with alpha 0, and items cannot fade, so items are hidden below half opacity.</li>
 *   <li><b>Geometry.</b> Rectangles and quads are submitted as {@link QuadsRenderState}s, so fractional positions,
 *       rotations and per-vertex colours all work.</li>
 * </ul>
 */
public final class McCanvas implements Canvas {
    private final Minecraft mc;
    private final GuiGraphicsExtractor g;
    private final float mouseX, mouseY;
    private final float guiScale;

    private final Matrix3x2f m;
    private final Matrix3x2f scratch = new Matrix3x2f();
    private float alpha = 1;
    /** Scissors pushed since the last {@link #save}; popped by the matching {@link #restore}. */
    private int scissors;

    private Matrix3x2f[] savedMatrices = new Matrix3x2f[16];
    private float[] savedAlpha = new float[16];
    private int[] savedScissors = new int[16];
    private int depth;

    /**
     * @param mouseX pointer position in GUI px, for content that reacts to it (item tooltips, gazes); -1 if none
     */
    public McCanvas(GuiGraphicsExtractor g, float mouseX, float mouseY) {
        this.mc = Minecraft.getInstance();
        this.g = g;
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        this.guiScale = mc.getWindow().getGuiScale();
        g.pose().pushMatrix();
        this.m = new Matrix3x2f(g.pose());
    }

    /** Unwinds everything the painter left pushed (also after an exception mid-paint) and restores the pose. */
    public void finish() {
        while (depth > 0) restore();
        popScissors();
        g.pose().popMatrix();
    }

    // ---- State ----

    @Override
    public void save() {
        if (depth == savedMatrices.length) {
            savedMatrices = Arrays.copyOf(savedMatrices, depth * 2);
            savedAlpha = Arrays.copyOf(savedAlpha, depth * 2);
            savedScissors = Arrays.copyOf(savedScissors, depth * 2);
        }
        Matrix3x2f saved = savedMatrices[depth];
        if (saved == null) savedMatrices[depth] = saved = new Matrix3x2f();
        saved.set(m);
        savedAlpha[depth] = alpha;
        savedScissors[depth] = scissors;
        scissors = 0;
        depth++;
    }

    @Override
    public void restore() {
        if (depth == 0) return;
        popScissors();
        depth--;
        m.set(savedMatrices[depth]);
        alpha = savedAlpha[depth];
        scissors = savedScissors[depth];
    }

    private void popScissors() {
        for (; scissors > 0; scissors--) g.disableScissor();
    }

    @Override
    public void translate(float dx, float dy) {
        m.translate(dx, dy);
    }

    @Override
    public void transform(float a, float b, float c, float d, float e, float f) {
        m.mul(scratch.set(a, b, c, d, e, f));
    }

    @Override
    public void multiplyAlpha(float a) {
        alpha *= Math.clamp(a, 0f, 1f);
    }

    @Override
    public void clipRect(float x, float y, float width, float height) {
        float[] box = bounds(new float[] {x, y, x, y + height, x + width, y + height, x + width, y});
        g.pose().identity();
        int x0 = Math.round(box[0]), y0 = Math.round(box[1]);
        g.enableScissor(x0, y0, Math.max(x0, Math.round(box[2])), Math.max(y0, Math.round(box[3])));
        scissors++;
    }

    @Override
    public float devicePixel() {
        return 1f / (guiScale * lengthScale());
    }

    // ---- Primitives ----

    @Override
    public void fillRect(float x, float y, float width, float height, int argb) {
        if (width <= 0 || height <= 0) return;
        int c = color(argb);
        if (ARGB.alpha(c) == 0) return;
        submit(RenderPipelines.GUI, TextureSetup.noTexture(), rect(x, y, width, height), null, new int[] {c, c, c, c});
    }

    @Override
    public void fillQuads(float[] xy, int[] colors, int quadCount) {
        if (quadCount <= 0) return;
        float[] v = Arrays.copyOf(xy, quadCount * 8); // the caller may reuse its buffers before we render
        int[] c = new int[quadCount * 4];
        for (int i = 0; i < c.length; i++) c[i] = color(colors[i]);
        submit(RenderPipelines.GUI, TextureSetup.noTexture(), v, null, c);
    }

    @Override
    public void drawText(String text, float x, float y, FontSpec font, int argb, int decorations, boolean shadow) {
        int c = color(argb);
        if (text.isEmpty() || ARGB.alpha(c) == 0) return;
        Style style = McFontMetrics.INSTANCE.style(font);
        if ((decorations & UNDERLINE) != 0) style = style.withUnderlined(true);
        if ((decorations & STRIKETHROUGH) != 0) style = style.withStrikethrough(true);
        float s = font.scale();
        g.pose().set(m).translate(x, y).scale(s, s);
        g.text(mc.font, Language.getInstance().getVisualOrder(FormattedText.of(text, style)), 0, 0, c, shadow);
    }

    @Override
    public void drawImage(String url, float x, float y, float width, float height,
                          float u0, float v0, float u1, float v1, int tint, boolean smooth) {
        if (url.startsWith("sprite:")) {
            drawSprite(url.substring("sprite:".length()), x, y, width, height, tint);
            return;
        }
        Identifier texture = url.startsWith(CanvasContent.SCHEME) ? CanvasContent.texture(url) : Identifier.tryParse(url);
        if (texture != null) blit(texture, x, y, width, height, u0, v0, u1, v1, tint, smooth);
    }

    @Override
    public float @Nullable [] imageSize(String url) {
        return McReplaced.imageSize(url);
    }

    @Override
    public void drawSprite(String spriteId, float x, float y, float width, float height, int tint) {
        Identifier sprite = Identifier.tryParse(spriteId);
        int c = color(tint);
        int w = Math.round(width), h = Math.round(height);
        if (sprite == null || ARGB.alpha(c) == 0 || w <= 0 || h <= 0) return;
        // Nine-slice and tiling work at integer sizes; scale the remainder so the sprite still fills the box exactly.
        g.pose().set(m).translate(x, y).scale(width / w, height / h);
        g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, 0, 0, w, h, c);
    }

    @Override
    public void drawReplaced(ReplacedContent content, Element element, float x, float y, float width, float height) {
        if (content instanceof McReplaced r && width > 0 && height > 0) r.draw(this, element, x, y, width, height);
    }

    // ---- For replaced content ----

    public float mouseX() {
        return mouseX;
    }

    public float mouseY() {
        return mouseY;
    }

    /** Draws a texture region (normalised UVs) with a tint, at any position and transform. */
    public void blit(Identifier texture, float x, float y, float width, float height,
                     float u0, float v0, float u1, float v1, int tint, boolean smooth) {
        int c = color(tint);
        if (ARGB.alpha(c) == 0 || width <= 0 || height <= 0) return;
        AbstractTexture t = mc.getTextureManager().getTexture(texture);
        var sampler = smooth ? RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR) : t.getSampler();
        submit(RenderPipelines.GUI_TEXTURED, TextureSetup.singleTexture(t.getTextureView(), sampler), rect(x, y, width, height),
                new float[] {u0, v0, u0, v1, u1, v1, u1, v0}, new int[] {c, c, c, c});
    }

    /** Draws an item scaled from its 16 px base to {@code size}, with count and durability when asked. */
    public void drawItem(ItemStack stack, float x, float y, float size, boolean decorations) {
        if (stack.isEmpty() || alpha < 0.5f) return; // items are pre-rendered sprites: they can't be faded
        g.pose().set(m).translate(x, y).scale(size / 16f, size / 16f);
        g.item(stack, 0, 0);
        if (decorations) g.itemDecorations(mc.font, stack, 0, 0);
    }

    /** Shows the vanilla tooltip for {@code stack} at the pointer; vanilla draws it on top at the end of the frame. */
    public void itemTooltip(ItemStack stack) {
        if (mouseX >= 0 && !stack.isEmpty()) g.setTooltipForNextFrame(mc.font, stack, (int) mouseX, (int) mouseY);
    }

    /**
     * Draws an entity render state fitted into a local box (a picture-in-picture render: it is axis-aligned on screen,
     * so rotations only move the box). {@code pixelsPerBlock} is in local px; like items, entities can't be faded.
     */
    public void drawEntity(EntityRenderState state, float pixelsPerBlock, Vector3f translation, Quaternionf rotation,
                           @Nullable Quaternionf cameraTilt, float x, float y, float width, float height) {
        if (alpha < 0.5f) return;
        float[] b = bounds(rect(x, y, width, height));
        g.entity(state, pixelsPerBlock * lengthScale(), translation, rotation, cameraTilt,
                Math.round(b[0]), Math.round(b[1]), Math.round(b[2]), Math.round(b[3]));
    }

    // ---- Internals ----

    /** How much the current transform scales lengths (the square root of its area scale). */
    private float lengthScale() {
        return (float) Math.sqrt(Math.abs(m.determinant()));
    }

    private int color(int argb) {
        return alpha >= 1 ? argb : ARGB.multiplyAlpha(argb, alpha);
    }

    /** A rectangle as one quad in vanilla's vertex order: (x0,y0) (x0,y1) (x1,y1) (x1,y0). */
    private static float[] rect(float x, float y, float w, float h) {
        return new float[] {x, y, x, y + h, x + w, y + h, x + w, y};
    }

    private void submit(RenderPipeline pipeline, TextureSetup textures, float[] xy, float @Nullable [] uv, int[] colors) {
        windQuads(xy, uv, colors);
        float[] b = bounds(xy);
        ScreenRectangle box = new ScreenRectangle((int) Math.floor(b[0]), (int) Math.floor(b[1]),
                (int) Math.ceil(b[2]) - (int) Math.floor(b[0]), (int) Math.ceil(b[3]) - (int) Math.floor(b[1]));
        ScreenRectangle scissor = g.scissorStack.peek();
        ScreenRectangle bounds = scissor == null ? box : scissor.intersection(box);
        if (bounds == null) return; // fully clipped
        g.guiRenderState.addGuiElement(new QuadsRenderState(pipeline, textures, new Matrix3x2f(m), xy, uv, colors, scissor, bounds));
    }

    /**
     * GUI pipelines cull back faces, so every quad must wind like vanilla's (negative shoelace area on screen).
     * Quads that arrive the other way round, or that a mirroring transform flips, are reversed in place.
     */
    private void windQuads(float[] xy, float @Nullable [] uv, int[] colors) {
        boolean mirrored = m.determinant() < 0;
        for (int q = 0; q < colors.length / 4; q++) {
            int o = q * 8;
            float area = 0;
            for (int i = 0; i < 4; i++) {
                int a = o + i * 2, b = o + ((i + 1) & 3) * 2;
                area += xy[a] * xy[b + 1] - xy[b] * xy[a + 1];
            }
            if ((area > 0) != mirrored && area != 0) {
                swap(xy, o + 2, o + 6, 2);
                if (uv != null) swap(uv, o + 2, o + 6, 2);
                int c = colors[q * 4 + 1];
                colors[q * 4 + 1] = colors[q * 4 + 3];
                colors[q * 4 + 3] = c;
            }
        }
    }

    private static void swap(float[] a, int i, int j, int n) {
        for (int k = 0; k < n; k++) {
            float t = a[i + k];
            a[i + k] = a[j + k];
            a[j + k] = t;
        }
    }

    /** The screen-space bounding box {x0, y0, x1, y1} of local points. */
    private float[] bounds(float[] xy) {
        float x0 = Float.POSITIVE_INFINITY, y0 = Float.POSITIVE_INFINITY, x1 = Float.NEGATIVE_INFINITY, y1 = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < xy.length; i += 2) {
            float sx = m.m00() * xy[i] + m.m10() * xy[i + 1] + m.m20();
            float sy = m.m01() * xy[i] + m.m11() * xy[i + 1] + m.m21();
            x0 = Math.min(x0, sx);
            y0 = Math.min(y0, sy);
            x1 = Math.max(x1, sx);
            y1 = Math.max(y1, sy);
        }
        return new float[] {x0, y0, x1, y1};
    }
}
