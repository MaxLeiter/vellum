package dev.vellum.mod.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.jspecify.annotations.Nullable;

/**
 * Draws {@link GuiSceneRenderState}s: entities, blocks and items in 3D, rendered at the GUI scale (so they are crisp
 * at any size) into a picture the size of their box, and blitted with their colour, which vanilla's picture renderers
 * leave white (so its entities can't be tinted or faded). The loaders register it (NeoForge's
 * {@code RegisterPictureInPictureRenderersEvent}, Fabric's {@code PictureInPictureRendererRegistry}); both pool
 * renderers, so any number of pictures can be drawn in a frame. A still model's picture is kept while it and its
 * size stay the same, so it costs a blit.
 */
public final class GuiSceneRenderer extends PictureInPictureRenderer<GuiSceneRenderState> {
    /** What the picture holds, or null when it must be drawn again. */
    private record Picture(Object scene, float scale) {}

    private @Nullable Picture drawn;

    @Override
    public Class<GuiSceneRenderState> getRenderStateClass() {
        return GuiSceneRenderState.class;
    }

    @Override
    protected boolean textureIsReadyToBlit(GuiSceneRenderState state) {
        return drawn != null && drawn.equals(picture(state));
    }

    @Override
    protected void renderToTexture(GuiSceneRenderState state, PoseStack pose, SubmitNodeCollector out) {
        state.scene().submit(pose, out, state);
        drawn = picture(state);
    }

    /** As vanilla's blit, multiplied by the state's colour (premultiplied, as the picture is). */
    @Override
    protected void blitTexture(GuiSceneRenderState state, GuiRenderState out) {
        out.addBlitToCurrentLayer(new BlitRenderState(RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA,
                TextureSetup.singleTexture(textureView, RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST)),
                state.pose(), state.x0(), state.y0(), state.x1(), state.y1(), 0, 1, 1, 0, state.color(), state.scissorArea(), null));
    }

    @Override
    protected float getTranslateY(int height, int guiScale) {
        return height / 2.0F;
    }

    @Override
    protected String getTextureLabel() {
        return "vellum scene";
    }

    private static @Nullable Picture picture(GuiSceneRenderState state) {
        Object scene = state.scene().key();
        return scene == null ? null : new Picture(scene, state.scale());
    }
}
