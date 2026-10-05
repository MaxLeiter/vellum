package dev.vellum.mod.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import dev.vellum.engine.host.FontSpec;
import dev.vellum.engine.paint.Canvas;
import dev.vellum.engine.paint.ScissorStack;
import dev.vellum.engine.paint.Shapes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.WindowRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2f;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

/**
 * The engine's {@link Canvas} over {@link GuiGraphicsExtractor}. Create one per frame, paint, then {@link #finish()}
 * (the painter balances its saves, also when it fails).
 *
 * <ul>
 *   <li><b>Transforms.</b> Vanilla's pose stack is only 16 deep, so the canvas keeps its own matrix stack and sets
 *       the pose matrix before each vanilla call (one push for the whole document).</li>
 *   <li><b>Clipping.</b> {@link #clipRect} pushes a vanilla scissor: the transformed rectangle's bounding box,
 *       intersected with the enclosing clip and the area the renderer draws ({@link ScissorStack}). A clip with
 *       nothing left is not pushed, and nothing inside it is drawn. Rounded clips are not supported.</li>
 *   <li><b>Opacity.</b> There are no offscreen groups; the alpha stack is multiplied into every colour. Vanilla
 *       skips text with alpha 0, and items cannot fade, so items are hidden below half opacity. 3D scenes are
 *       pictures blitted with a colour, so they fade (and tint).</li>
 *   <li><b>Geometry.</b> Rectangles and quads are submitted as {@link RectRenderState}s and {@link QuadsRenderState}s,
 *       so fractional positions, rotations and per-vertex colours all work. Consecutive primitives share one copy of
 *       the transform.</li>
 * </ul>
 */
public final class McCanvas implements Canvas {
    /** Receives where {@code <slot>} elements were painted, for a container screen to move its slots there. */
    @FunctionalInterface
    public interface SlotSink {
        /** Slot {@code index}'s 16×16 item area goes at screen GUI position (x, y). */
        void place(int index, int x, int y);
    }

    private final Minecraft mc;
    private final GuiGraphicsExtractor g;
    private final float mouseX, mouseY;
    private final float guiScale;
    private final @Nullable SlotSink slots;

    private final Matrix3x2f m;
    private final Matrix3x2f scratch = new Matrix3x2f();
    /** A copy of {@link #m} for render states, which keep it; made on demand after {@code m} changes. */
    private @Nullable Matrix3x2f pose;
    private float alpha = 1;
    /** The clips, mirrored by vanilla's scissor stack where they are not empty. */
    private final ScissorStack clips;
    private boolean tooltip;

    private Matrix3x2f[] savedMatrices = new Matrix3x2f[16];
    private float[] savedAlpha = new float[16];
    /** {@link ScissorStack#depth()} at each {@link #save}, for the matching {@link #restore}. */
    private int[] savedClips = new int[16];
    private int depth;

    /** The screen-space bounding box of the last {@link #boundsOf} call. */
    private float bx0, by0, bx1, by1;

    /** @see #McCanvas(GuiGraphicsExtractor, float, float, SlotSink) */
    public McCanvas(GuiGraphicsExtractor g, float mouseX, float mouseY) {
        this(g, mouseX, mouseY, null);
    }

    /**
     * @param mouseX pointer position in GUI px, for content that reacts to it (item tooltips, gazes); -1 if none
     * @param slots  where {@code <slot>} elements report their painted position, or null outside container screens
     */
    public McCanvas(GuiGraphicsExtractor g, float mouseX, float mouseY, @Nullable SlotSink slots) {
        this.mc = Minecraft.getInstance();
        this.g = g;
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        this.slots = slots;
        this.guiScale = mc.getWindow().getGuiScale();
        this.clips = drawableArea(mc, g);
        g.pose().pushMatrix();
        this.m = new Matrix3x2f(g.pose());
    }

    /**
     * Clips start from what the GUI renderer can draw this frame: the framebuffer at the GUI scale it renders with
     * (its scissors are clamped to the framebuffer, and one clamped to nothing is a crash), within the screen and any
     * scissor already pushed. The framebuffer is not always the GUI size: {@code Window.setWindowed} resizes it at
     * once, while the GUI scale and size change only when the resize event arrives, so for a frame the GUI can reach
     * past the framebuffer.
     */
    private static ScissorStack drawableArea(Minecraft mc, GuiGraphicsExtractor g) {
        WindowRenderState window = mc.gameRenderer.gameRenderState().windowRenderState;
        int scale = Math.max(1, window.guiScale);
        int width = Math.min(g.guiWidth(), Math.ceilDiv(window.width, scale));
        int height = Math.min(g.guiHeight(), Math.ceilDiv(window.height, scale));
        ScreenRectangle outer = g.scissorStack.peek();
        return outer == null ? new ScissorStack(0, 0, width, height) : new ScissorStack(Math.max(0, outer.left()),
                Math.max(0, outer.top()), Math.min(width, outer.right()), Math.min(height, outer.bottom()));
    }

