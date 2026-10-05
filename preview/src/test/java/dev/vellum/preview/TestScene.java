package dev.vellum.preview;

import dev.vellum.engine.dom.Viewport;
import dev.vellum.engine.event.Modifiers;
import dev.vellum.preview.host.PreviewHost;
import dev.vellum.preview.render.MinecraftAssets;
import dev.vellum.preview.render.MinecraftFont;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A page open in the previewer's rendering path for a test: its scene and a renderer, at {@code width} × {@code height}
 * GUI px and {@code scale} device px each. Close it to release the assets.
 */
record TestScene(MinecraftAssets assets, PageScene scene, FrameRenderer renderer, int width, int height, int scale)
        implements AutoCloseable {
    /** Opens {@code page} with Minecraft's jar, or the bundled stand-ins without one. */
    static TestScene open(Path page, Optional<Path> jar, int width, int height, int scale) {
        MinecraftAssets assets = MinecraftAssets.open(PreviewHost.packRoot(page).stream().toList(), jar);
        MinecraftFont font = new MinecraftFont(assets);
        PageScene scene = new PageScene(new PreviewHost(assets, font), PreviewHost.pageUrl(page), null,
                new Viewport(width, height, scale));
        return new TestScene(assets, scene, new FrameRenderer(assets, font), width, height, scale);
    }

    /** Runs the frame at {@code ms} and returns what it painted. */
    BufferedImage render(double ms) {
        return renderer.render(scene, width * scale, height * scale, scale, ms, null);
    }

    /** Moves the pointer to {@code at} ({x, y} in viewport px). */
    void hover(float[] at) {
        scene.input(in -> in.mouseMove(at[0], at[1], Modifiers.NONE));
    }

    @Override
    public void close() throws IOException {
        assets.close();
    }
}
