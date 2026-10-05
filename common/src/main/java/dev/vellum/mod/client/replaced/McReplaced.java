package dev.vellum.mod.client.replaced;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.ReplacedContent;
import dev.vellum.engine.paint.Canvas;
import dev.vellum.mod.client.render.McCanvas;

import java.util.Map;
import java.util.function.Function;

/**
 * The Minecraft elements, drawn by Minecraft: {@code <item>}, {@code <slot>}, {@code <entity>} and
 * {@code <player-head>} ({@code img}, {@code sprite} and {@code canvas} are the engine's). Layout sizes them like
 * images (intrinsic size, CSS size, object-fit).
 */
public abstract class McReplaced implements ReplacedContent {
    /** The host's replaced elements, by tag ({@code Host.replacedElements}). */
    public static final Map<String, Function<Element, ReplacedContent>> ELEMENTS = Map.of(
            "item", ItemContent::new, "slot", SlotContent::new, "entity", EntityContent::new, "player-head", PlayerHeadContent::new);

    protected final Element element;

    protected McReplaced(Element element) {
        this.element = element;
    }

    /**
     * Minecraft content draws with Minecraft calls, so it needs the Minecraft canvas; documents in game are always
     * painted on one, so this is the one place that looks for it.
     */
    @Override
    public final void paint(Canvas canvas, float x, float y, float width, float height) {
        if (canvas instanceof McCanvas mc) draw(mc, x, y, width, height);
    }

    /** Draws into the content box {@code (x, y, width, height)}, in the canvas's current transform. */
    protected abstract void draw(McCanvas canvas, float x, float y, float width, float height);

    protected String attr(String name, String fallback) {
        String v = element.getAttribute(name);
        return v == null || v.isBlank() ? fallback : v.strip();
    }
}