    /** Pops the scissors clipped outside any save and restores the pose. */
    public void finish() {
        popClips(0);
        g.pose().popMatrix();
    }

    // ---- State ----

    @Override
    public void save() {
        if (depth == savedMatrices.length) {
            savedMatrices = Arrays.copyOf(savedMatrices, depth * 2);
            savedAlpha = Arrays.copyOf(savedAlpha, depth * 2);
            savedClips = Arrays.copyOf(savedClips, depth * 2);
        }
        Matrix3x2f saved = savedMatrices[depth];
        if (saved == null) savedMatrices[depth] = saved = new Matrix3x2f();
        saved.set(m);
        savedAlpha[depth] = alpha;
        savedClips[depth] = clips.depth();
        depth++;
    }

    @Override
    public int saveCount() {
        return depth;
    }

    @Override
    public void restore() {
        if (depth == 0) return;
        depth--;
        popClips(savedClips[depth]);
        setMatrix(savedMatrices[depth]);
        alpha = savedAlpha[depth];
    }

    private void popClips(int toDepth) {
        while (clips.depth() > toDepth) if (clips.pop()) g.disableScissor();
    }

    /** True inside an empty clip: draw calls do nothing. */
    private boolean clippedAway() {
        return clips.clippedAway();
    }

    @Override
    public void translate(float dx, float dy) {
        m.translate(dx, dy);
        pose = null;
    }

    @Override
    public void transform(float a, float b, float c, float d, float e, float f) {
        m.mul(scratch.set(a, b, c, d, e, f));
        pose = null;
    }

    private void setMatrix(Matrix3x2f matrix) {
        if (!m.equals(matrix)) {
            m.set(matrix);
            pose = null;
        }
    }

    @Override
    public void multiplyAlpha(float a) {
        alpha *= Math.clamp(a, 0f, 1f);
    }

    @Override
    public void clipRect(float x, float y, float width, float height) {
        boundsOf(x, y, x + width, y + height);
        if (!clips.push(bx0, by0, bx1, by1)) return;
        g.pose().identity();
        g.enableScissor(clips.left(), clips.top(), clips.right(), clips.bottom());
    }

    @Override
    public float devicePixel() {
        return 1f / (guiScale * lengthScale());
    }

    // ---- Primitives ----

    @Override
    public void fillRect(float x, float y, float width, float height, int argb) {
        if (clippedAway() || width <= 0 || height <= 0) return;
        int c = color(argb);
        if (ARGB.alpha(c) != 0) rect(RenderPipelines.GUI, TextureSetup.noTexture(), x, y, width, height, false, 0, 0, 0, 0, c);
    }

