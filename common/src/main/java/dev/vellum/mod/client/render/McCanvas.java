package dev.vellum.mod.client.render;

import dev.vellum.engine.host.FontSpec;
import dev.vellum.engine.paint.Canvas;
import dev.vellum.engine.paint.ScissorStack;
import dev.vellum.engine.paint.Shapes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2f;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

/**
 * The engine's {@link Canvas} over Minecraft's GUI drawing. Create one per frame, paint, then {@link #finish()}
 * (the painter balances its saves, also when it fails). What differs between Minecraft versions is in {@link McGui},
 * which this hands every primitive to, transformed and clipped.
 *
 * <ul>
 *   <li>Transforms: vanilla's pose stack is not deep (16 on 26.x), so the canvas keeps its own matrix stack and sets
 *       the pose matrix before each vanilla call (one push for the whole document).</li>
 *   <li>Clipping: {@link #clipRect} pushes a vanilla scissor: the transformed rectangle's bounding box,
 *       intersected with the enclosing clip and the area the renderer draws ({@link ScissorStack}). A clip with
 *       nothing left is not pushed, and nothing inside it is drawn. Rounded clips are not supported.</li>
 *   <li>Opacity: there are no offscreen groups; the alpha stack is multiplied into every colour. Vanilla
 *       skips text with alpha 0, and items cannot fade, so items are hidden below half opacity. 3D scenes tint;
 *       on 26.x they are pictures blitted with a colour, so they fade too, while on 1.21.1 they are drawn in place
 *       and hide below half opacity, as items do.</li>
 *   <li>Geometry: rectangles and quads are drawn with their own vertices, so fractional positions, rotations and
 *       per-vertex colours all work. Consecutive primitives share one copy of the transform.</li>
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
    private final McGui gui;
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
     * @param mouseX pointer position in GUI px, for content that reacts to it (gazes); -1 if none
     * @param slots  where {@code <slot>} elements report their painted position, or null outside container screens
     */
    public McCanvas(GuiGraphicsExtractor g, float mouseX, float mouseY, @Nullable SlotSink slots) {
        this.mc = Minecraft.getInstance();
        this.gui = new McGui(g);
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        this.slots = slots;
        this.guiScale = (float) mc.getWindow().getGuiScale();
        // Clips start from what the GUI renderer can draw this frame (McGui.drawableArea).
        this.clips = gui.drawableArea();
        this.m = gui.begin();
    }

    /** Pops the scissors clipped outside any save and restores the pose. */
    public void finish() {
        popClips(0);
        gui.end();
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
        while (clips.depth() > toDepth) if (clips.pop()) gui.popScissor();
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
        gui.pushScissor(clips.left(), clips.top(), clips.right(), clips.bottom());
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
        if (alpha(c) != 0) rect(null, false, x, y, width, height, 0, 0, 0, 0, c);
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
        ScreenRectangle scissor = gui.scissor();
        ScreenRectangle bounds = clippedBounds(scissor);
        if (bounds != null) gui.quads(pose(), v, c, bounds, scissor);
    }

    @Override
    public void drawText(String text, float x, float y, FontSpec font, int argb, int decorations, boolean shadow) {
        if (clippedAway()) return;
        int c = color(argb);
        if (text.isEmpty() || !McGui.textVisible(c)) return;
        McFontMetrics fonts = McFontMetrics.INSTANCE;
        gui.text(m, x, y, font.scale(), fonts.sequence(text, fonts.style(font, decorations)), c, shadow);
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
        if (sprite == null || alpha(c) == 0 || w <= 0 || h <= 0) return;
        // Nine-slice and tiling work at integer sizes; scale the remainder so the sprite still fills the box exactly.
        gui.sprite(m, x, y, width / w, height / h, sprite, w, h, c);
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
        if (clippedAway() || alpha(c) == 0 || width <= 0 || height <= 0) return;
        rect(texture, smooth, x, y, width, height, u0, v0, u1, v1, c);
    }

    /**
     * Draws an item scaled from its 16 px base to {@code size}, with count and durability when asked. Items outside
     * the clip are skipped before their model is resolved.
     */
    public void drawItem(ItemStack stack, float x, float y, float size, boolean decorations) {
        if (clippedAway() || stack.isEmpty() || alpha < 0.5f) return; // items are pre-rendered sprites: they can't be faded
        boundsOf(x, y, x + size, y + size);
        if (clippedBounds(gui.scissor()) == null) return;
        gui.item(m, x, y, size / 16f, stack, decorations);
    }

    /**
     * Whether a 3D scene in a local box, multiplied by {@code tint}, would show: callers check before they resolve
     * the scene, since pictures are rendered even where nothing of them shows (scrolled or clipped away, transparent).
     */
    public boolean sceneVisible(int tint, float x, float y, float width, float height) {
        if (clippedAway() || alpha(color(tint)) == 0) return false;
        boundsOf(x, y, x + width, y + height);
        return Math.round(bx1) > Math.round(bx0) && Math.round(by1) > Math.round(by0) && clippedBounds(gui.scissor()) != null;
    }

    /**
     * Draws a 3D scene (an entity, block or item) into a local box, a block being {@code pixelsPerBlock} local px,
     * multiplied by {@code tint} and the opacity. A picture-in-picture render: axis-aligned on screen, so rotations
     * of the canvas only move the box.
     */
    public void drawScene(Scene scene, float pixelsPerBlock, int tint, float x, float y, float width, float height) {
        float scale = pixelsPerBlock * lengthScale();
        if (scale <= 0 || !sceneVisible(tint, x, y, width, height)) return;
        int x0 = Math.round(bx0), y0 = Math.round(by0), x1 = Math.round(bx1), y1 = Math.round(by1);
        gui.scene(scene, color(tint), x0, y0, x1, y1, scale, gui.scissor());
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

    /** {@code argb} with the opacity multiplied into its alpha. */
    private int color(int argb) {
        if (alpha >= 1) return argb;
        if (argb == 0 || alpha <= 0) return 0;
        return (int) Math.floor(alpha(argb) / 255f * alpha * 255f) << 24 | argb & 0xFFFFFF;
    }

    private static int alpha(int argb) {
        return argb >>> 24;
    }


    /** The current transform for a render state, which keeps it: shared until the transform changes. */
    private Matrix3x2f pose() {
        if (pose == null) pose = new Matrix3x2f(m);
        return pose;
    }

    /**
     * Draws one rectangle, a fill or (with a texture) a blit, wound for back-face culling even under a mirroring
     * transform.
     */
    private void rect(@Nullable Identifier texture, boolean smooth, float x, float y, float width, float height,
                      float u0, float v0, float u1, float v1, int color) {
        boundsOf(x, y, x + width, y + height);
        ScreenRectangle scissor = gui.scissor();
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
        if (texture == null) gui.fill(pose(), x0, y, x1, y + height, color, bounds, scissor);
        else gui.blit(pose(), texture, smooth, x0, y, x1, y + height, u0, v0, u1, v1, color, bounds, scissor);
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
