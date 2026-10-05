package dev.vellum.preview.host;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.FontMetrics;
import dev.vellum.engine.host.FontSpec;
import dev.vellum.engine.host.ReplacedContent;
import dev.vellum.engine.paint.Canvas;
import dev.vellum.preview.render.MinecraftAssets;
import dev.vellum.preview.render.MinecraftFont;

import java.util.Map;
import java.util.function.Function;

/**
 * The previewer's stand-ins for the Minecraft elements, at the game's sizes: items and models as their flat item (or
 * block) texture (items with the stack size), player heads as the default skin's face, entities as a silhouette,
 * and slots as nothing (CSS draws the slot). Attributes are read when painting, so edits show up without reloading.
 */
final class ReplacedElements {
    private static final int WHITE = 0xFFFFFFFF;
    private static final String STEVE = "minecraft:textures/entity/player/wide/steve.png";

    private ReplacedElements() {}

    /** The elements by tag, for {@link PreviewHost#replacedElements}. */
    static Map<String, Function<Element, ReplacedContent>> of(MinecraftAssets assets, FontMetrics fonts) {
        return Map.of(
                "item", element -> new Item(element, assets, fonts),
                "slot", element -> new Fixed(18, 18, (canvas, x, y, w, h) -> {}),
                "entity", element -> new Fixed(48, 48, ReplacedElements::paintSilhouette),
                "model", element -> new Fixed(32, 32, (canvas, x, y, w, h) -> paintModel(canvas, element, assets, x, y, w, h)),
                "player-head", element -> new Fixed(16, 16, ReplacedElements::paintFace));
    }

    private interface Paint {
        void paint(Canvas canvas, float x, float y, float width, float height);
    }

    private record Fixed(float intrinsicWidth, float intrinsicHeight, Paint painter) implements ReplacedContent {
        @Override
        public void paint(Canvas canvas, float x, float y, float width, float height) {
            painter.paint(canvas, x, y, width, height);
        }
    }

    /** {@code <item id count>}: the flat texture, and the stack size like vanilla's {@code itemCount}. */
    private static final class Item implements ReplacedContent {
        private final Element element;
        private final MinecraftAssets assets;
        private final FontMetrics fonts;
        /** The count's font at the last painted size. */
        private FontSpec countFont = MinecraftFont.NATIVE;

        Item(Element element, MinecraftAssets assets, FontMetrics fonts) {
            this.element = element;
            this.assets = assets;
            this.fonts = fonts;
        }

        @Override public float intrinsicWidth() { return 16; }
        @Override public float intrinsicHeight() { return 16; }

        @Override
        public void paint(Canvas canvas, float x, float y, float width, float height) {
            canvas.drawImage(flatTexture(assets, element.getAttribute("id")), x, y, width, height, 0, 0, 1, 1, WHITE, false);
            String count = element.getAttribute("count");
            if (count == null || count.isBlank() || count.equals("1")) return;
            // Vanilla draws the count at (17 - width, 9) in the 16px slot, white with the native shadow.
            float s = width / 16;
            if (countFont.size() != 8 * s) countFont = new FontSpec(MinecraftFont.NATIVE.families(), 8 * s, false, false);
            canvas.drawText(count, x + 17 * s - fonts.width(count, countFont), y + 9 * s, countFont, WHITE, 0, true);
        }
    }

    /** The flat item texture, else the block texture of the same name (the missing texture if neither exists). */
    private static String flatTexture(MinecraftAssets assets, String id) {
        if (id == null) return "";
        String item = MinecraftAssets.assetUrl(id, "textures/item/", ".png");
        return assets.texture(item).isPresent() ? item : MinecraftAssets.assetUrl(id, "textures/block/", ".png");
    }

    /** {@code <model item>} or {@code <model block>}: the flat texture, centred, at the size the model would be. */
    private static void paintModel(Canvas canvas, Element element, MinecraftAssets assets, float x, float y, float width, float height) {
        String block = element.getAttribute("block");
        String id = block != null ? block.split("\\[", 2)[0].strip() : element.getAttribute("item");
        float size = Math.min(width, height) * (block != null ? 0.625f : 1);
        canvas.drawImage(flatTexture(assets, id), x + (width - size) / 2, y + (height - size) / 2, size, size, 0, 0, 1, 1, WHITE, false);
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
