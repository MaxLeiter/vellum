package dev.vellum.mod.client.render;

import com.mojang.blaze3d.platform.cursor.CursorType;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import dev.vellum.engine.paint.ScissorStack;
import dev.vellum.engine.style.Cursor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.WindowRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Minecraft's GUI drawing, the part of it that differs between Minecraft versions; this is the one for 26.x, where
 * a {@link GuiGraphicsExtractor} records render states that the GUI renderer draws at the end of the frame. Every
 * version has a McGui with the same methods ({@code common/versions/<version>/src}).
 *
 * <p>An instance is {@link McCanvas}'s backend for one frame: McCanvas keeps the transform, opacity and clip stacks and
 * computes bounds, and hands each primitive here already transformed and clipped. The static methods draw the few
 * things Vellum puts on screen without the engine (the error panel, the typing notice) and tooltips.
 */
public final class McGui {
    private final GuiGraphicsExtractor g;

    McGui(GuiGraphicsExtractor g) {
        this.g = g;
    }

    // ---- For McCanvas ----

    /**
     * What the GUI renderer can draw this frame: the framebuffer at the GUI scale it renders with (its scissors are
     * clamped to the framebuffer, and one clamped to nothing is a crash), within the screen and any scissor already
     * pushed. The framebuffer is not always the GUI size: {@code Window.setWindowed} resizes it at once, while the GUI
     * scale and size change only when the resize event arrives, so for a frame the GUI can reach past the framebuffer.
     */
    ScissorStack drawableArea() {
        WindowRenderState window = Minecraft.getInstance().gameRenderer.gameRenderState().windowRenderState;
        int scale = Math.max(1, window.guiScale);
        int width = Math.min(g.guiWidth(), Math.ceilDiv(window.width, scale));
        int height = Math.min(g.guiHeight(), Math.ceilDiv(window.height, scale));
        ScreenRectangle outer = g.scissorStack.peek();
        return outer == null ? new ScissorStack(0, 0, width, height) : new ScissorStack(Math.max(0, outer.left()),
                Math.max(0, outer.top()), Math.min(width, outer.right()), Math.min(height, outer.bottom()));
    }

    /** Saves vanilla's pose for the canvas, which sets it before each call; returns the transform it had. */
    Matrix3x2f begin() {
        g.pose().pushMatrix();
        return new Matrix3x2f(g.pose());
    }

    /** Puts vanilla's pose back. */
    void end() {
        g.pose().popMatrix();
    }

    /** Pushes a scissor, in GUI px on screen (the transform is not applied). */
    void pushScissor(int left, int top, int right, int bottom) {
        g.pose().identity();
        g.enableScissor(left, top, right, bottom);
    }

    void popScissor() {
        g.disableScissor();
    }

    /** The scissor elements are clipped to now, or null. */
    @Nullable ScreenRectangle scissor() {
        return g.scissorStack.peek();
    }

    /** Whether text in this colour shows (vanilla skips text with alpha 0). */
    static boolean textVisible(int argb) {
        return argb >>> 24 != 0;
    }

    /**
     * One rectangle in one colour: the vertices go (x0,y0) (x0,y1) (x1,y1) (x1,y0), wound as vanilla's when x0 < x1
     * and y0 < y1 (the caller swaps x0 and x1 under a mirroring transform; GUI pipelines cull back faces).
     *
     * @param bounds  the transformed bounding box clipped to the scissor
     */
    void fill(Matrix3x2fc pose, float x0, float y0, float x1, float y1, int color, ScreenRectangle bounds,
              @Nullable ScreenRectangle scissor) {
        g.guiRenderState.addGuiElement(new RectRenderState(RenderPipelines.GUI, TextureSetup.noTexture(), pose, x0, y0, x1,
                y1, false, 0, 0, 0, 0, color, scissor, bounds));
    }

