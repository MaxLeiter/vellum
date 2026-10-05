package dev.vellum.preview.render;

import dev.vellum.engine.host.ArraySurface;

import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBufferInt;
import java.awt.image.Raster;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A canvas's pixels in the previewer: an {@link ArraySurface} whose array is also a {@link BufferedImage},
 * registered with the assets as {@code vellum:canvas/<n>} (the game's texture id) so {@link ImageCanvas} draws it like
 * any texture, always with the current pixels.
 */
public final class PreviewSurface extends ArraySurface {
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    private final MinecraftAssets assets;
    private final String url = "vellum:canvas/" + NEXT_ID.getAndIncrement();

    public PreviewSurface(MinecraftAssets assets, int width, int height) {
        super(width, height);
        this.assets = assets;
        int[] pixels = pixels();
        var raster = Raster.createPackedRaster(new DataBufferInt(pixels, pixels.length), width, height, width,
                new int[] {0xFF0000, 0xFF00, 0xFF, 0xFF000000}, null);
        assets.register(url, new Texture(new BufferedImage(ColorModel.getRGBdefault(), raster, false, null), false,
                SpriteScaling.STRETCH));
    }

    @Override
    public String url() {
        return url;
    }

    @Override
    public void dispose() {
        assets.release(url);
    }
}
