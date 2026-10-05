package dev.vellum.mod.client.replaced;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.ReplacedContent;
import dev.vellum.mod.client.render.McCanvas;
import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * Replaced content drawn by Minecraft: the Minecraft elements ({@code <item>}, {@code <slot>}, {@code <entity>},
 * {@code <player-head>}, {@code <sprite>}) plus {@code <img>} and {@code <canvas>}. Layout sizes them like images
 * (intrinsic size, CSS size, object-fit); {@link McCanvas#drawReplaced} calls {@link #draw} with the content box.
 */
public abstract class McReplaced implements ReplacedContent {
    public static final Set<String> TAGS = Set.of("img", "canvas", "item", "slot", "entity", "player-head", "sprite");

    protected final Element element;

    protected McReplaced(Element element) {
        this.element = element;
    }

    public static @Nullable McReplaced create(Element element) {
        return switch (element.tagName()) {
            case "item" -> new ItemContent(element);
            case "slot" -> new SlotContent(element);
            case "entity" -> new EntityContent(element);
            case "player-head" -> new PlayerHeadContent(element);
            case "sprite" -> new SpriteContent(element);
            case "img" -> new ImageContent(element);
            case "canvas" -> new CanvasContent(element);
            default -> null;
        };
    }

    /** {width, height} in px of an image URL (texture, {@code sprite:} or {@code canvas:}), or null when unknown. */
    public static float @Nullable [] imageSize(String url) {
        float[] size = ImageContent.size(url);
        return Float.isNaN(size[0]) ? null : size.clone();
    }

    /** Forgets cached sizes after a resource reload; resource packs may have replaced images. */
    public static void clearCaches() {
        ImageContent.clearCache();
    }

    /** Draws into the content box {@code (x, y, width, height)}, in the canvas's current transform. */
    public abstract void draw(McCanvas canvas, Element element, float x, float y, float width, float height);

    protected String attr(String name, String fallback) {
        String v = element.getAttribute(name);
        return v == null || v.isBlank() ? fallback : v.strip();
    }

    protected float number(String name, float fallback) {
        try {
            return Float.parseFloat(attr(name, ""));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
