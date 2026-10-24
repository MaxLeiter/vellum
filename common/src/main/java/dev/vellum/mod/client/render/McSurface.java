package dev.vellum.mod.client.render;

import com.mojang.blaze3d.platform.NativeImage;
//? if >=26
import com.mojang.blaze3d.systems.RenderSystem;
import dev.vellum.engine.host.PixelSurface;
import dev.vellum.mod.Constants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * A canvas's pixels in game: a {@link NativeImage} in a {@link DynamicTexture} registered as
 * {@code vellum:canvas/<n>}, so {@link McCanvas} draws it like any texture. An upload sends only the rows written
 * since the last one, not the whole (up to 2048²) image. Render thread only. On 1.21.1 images hold ABGR.
 */
public final class McSurface implements PixelSurface {
    private static int nextId;

    private final Identifier id = Constants.id("canvas/" + nextId++);
    private final NativeImage pixels;
    private final DynamicTexture texture;
    /** Rows written since the last upload: {@code [dirtyTop, dirtyBottom)}, empty when top >= bottom. */
    private int dirtyTop = Integer.MAX_VALUE, dirtyBottom;

    public McSurface(int width, int height) {
        pixels = new NativeImage(width, height, true);
        //? if >=26 {
        texture = new DynamicTexture(() -> "Vellum " + id, pixels); // uploads the cleared image
        //?} else
        //texture = new DynamicTexture(pixels); // uploads the cleared image
        Minecraft.getInstance().getTextureManager().register(id, texture);
    }

    @Override
    public int width() {
        return pixels.getWidth();
    }

    @Override
    public int height() {
        return pixels.getHeight();
    }

    @Override
    public String url() {
        return id.toString();
    }

    @Override
    public void fillRect(int x, int y, int width, int height, int argb) {
        //? if >=26 {
        pixels.fillRect(x, y, width, height, argb);
        //?} else
        //pixels.fillRect(x, y, width, height, swapRedBlue(argb));
        written(y, height);
    }

    @Override
    public void getPixels(int x, int y, int width, int height, int[] argb) {
        for (int row = 0; row < height; row++) {
            //? if >=26 {
            for (int i = 0; i < width; i++) argb[row * width + i] = pixels.getPixel(x + i, y + row);
            //?} else
            //for (int i = 0; i < width; i++) argb[row * width + i] = swapRedBlue(pixels.getPixelRGBA(x + i, y + row));
        }
    }

    @Override
    public void setPixels(int x, int y, int width, int height, int[] argb) {
        for (int row = 0; row < height; row++) {
            //? if >=26 {
            for (int i = 0; i < width; i++) pixels.setPixel(x + i, y + row, argb[row * width + i]);
            //?} else
            //for (int i = 0; i < width; i++) pixels.setPixelRGBA(x + i, y + row, swapRedBlue(argb[row * width + i]));
        }
        written(y, height);
    }

    @Override
    public void upload() {
        if (dirtyTop >= dirtyBottom) return;
        //? if >=26 {
        int rowBytes = width() * 4, rows = dirtyBottom - dirtyTop;
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture.getTexture(),
                pixels.getPixelBytes().slice(dirtyTop * rowBytes, rows * rowBytes), 0, 0, 0, dirtyTop, width(), rows);
        //?} else {
        /*texture.bind();
        pixels.upload(0, 0, dirtyTop, 0, dirtyTop, width(), dirtyBottom - dirtyTop, false, false, false, false);
        *///?}
        dirtyTop = Integer.MAX_VALUE;
        dirtyBottom = 0;
    }

    /** Releases the texture, which frees the image too. */
    @Override
    public void dispose() {
        Minecraft.getInstance().getTextureManager().release(id);
    }

    private void written(int y, int height) {
        dirtyTop = Math.min(dirtyTop, y);
        dirtyBottom = Math.max(dirtyBottom, y + height);
    }

    //? if <26 {
    /*/^* ARGB to the image's ABGR and back. ^/
    private static int swapRedBlue(int color) {
        return color & 0xFF00FF00 | color >> 16 & 0xFF | (color & 0xFF) << 16;
    }
    *///?}
}