    /** A texture region (normalised UVs) multiplied by {@code color}, wound as {@link #fill}. */
    void blit(Matrix3x2fc pose, Identifier texture, boolean smooth, float x0, float y0, float x1, float y1,
              float u0, float v0, float u1, float v1, int color, ScreenRectangle bounds, @Nullable ScreenRectangle scissor) {
        AbstractTexture t = Minecraft.getInstance().getTextureManager().getTexture(texture);
        var sampler = smooth ? RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR) : t.getSampler();
        g.guiRenderState.addGuiElement(new RectRenderState(RenderPipelines.GUI_TEXTURED,
                TextureSetup.singleTexture(t.getTextureView(), sampler), pose, x0, y0, x1, y1, true, u0, v0, u1, v1, color,
                scissor, bounds));
    }

    /** Quads with a colour per vertex, 4 vertices each, already wound as vanilla's. */
    void quads(Matrix3x2fc pose, float[] xy, int[] colors, ScreenRectangle bounds, @Nullable ScreenRectangle scissor) {
        g.guiRenderState.addGuiElement(new QuadsRenderState(RenderPipelines.GUI, TextureSetup.noTexture(), pose, xy, colors,
                scissor, bounds));
    }

    /** Text at (x, y) in {@code m}, scaled by {@code scale}. */
    void text(Matrix3x2fc m, float x, float y, float scale, FormattedCharSequence text, int color, boolean shadow) {
        g.pose().set(m).translate(x, y).scale(scale, scale);
        g.text(Minecraft.getInstance().font, text, 0, 0, color, shadow);
    }

    /** A GUI sprite (nine-slice and tiling at its integer size {@code w × h}), at (x, y) in {@code m} scaled by (sx, sy). */
    void sprite(Matrix3x2fc m, float x, float y, float sx, float sy, Identifier sprite, int w, int h, int color) {
        g.pose().set(m).translate(x, y).scale(sx, sy);
        g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, 0, 0, w, h, color);
    }

    /** An item at (x, y) in {@code m}, scaled from its 16 px, with count and durability when asked. */
    void item(Matrix3x2fc m, float x, float y, float scale, ItemStack stack, boolean decorations) {
        g.pose().set(m).translate(x, y).scale(scale, scale);
        g.item(stack, 0, 0);
        if (decorations) g.itemDecorations(Minecraft.getInstance().font, stack, 0, 0);
    }

    /**
     * A 3D scene in a screen box, a block {@code scale} GUI px, multiplied by {@code color}: a picture, blitted with the
     * colour premultiplied as the picture is, so fading scales every channel.
     */
    void scene(Scene scene, int color, int x0, int y0, int x1, int y1, float scale, @Nullable ScreenRectangle scissor) {
        g.guiRenderState.addPicturesInPictureState(new GuiSceneRenderState(scene, premultiplied(color), x0, y0, x1, y1, scale, scissor));
    }

    /** {@code argb} with its colour channels multiplied by its alpha. */
    private static int premultiplied(int argb) {
        float a = (argb >>> 24) / 255f;
        return argb & 0xFF000000 | (int) ((argb >> 16 & 0xFF) * a) << 16 | (int) ((argb >> 8 & 0xFF) * a) << 8
                | (int) ((argb & 0xFF) * a);
    }

    // ---- Panels, tooltips, the cursor ----

    /** From here on, everything is drawn above what was drawn before (vanilla's next stratum). */
    public static void nextLayer(GuiGraphicsExtractor g) {
        g.nextStratum();
    }

    public static void fill(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
        g.fill(x0, y0, x1, y1, color);
    }

    public static void outline(GuiGraphicsExtractor g, int x, int y, int width, int height, int color) {
        g.outline(x, y, width, height, color);
    }

    public static void text(GuiGraphicsExtractor g, Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
        g.text(font, text, x, y, color, shadow);
    }

    public static void text(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int color, boolean shadow) {
        g.text(font, text, x, y, color, shadow);
    }

    public static void text(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color, boolean shadow) {
        g.text(font, text, x, y, color, shadow);
    }

    /** A tooltip of already wrapped lines for this frame, drawn on top at its end. */
    public static void tooltip(GuiGraphicsExtractor g, Font font, List<FormattedCharSequence> lines, int x, int y) {
        g.setTooltipForNextFrame(font, lines, x, y);
    }

    /** A tooltip of lines that are not wrapped, for this frame. */
    public static void componentTooltip(GuiGraphicsExtractor g, Font font, List<Component> lines, int x, int y) {
        g.setComponentTooltipForNextFrame(font, lines, x, y);
    }

    /**
     * An item tooltip of {@code lines} (the item's own and any after them) for this frame: with the stack's tooltip
     * image and style, and the gap after its name.
     */
    public static void itemTooltip(GuiGraphicsExtractor g, Font font, List<Component> lines, ItemStack stack, int x, int y) {
        g.setTooltipForNextFrame(font, lines, stack.getTooltipImage(), x, y, stack.get(DataComponents.TOOLTIP_STYLE), true);
    }

    /**
     * Draws the tooltips set for this frame now, for HUD overlays drawn after the screen's own pass has drawn its
     * tooltips.
     */
    public static void drawTooltipsNow(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.extractDeferredElements(mouseX, mouseY, partialTick);
    }

    /**
     * Shows the CSS cursor a page asks for while the pointer is over it; vanilla resets it every frame. Screens' pages
     * and HUD overlays both ask ({@code screen} tells them apart).
     */
    public static void cursor(GuiGraphicsExtractor g, Cursor cursor, boolean screen) {
        if (cursor != Cursor.AUTO && cursor != Cursor.DEFAULT) g.requestCursor(cursorType(cursor));
    }

    /** A page's cursor is no longer asked for (its screen closed). Nothing to do: vanilla resets it every frame. */
    public static void resetCursor() {}

    private static CursorType cursorType(Cursor cursor) {
        return switch (cursor) {
            case POINTER, GRAB, GRABBING -> CursorTypes.POINTING_HAND;
            case TEXT -> CursorTypes.IBEAM;
            case EW_RESIZE -> CursorTypes.RESIZE_EW;
            case NS_RESIZE -> CursorTypes.RESIZE_NS;
            case MOVE -> CursorTypes.RESIZE_ALL;
            case NOT_ALLOWED -> CursorTypes.NOT_ALLOWED;
            case CROSSHAIR -> CursorTypes.CROSSHAIR;
            default -> CursorTypes.ARROW;
        };
    }
}
