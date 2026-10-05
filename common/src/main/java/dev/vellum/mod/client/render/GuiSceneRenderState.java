package dev.vellum.mod.client.render;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.jspecify.annotations.Nullable;

/**
 * A 3D picture in a GUI box ({@code <entity>}, {@code <model>}), drawn by {@link GuiSceneRenderer}: the {@link Scene}
 * with a block {@code scale} GUI px, blitted multiplied by {@code color} (ARGB, so it can be tinted and faded).
 */
public record GuiSceneRenderState(Scene scene, int color, int x0, int y0, int x1, int y1, float scale,
                                  @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds)
        implements PictureInPictureRenderState {
    public GuiSceneRenderState(Scene scene, int color, int x0, int y0, int x1, int y1, float scale,
                               @Nullable ScreenRectangle scissorArea) {
        this(scene, color, x0, y0, x1, y1, scale, scissorArea, PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissorArea));
    }
}
