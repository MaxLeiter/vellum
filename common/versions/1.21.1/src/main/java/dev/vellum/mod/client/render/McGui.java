package dev.vellum.mod.client.render;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.vellum.engine.paint.ScissorStack;
import dev.vellum.engine.style.Cursor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Minecraft's GUI drawing, the part of it that differs between Minecraft versions; this is the one for 1.21.1, where
 * {@link GuiGraphics} draws as it is called. Every version has a McGui with the same methods
 * ({@code common/versions/<version>/src}).
 *
 * <p>An instance is {@link McCanvas}'s backend for one frame: McCanvas keeps the transform, opacity and clip stacks and
 * computes bounds, and hands each primitive here already transformed and clipped. The static methods draw the few
 * things Vellum puts on screen without the engine (the error panel, the typing notice) and tooltips.
 *
 * <p>Order: everything is drawn in painter's order. Fills and text go through vanilla's buffer source, which draws
 * without the depth test when it is flushed; textures and sprites are drawn at once, also without it. Items are
 * 3D models that need the depth test, so after each one the depth buffer is cleared, and nothing drawn after an item
 * hides behind it.
 */
public final class McGui {
    /** How far in front of the canvas a scene's middle is drawn, so its near half is not clipped. */
    private static final float SCENE_DEPTH = 500;

    private final GuiGraphics g;
    /** Where vanilla's pose had z when the canvas began: the canvas draws at that depth. */
    private float z;

    McGui(GuiGraphics g) {
        this.g = g;
    }

    // ---- For McCanvas ----

    /** What can be drawn: the GUI-scaled window, within any scissor already pushed. */
    ScissorStack drawableArea() {
        int width = g.guiWidth(), height = g.guiHeight();
        ScreenRectangle outer = scissor();
        return outer == null ? new ScissorStack(0, 0, width, height) : new ScissorStack(Math.max(0, outer.left()),
                Math.max(0, outer.top()), Math.min(width, outer.right()), Math.min(height, outer.bottom()));
    }

    /** Saves vanilla's pose for the canvas, which sets it before each call; returns the 2D transform it had. */
    Matrix3x2f begin() {
        g.flush();
        g.pose().pushPose();
        Matrix4f pose = g.pose().last().pose();
        z = pose.m32();
        return new Matrix3x2f(pose.m00(), pose.m01(), pose.m10(), pose.m11(), pose.m30(), pose.m31());
    }

    /** Draws what is still buffered and puts vanilla's pose back. */
    void end() {
        g.flush();
        g.pose().popPose();
    }

    /** Pushes a scissor, in GUI px on screen. */
    void pushScissor(int left, int top, int right, int bottom) {
        g.flush(); // what was buffered under the old scissor is drawn with it
        g.enableScissor(left, top, right, bottom);
    }

    void popScissor() {
        g.flush();
        g.disableScissor();
    }

    /** The scissor vanilla clips to now, or null. */
    @Nullable ScreenRectangle scissor() {
        return g.scissorStack.stack.peekLast();
    }

    /** Whether text in this colour shows: vanilla draws text whose alpha is under 4 opaque, so it is left out. */
    static boolean textVisible(int argb) {
        return (argb & 0xFC000000) != 0;
    }

    /** One rectangle in one colour, wound as vanilla's: (x0,y0) (x0,y1) (x1,y1) (x1,y0). */
    void fill(Matrix3x2fc pose, float x0, float y0, float x1, float y1, int color, ScreenRectangle bounds,
              @Nullable ScreenRectangle scissor) {
        Matrix4f m = matrix(pose);
        VertexConsumer out = g.bufferSource().getBuffer(RenderType.gui());
        out.addVertex(m, x0, y0, 0).setColor(color);
        out.addVertex(m, x0, y1, 0).setColor(color);
        out.addVertex(m, x1, y1, 0).setColor(color);
        out.addVertex(m, x1, y0, 0).setColor(color);
    }

