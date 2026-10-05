package dev.vellum.preview.host;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.FontMetrics;
import dev.vellum.engine.host.FontSpec;
import dev.vellum.engine.paint.Canvas;
import dev.vellum.preview.render.MinecraftAssets;
import dev.vellum.preview.render.MinecraftFont;
import dev.vellum.preview.render.PaintedContent;
import dev.vellum.preview.render.Texture;

import java.util.Optional;
import java.util.Set;

/**
 * The previewer's stand-ins for Minecraft's replaced elements: items as their flat item (or block) texture,
 * player heads as the default skin's face, entities as a silhouette, slots as nothing (CSS draws the slot), plus
 * {@code <img>}, {@code <sprite>} and blank {@code <canvas>}. Attributes are read when painting, so edits show up
 * without reloading.
 */
final class ReplacedElements {
    static final Set<String> TAGS = Set.of("item", "slot", "entity", "player-head", "sprite", "img", "canvas");

    private static final int WHITE = 0xFFFFFFFF;
    private static final String STEVE = "minecraft:textures/entity/player/wide/steve.png";

    private ReplacedElements() {}

    static PaintedContent create(Element element, MinecraftAssets assets, FontMetrics fonts) {
        return switch (element.tagName()) {
            case "item" -> new Item(element, assets, fonts);
            case "slot" -> new Fixed(18, 18, (canvas, x, y, w, h) -> {});
            case "entity" -> new Fixed(32, 48, ReplacedElements::paintSilhouette);
            case "player-head" -> new Fixed(8, 8, ReplacedElements::paintFace);
            case "img", "sprite" -> new Picture(element, assets);
            case "canvas" -> new Fixed(size(element, "width", 300), size(element, "height", 150), (canvas, x, y, w, h) -> {});
            default -> null;
        };
    }

    private static float size(Element element, String attribute, float fallback) {
        String value = element.getAttribute(attribute);
        try {
            return value == null ? fallback : Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private interface Paint {
        void paint(Canvas canvas, float x, float y, float width, float height);
    }

    private record Fixed(float intrinsicWidth, float intrinsicHeight, Paint painter) implements PaintedContent {
        @Override
        public void paint(Canvas canvas, float x, float y, float width, float height) {
            painter.paint(canvas, x, y, width, height);
        }
    }

    /** {@code <item id count>}: the flat texture, and the stack size like vanilla's {@code itemCount}. */
    private record Item(Element element, MinecraftAssets assets, FontMetrics fonts) implements PaintedContent {
        @Override public float intrinsicWidth() { return 16; }
        @Override public float intrinsicHeight() { return 16; }

        /** The flat item texture, else the block texture of the same name (the missing texture if neither exists). */
        private String texture() {
            String id = element.getAttribute("id");
            if (id == null) return "";
            String item = MinecraftAssets.assetUrl(id, "textures/item/", ".png");
            return assets.texture(item).isPresent() ? item : MinecraftAssets.assetUrl(id, "textures/block/", ".png");
        }

        @Override
        public void paint(Canvas canvas, float x, float y, float width, float height) {
            canvas.drawImage(texture(), x, y, width, height, 0, 0, 1, 1, WHITE, false);
            String count = element.getAttribute("count");
            if (count == null || count.isBlank() || count.equals("1")) return;
            // Vanilla draws the count at (17 - width, 9) in the 16px slot, white with the native shadow.
            float s = width / 16;
            FontSpec font = new FontSpec(MinecraftFont.NATIVE.families(), 8 * s, false, false);
            canvas.drawText(count, x + 17 * s - fonts.width(count, font), y + 9 * s, font, WHITE, 0, true);
        }
    }

    /** {@code <img src>} (a texture, a document-relative path, or {@code sprite:ns:path}) and {@code <sprite src>}. */
    private record Picture(Element element, MinecraftAssets assets) implements PaintedContent {
        private String source() {
            String src = element.getAttribute("src");
            return src == null ? "" : src;
        }

        /** The sprite id when this shows a sprite, else null. */
        private String sprite() {
            if (element.tagName().equals("sprite")) return source();
            return source().startsWith("sprite:") ? source().substring("sprite:".length()) : null;
        }

        private String textureUrl() {
            return element.ownerDocument().resolveUrl(source());
        }

        private Optional<Texture> texture() {
            return sprite() != null ? assets.sprite(sprite()) : assets.texture(textureUrl());
        }

        @Override public float intrinsicWidth() { return texture().map(t -> (float) t.naturalWidth()).orElse(Float.NaN); }
        @Override public float intrinsicHeight() { return texture().map(t -> (float) t.naturalHeight()).orElse(Float.NaN); }

        @Override
        public void paint(Canvas canvas, float x, float y, float width, float height) {
            if (sprite() != null) canvas.drawSprite(sprite(), x, y, width, height, WHITE);
            else canvas.drawImage(textureUrl(), x, y, width, height, 0, 0, 1, 1, WHITE, false);
        }
    }

    /** The default skin's face and hat layer. */
    private static void paintFace(Canvas canvas, float x, float y, float width, float height) {
        canvas.drawImage(STEVE, x, y, width, height, 8 / 64f, 8 / 64f, 16 / 64f, 16 / 64f, WHITE, false);
        canvas.drawImage(STEVE, x, y, width, height, 40 / 64f, 8 / 64f, 48 / 64f, 16 / 64f, WHITE, false);
    }

    /** A player-shaped silhouette (16×32 model units: head, body, arms, legs) fitted into the box. */
    private static void paintSilhouette(Canvas canvas, float x, float y, float width, float height) {
        float u = Math.min(width / 16, height / 32);
        float ox = x + (width - 16 * u) / 2, oy = y + (height - 32 * u) / 2;
        float[][] parts = {{4, 0, 8, 8}, {4, 8, 8, 12}, {0, 8, 4, 12}, {12, 8, 4, 12}, {4, 20, 4, 12}, {8, 20, 4, 12}};
        int[] colors = {0xC0505050, 0xC0404040, 0xC0363636, 0xC0363636, 0xC0303030, 0xC02A2A2A};
        for (int i = 0; i < parts.length; i++) {
            float[] p = parts[i];
            canvas.fillRect(ox + p[0] * u, oy + p[1] * u, p[2] * u, p[3] * u, colors[i]);
        }
    }
}