    @Override
    public void fillQuads(float[] xy, int[] colors, int quadCount) {
        if (clippedAway() || quadCount <= 0) return;
        float[] v = Arrays.copyOf(xy, quadCount * 8); // the caller may reuse its buffers before we render
        int[] c = new int[quadCount * 4];
        for (int i = 0; i < c.length; i++) c[i] = color(colors[i]);
        windQuads(v, c);
        float x0 = Float.POSITIVE_INFINITY, y0 = Float.POSITIVE_INFINITY, x1 = Float.NEGATIVE_INFINITY, y1 = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < v.length; i += 2) {
            x0 = Math.min(x0, v[i]);
            y0 = Math.min(y0, v[i + 1]);
            x1 = Math.max(x1, v[i]);
            y1 = Math.max(y1, v[i + 1]);
        }
        boundsOf(x0, y0, x1, y1);
        ScreenRectangle scissor = g.scissorStack.peek();
        ScreenRectangle bounds = clippedBounds(scissor);
        if (bounds != null) {
            g.guiRenderState.addGuiElement(new QuadsRenderState(RenderPipelines.GUI, TextureSetup.noTexture(), pose(), v, c, scissor, bounds));
        }
    }

    @Override
    public void drawText(String text, float x, float y, FontSpec font, int argb, int decorations, boolean shadow) {
        if (clippedAway()) return;
        int c = color(argb);
        if (text.isEmpty() || ARGB.alpha(c) == 0) return;
        McFontMetrics fonts = McFontMetrics.INSTANCE;
        float s = font.scale();
        g.pose().set(m).translate(x, y).scale(s, s);
        g.text(mc.font, fonts.sequence(text, fonts.style(font, decorations)), 0, 0, c, shadow);
    }

    @Override
    public void drawImage(String url, float x, float y, float width, float height,
                          float u0, float v0, float u1, float v1, int tint, boolean smooth) {
        Identifier texture = McImages.id(url);
        if (texture != null) blit(texture, x, y, width, height, u0, v0, u1, v1, tint, smooth);
    }

    @Override
    public void drawSprite(String spriteId, float x, float y, float width, float height, int tint) {
        Identifier sprite = McImages.id(spriteId);
        if (clippedAway()) return;
        int c = color(tint);
        int w = Math.round(width), h = Math.round(height);
        if (sprite == null || ARGB.alpha(c) == 0 || w <= 0 || h <= 0) return;
        // Nine-slice and tiling work at integer sizes; scale the remainder so the sprite still fills the box exactly.
        g.pose().set(m).translate(x, y).scale(width / w, height / h);
        g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, 0, 0, w, h, c);
    }

    // ---- For Minecraft content ----

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
        if (clippedAway() || ARGB.alpha(c) == 0 || width <= 0 || height <= 0) return;
        AbstractTexture t = mc.getTextureManager().getTexture(texture);
        var sampler = smooth ? RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR) : t.getSampler();
        rect(RenderPipelines.GUI_TEXTURED, TextureSetup.singleTexture(t.getTextureView(), sampler), x, y, width, height,
                true, u0, v0, u1, v1, c);
    }

    /** Draws an item scaled from its 16 px base to {@code size}, with count and durability when asked. */
    public void drawItem(ItemStack stack, float x, float y, float size, boolean decorations) {
        if (clippedAway() || stack.isEmpty() || alpha < 0.5f) return; // items are pre-rendered sprites: they can't be faded
        g.pose().set(m).translate(x, y).scale(size / 16f, size / 16f);
        g.item(stack, 0, 0);
        if (decorations) g.itemDecorations(mc.font, stack, 0, 0);
    }

    /** Shows the vanilla tooltip for {@code stack} at the pointer; vanilla draws it on top at the end of the frame. */
    public void itemTooltip(ItemStack stack) {
        if (clippedAway() || mouseX < 0 || stack.isEmpty()) return;
        g.setTooltipForNextFrame(mc.font, stack, (int) mouseX, (int) mouseY);
        tooltip = true;
    }

    /** Whether content asked for a tooltip while painting ({@link #itemTooltip}). */
    public boolean requestedTooltip() {
        return tooltip;
    }

    /**
     * Draws a 3D scene (an entity, block or item) into a local box, a block being {@code pixelsPerBlock} local px,
     * multiplied by {@code tint} and the opacity. A picture-in-picture render: axis-aligned on screen, so rotations
     * of the canvas only move the box.
     */
    public void drawScene(Scene scene, float pixelsPerBlock, int tint, float x, float y, float width, float height) {
        if (clippedAway()) return;
        float a = alpha * ARGB.alphaFloat(tint);
        boundsOf(x, y, x + width, y + height);
        int x0 = Math.round(bx0), y0 = Math.round(by0), x1 = Math.round(bx1), y1 = Math.round(by1);
        float scale = pixelsPerBlock * lengthScale();
        ScreenRectangle scissor = g.scissorStack.peek();
        // Pictures are rendered even where nothing of them shows: skip those scrolled or clipped away.
        if (a <= 0 || x1 <= x0 || y1 <= y0 || scale <= 0 || clippedBounds(scissor) == null) return;
        // The picture is premultiplied, so fading scales every channel.
        int color = ARGB.colorFromFloat(a, a * ARGB.redFloat(tint), a * ARGB.greenFloat(tint), a * ARGB.blueFloat(tint));
        g.guiRenderState.addPicturesInPictureState(new GuiSceneRenderState(scene, color, x0, y0, x1, y1, scale, scissor));
    }

    /**
     * Reports where container slot {@code index} is painted: its 16×16 item area centred in the local box, on screen.
     * A slot that is under half opacity (items can't fade) or not wholly inside the clip is not placed.
     */
    public void placeSlot(int index, float x, float y, float width, float height) {
        if (slots == null || clippedAway() || alpha < 0.5f) return;
        float cx = x + width / 2, cy = y + height / 2;
        int sx = Math.round(m.m00() * cx + m.m10() * cy + m.m20() - 8), sy = Math.round(m.m01() * cx + m.m11() * cy + m.m21() - 8);
        boolean clipped = clips.depth() > 0; // the screen edge alone does not hide a slot
        if (clipped && (sx < clips.left() || sy < clips.top() || sx + 16 > clips.right() || sy + 16 > clips.bottom())) return;
        slots.place(index, sx, sy);
    }

    // ---- Internals ----

    /** How much the current transform scales lengths (the square root of its area scale). */
    private float lengthScale() {
        return (float) Math.sqrt(Math.abs(m.determinant()));
    }

    private int color(int argb) {
        return alpha >= 1 ? argb : ARGB.multiplyAlpha(argb, alpha);
    }

    /** The current transform for a render state, which keeps it: shared until the transform changes. */
    private Matrix3x2f pose() {
        if (pose == null) pose = new Matrix3x2f(m);
        return pose;
    }

    /** Submits one rectangle, wound for back-face culling even under a mirroring transform. */
    private void rect(RenderPipeline pipeline, TextureSetup textures, float x, float y, float width, float height,
                      boolean textured, float u0, float v0, float u1, float v1, int color) {
        boundsOf(x, y, x + width, y + height);
        ScreenRectangle scissor = g.scissorStack.peek();
        ScreenRectangle bounds = clippedBounds(scissor);
        if (bounds == null) return; // fully clipped
        float x0 = x, x1 = x + width;
        if (m.determinant() < 0) {
            x0 = x1;
            x1 = x;
            float u = u0;
            u0 = u1;
            u1 = u;
        }
        g.guiRenderState.addGuiElement(new RectRenderState(pipeline, textures, pose(), x0, y, x1, y + height, textured,
                u0, v0, u1, v1, color, scissor, bounds));
    }

    /**
     * GUI pipelines cull back faces, so every quad must wind like vanilla's (negative shoelace area on screen).
     * Quads that arrive the other way round, or that a mirroring transform flips, are reversed in place.
     */
    private void windQuads(float[] xy, int[] colors) {
        boolean mirrored = m.determinant() < 0;
        for (int q = 0; q < colors.length / 4; q++) {
            float area = Shapes.signedArea(xy, q * 8, 4);
            if ((area > 0) != mirrored && area != 0) {
                int o = q * 8;
                for (int k = 2; k < 4; k++) {
                    float t = xy[o + k];
                    xy[o + k] = xy[o + k + 4];
                    xy[o + k + 4] = t;
                }
                int c = colors[q * 4 + 1];
                colors[q * 4 + 1] = colors[q * 4 + 3];
                colors[q * 4 + 3] = c;
            }
        }
    }

    /** Sets {@link #bx0}..{@link #by1} to the screen-space bounding box of a local rectangle. */
    private void boundsOf(float x0, float y0, float x1, float y1) {
        bx0 = by0 = Float.POSITIVE_INFINITY;
        bx1 = by1 = Float.NEGATIVE_INFINITY;
        include(x0, y0);
        include(x0, y1);
        include(x1, y1);
        include(x1, y0);
    }

    private void include(float x, float y) {
        float sx = m.m00() * x + m.m10() * y + m.m20(), sy = m.m01() * x + m.m11() * y + m.m21();
        bx0 = Math.min(bx0, sx);
        by0 = Math.min(by0, sy);
        bx1 = Math.max(bx1, sx);
        by1 = Math.max(by1, sy);
    }

    /** The bounding box on whole pixels, clipped to the scissor; null when nothing of it is left. */
    private @Nullable ScreenRectangle clippedBounds(@Nullable ScreenRectangle scissor) {
        int x0 = (int) Math.floor(bx0), y0 = (int) Math.floor(by0), x1 = (int) Math.ceil(bx1), y1 = (int) Math.ceil(by1);
        if (scissor != null) {
            x0 = Math.max(x0, scissor.left());
            y0 = Math.max(y0, scissor.top());
            x1 = Math.min(x1, scissor.right());
            y1 = Math.min(y1, scissor.bottom());
        }
        return x1 > x0 && y1 > y0 ? new ScreenRectangle(x0, y0, x1 - x0, y1 - y0) : null;
    }
}
