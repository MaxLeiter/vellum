package dev.vellum.mod.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import dev.vellum.mod.Constants;
import dev.vellum.mod.client.VellumResources;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Images from folder namespaces ({@code VellumPages}), which the texture manager can't load itself: each is read
 * from its file and registered under its own id as a {@link DynamicTexture} the first time a page draws it, and
 * dropped when files change or resources reload. Render thread only.
 */
public final class FolderTextures {
    /** Whether each id read: a missing or unreadable file is remembered too, so it isn't read every frame. */
    private static final Map<Identifier, Boolean> LOADED = new HashMap<>();

    private FolderTextures() {}

    /** Registers the texture for {@code id} if needed; false when its file is missing or isn't a PNG. */
    static boolean ensure(Identifier id) {
        return LOADED.computeIfAbsent(id, FolderTextures::load);
    }

    private static boolean load(Identifier id) {
        byte[] bytes = VellumResources.loadFolderBytes(id);
        if (bytes == null) return false;
        try {
            NativeImage image = NativeImage.read(new ByteArrayInputStream(bytes));
            //? if >=26 {
            DynamicTexture texture = new DynamicTexture(() -> "Vellum " + id, image);
            //?} else
            //DynamicTexture texture = new DynamicTexture(image);
            Minecraft.getInstance().getTextureManager().register(id, texture);
            return true;
        } catch (IOException e) {
            Constants.LOG.warn("Vellum: {} is not a PNG image: {}", id, e.toString());
            return false;
        }
    }

    /** Files changed or resources reloaded: every folder image is read again when next drawn. */
    public static void clear() {
        var textures = Minecraft.getInstance().getTextureManager();
        for (var e : LOADED.entrySet()) if (e.getValue()) textures.release(e.getKey());
        LOADED.clear();
    }
}
