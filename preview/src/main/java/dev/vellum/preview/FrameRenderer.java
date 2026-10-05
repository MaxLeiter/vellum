package dev.vellum.preview;

import dev.vellum.preview.render.ImageCanvas;
import dev.vellum.preview.render.MinecraftAssets;
import dev.vellum.preview.render.MinecraftFont;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Renders a scene into an image: the backdrop, the scene (or the error that stopped it), then an optional overlay.
 * The one rendering path of the window, headless snapshots and the snapshot tests.
 */
final class FrameRenderer {
    private final MinecraftAssets assets;
    private final MinecraftFont font;
    private BufferedImage image;
    /** The backdrop is static, so it is painted once per size and scale and copied into each frame. */
    private int[] backdrop;
    private int backdropScale;

    FrameRenderer(MinecraftAssets assets, MinecraftFont font) {
        this.assets = assets;
        this.font = font;
    }

    /**
     * Advances the scene to {@code nowMs} and paints it at {@code width}×{@code height} device px. The viewport is
     * that size divided by {@code scale}, rounded up like Minecraft's GUI size. The returned image is reused by the
     * next call.
     */
    BufferedImage render(Scene scene, int width, int height, int scale, double nowMs, Consumer<ImageCanvas> overlay) {
        if (image == null || image.getWidth() != width || image.getHeight() != height) {
            image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            backdrop = null;
        }
        float guiWidth = (float) Math.ceil((double) width / scale), guiHeight = (float) Math.ceil((double) height / scale);
        scene.frame(nowMs, guiWidth, guiHeight, scale);

        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        if (backdrop == null || backdropScale != scale) {
            Arrays.fill(pixels, 0);
            ImageCanvas canvas = new ImageCanvas(image, scale, assets, font);
            Backdrop.paint(canvas, guiWidth, guiHeight, assets.hasMinecraft());
            canvas.dispose();
            backdrop = pixels.clone();
            backdropScale = scale;
        } else {
            System.arraycopy(backdrop, 0, pixels, 0, pixels.length);
        }

        ImageCanvas canvas = new ImageCanvas(image, scale, assets, font);
        if (scene.error() == null) scene.paint(canvas);
        canvas.dispose();

        // A fresh canvas: a scene that failed mid-paint may have left state pushed.
        ImageCanvas top = new ImageCanvas(image, scale, assets, font);
        if (scene.error() != null) paintError(top, scene.error(), guiWidth, guiHeight);
        else if (overlay != null) overlay.accept(top);
        top.dispose();
        return image;
    }

    /** The exception and the top of its stack trace (and causes) over a dark red panel. */
    private static void paintError(ImageCanvas canvas, Throwable error, float width, float height) {
        canvas.fillRect(0, 0, width, height, 0xE0300808);
        float y = 4;
        for (String line : errorLines(error)) {
            if (y > height) break;
            boolean heading = !line.startsWith(" ");
            canvas.drawText(line, 4, y, MinecraftFont.NATIVE, heading ? 0xFFFF6060 : 0xFFD0D0D0, 0, heading);
            y += 10;
        }
    }

    private static List<String> errorLines(Throwable error) {
        List<String> lines = new ArrayList<>();
        for (Throwable t = error; t != null; t = t.getCause()) {
            lines.add((t == error ? "" : "Caused by: ") + t);
            StackTraceElement[] trace = t.getStackTrace();
            for (int i = 0; i < Math.min(trace.length, 6); i++) lines.add("  at " + trace[i]);
        }
        return lines;
    }
}
