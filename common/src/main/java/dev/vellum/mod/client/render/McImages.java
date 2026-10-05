package dev.vellum.mod.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minecraft's side of images, cached so painting does no parsing: identifiers for the URLs and sprite ids pages use,
 * the natural size of textures (from the PNG header, without decoding) and of GUI sprites. Each cache is bounded,
 * since scripts can make up URLs. Render thread only; cleared on resource reload.
 */
public final class McImages {
    private static final int CAPACITY = 1024;
    private static final float[] UNKNOWN = new float[0];

    private static final Map<String, Identifier> IDS = lru();
    private static final Map<String, float[]> TEXTURE_SIZES = lru(), SPRITE_SIZES = lru();
    /** Marks a string that is no valid identifier in {@link #IDS}. */
    private static final Identifier INVALID = Identifier.withDefaultNamespace("invalid");

    private McImages() {}

    /** The identifier a URL or sprite id names, or null when it is not one. */
    public static @Nullable Identifier id(String url) {
        Identifier id = IDS.get(url);
        if (id == null) {
            id = Identifier.tryParse(url);
            IDS.put(url, id == null ? INVALID : id);
        }
        return id == INVALID ? null : id;
    }

    /** {width, height} in px of the texture at {@code url}, or null when unknown. Shared: do not modify. */
    public static float @Nullable [] textureSize(String url) {
        return known(TEXTURE_SIZES.computeIfAbsent(url, McImages::readPngSize));
    }

    /** {width, height} in px of a GUI sprite, or null when there is no such sprite. Shared: do not modify. */
    public static float @Nullable [] spriteSize(String spriteId) {
        return known(SPRITE_SIZES.computeIfAbsent(spriteId, McImages::readSpriteSize));
    }

    /** Resource packs may have replaced images and sprites. */
    public static void clearCaches() {
        TEXTURE_SIZES.clear();
        SPRITE_SIZES.clear();
    }

    private static float @Nullable [] known(float[] size) {
        return size == UNKNOWN ? null : size;
    }

    /** Width and height from the PNG's IHDR chunk. */
    private static float[] readPngSize(String url) {
        Identifier id = id(url);
        var resource = id == null ? null : Minecraft.getInstance().getResourceManager().getResource(id).orElse(null);
        if (resource == null) return UNKNOWN;
        try (InputStream in = resource.open()) {
            byte[] header = in.readNBytes(24);
            if (header.length < 24 || header[1] != 'P' || header[2] != 'N' || header[3] != 'G') return UNKNOWN;
            return new float[] {readInt(header, 16), readInt(header, 20)};
        } catch (IOException e) {
            return UNKNOWN;
        }
    }

    private static float[] readSpriteSize(String spriteId) {
        Identifier id = id(spriteId);
        if (id == null) return UNKNOWN;
        TextureAtlasSprite sprite = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.GUI).getSprite(id);
        // A missing sprite comes back as the atlas's missing sprite.
        return sprite.contents().name().equals(id) ? new float[] {sprite.contents().width(), sprite.contents().height()} : UNKNOWN;
    }

    private static int readInt(byte[] b, int at) {
        return (b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | (b[at + 3] & 0xFF);
    }

    private static <V> Map<String, V> lru() {
        return new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > CAPACITY;
            }
        };
    }
}