    /** A texture region (normalised UVs) multiplied by {@code color}, wound as {@link #fill}. */
    void blit(Matrix3x2fc pose, ResourceLocation texture, boolean smooth, float x0, float y0, float x1, float y1,
              float u0, float v0, float u1, float v1, int color, ScreenRectangle bounds, @Nullable ScreenRectangle scissor) {
        g.flush();
        Matrix4f m = matrix(pose);
        AbstractTexture t = Minecraft.getInstance().getTextureManager().getTexture(texture);
        if (smooth) t.setFilter(true, false);
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.enableBlend();
        RenderSystem.disableDepthTest();
        BufferBuilder out = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        out.addVertex(m, x0, y0, 0).setUv(u0, v0).setColor(color);
        out.addVertex(m, x0, y1, 0).setUv(u0, v1).setColor(color);
        out.addVertex(m, x1, y1, 0).setUv(u1, v1).setColor(color);
        out.addVertex(m, x1, y0, 0).setUv(u1, v0).setColor(color);
        BufferUploader.drawWithShader(out.buildOrThrow());
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        if (smooth) t.setFilter(false, false);
    }

    /** Quads with a colour per vertex, 4 vertices each, already wound as vanilla's. */
    void quads(Matrix3x2fc pose, float[] xy, int[] colors, ScreenRectangle bounds, @Nullable ScreenRectangle scissor) {
        Matrix4f m = matrix(pose);
        VertexConsumer out = g.bufferSource().getBuffer(RenderType.gui());
        for (int i = 0, n = colors.length; i < n; i++) out.addVertex(m, xy[i * 2], xy[i * 2 + 1], 0).setColor(colors[i]);
    }

    /** Text at (x, y) in {@code m}, scaled by {@code scale}. */
    void text(Matrix3x2fc m, float x, float y, float scale, FormattedCharSequence text, int color, boolean shadow) {
        Matrix4f pose = matrix(m).translate(x, y, 0).scale(scale, scale, 1);
        Minecraft.getInstance().font.drawInBatch(text, 0, 0, color, shadow, pose, g.bufferSource(), Font.DisplayMode.NORMAL,
                0, 0xF000F0);
    }

    /** A GUI sprite (nine-slice and tiling at its integer size {@code w × h}), at (x, y) in {@code m} scaled by (sx, sy). */
    void sprite(Matrix3x2fc m, float x, float y, float sx, float sy, ResourceLocation sprite, int w, int h, int color) {
        g.flush();
        setPose(m).translate(x, y, 0).scale(sx, sy, 1);
        g.setColor((color >> 16 & 0xFF) / 255f, (color >> 8 & 0xFF) / 255f, (color & 0xFF) / 255f, (color >>> 24) / 255f);
        RenderSystem.enableBlend();
        RenderSystem.disableDepthTest();
        g.blitSprite(sprite, 0, 0, w, h);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        g.setColor(1, 1, 1, 1);
    }

    /** An item at (x, y) in {@code m}, scaled from its 16 px, with count and durability when asked. */
    void item(Matrix3x2fc m, float x, float y, float scale, ItemStack stack, boolean decorations) {
        g.flush();
        setPose(m).translate(x, y, 0).scale(scale, scale, 1);
        g.renderItem(stack, 0, 0);
        if (decorations) g.renderItemDecorations(Minecraft.getInstance().font, stack, 0, 0);
        g.flush();
        clearDepth();
    }

    /**
     * A 3D scene in a screen box, a block {@code scale} GUI px, multiplied by {@code color}: drawn straight into the
     * GUI (1.21.1 has no picture-in-picture renderers), clipped to the box and the scissor, with a depth buffer of its
     * own. Models are opaque here, so the colour tints them but its alpha cannot fade them: like items, a scene under
     * half opacity is not drawn.
     */
    void scene(Scene scene, int color, int x0, int y0, int x1, int y1, float scale, @Nullable ScreenRectangle scissor) {
        if (color >>> 24 < 128) return;
        g.flush();
        clearDepth();
        g.enableScissor(x0, y0, x1, y1); // within the scissor already pushed
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.last().pose().translation((x0 + x1) / 2F, (y0 + y1) / 2F, z + SCENE_DEPTH);
        pose.last().normal().identity();
        pose.scale(scale, scale, -scale);
        RenderSystem.setShaderColor((color >> 16 & 0xFF) / 255F, (color >> 8 & 0xFF) / 255F, (color & 0xFF) / 255F, 1);
        scene.draw(pose, g.bufferSource(), new Scene.Box(x0, y0, x1, y1, scale));
        g.flush();
        RenderSystem.setShaderColor(1, 1, 1, 1);
        Lighting.setupFor3DItems(); // the GUI's lighting, as vanilla leaves it after its models
        pose.popPose();
        g.disableScissor();
        clearDepth();
    }

    /** {@code pose} as a 4×4 matrix at the canvas's depth. */
    private Matrix4f matrix(Matrix3x2fc pose) {
        return new Matrix4f(pose.m00(), pose.m01(), 0, 0, pose.m10(), pose.m11(), 0, 0, 0, 0, 1, 0, pose.m20(), pose.m21(), z, 1);
    }

    /** Sets vanilla's pose to {@code m} at the canvas's depth, for the vanilla calls that read it. */
    private Matrix4f setPose(Matrix3x2fc m) {
        Matrix4f pose = g.pose().last().pose();
        pose.set(matrix(m));
        return pose;
    }

    // ---- Panels, tooltips, the cursor ----

    /**
     * From here on, everything is drawn above what was drawn before: what is buffered is drawn, and the depth buffer
     * cleared so no item hides what follows.
     */
    public static void nextLayer(GuiGraphics g) {
        g.flush();
        clearDepth();
    }

    public static void fill(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        g.fill(x0, y0, x1, y1, color);
    }

    public static void outline(GuiGraphics g, int x, int y, int width, int height, int color) {
        g.renderOutline(x, y, width, height, color);
    }

    public static void text(GuiGraphics g, Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
        g.drawString(font, text, x, y, color, shadow);
    }

    public static void text(GuiGraphics g, Font font, Component text, int x, int y, int color, boolean shadow) {
        g.drawString(font, text, x, y, color, shadow);
    }

    public static void text(GuiGraphics g, Font font, String text, int x, int y, int color, boolean shadow) {
        g.drawString(font, text, x, y, color, shadow);
    }

    /** A tooltip of already wrapped lines, drawn now (1.21.1 has no deferred tooltips outside screens). */
    public static void tooltip(GuiGraphics g, Font font, List<FormattedCharSequence> lines, int x, int y) {
        g.renderTooltip(font, lines, x, y);
    }

    /** A tooltip of lines that are not wrapped, drawn now. */
    public static void componentTooltip(GuiGraphics g, Font font, List<Component> lines, int x, int y) {
        g.renderComponentTooltip(font, lines, x, y);
    }

    /** An item tooltip of {@code lines} (the item's own and any after them), with the stack's tooltip image, drawn now. */
    public static void itemTooltip(GuiGraphics g, Font font, List<Component> lines, ItemStack stack, int x, int y) {
        g.renderTooltip(font, lines, stack.getTooltipImage(), x, y);
    }

    /** Tooltips are drawn as they are asked for on this version, so there is nothing left to draw. */
    public static void drawTooltipsNow(GuiGraphics g, int mouseX, int mouseY, float partialTick) {}

    private static final Map<Cursor, Long> CURSORS = new EnumMap<>(Cursor.class);
    private static long shown;

    /**
     * Shows the CSS cursor a screen's page asks for while the pointer is over it, with GLFW's standard cursors
     * (vanilla 1.21.1 has no cursors of its own). It stays until another is asked for or {@link #resetCursor}. HUD
     * overlays ({@code screen} false) don't set the cursor on this version: with no cursor of vanilla's to ask, the last
     * page drawn would win, whatever is under the pointer.
     */
    public static void cursor(GuiGraphics g, Cursor cursor, boolean screen) {
        if (!screen) return;
        int shape = switch (cursor) {
            case POINTER, GRAB, GRABBING -> GLFW.GLFW_HAND_CURSOR;
            case TEXT -> GLFW.GLFW_IBEAM_CURSOR;
            case EW_RESIZE -> GLFW.GLFW_HRESIZE_CURSOR;
            case NS_RESIZE -> GLFW.GLFW_VRESIZE_CURSOR;
            case MOVE -> GLFW.GLFW_RESIZE_ALL_CURSOR;
            case NOT_ALLOWED -> GLFW.GLFW_NOT_ALLOWED_CURSOR;
            case CROSSHAIR -> GLFW.GLFW_CROSSHAIR_CURSOR;
            default -> 0;
        };
        show(shape == 0 ? 0L : CURSORS.computeIfAbsent(cursor, c -> GLFW.glfwCreateStandardCursor(shape)));
    }

    /** Back to the system's arrow: the page that asked for a cursor is gone. */
    public static void resetCursor() {
        show(0L);
    }

    private static void show(long handle) {
        if (handle == shown) return;
        shown = handle;
        Window window = Minecraft.getInstance().getWindow();
        GLFW.glfwSetCursor(window.getWindow(), handle);
    }

    private static void clearDepth() {
        RenderSystem.clear(GlConst.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
    }
}
