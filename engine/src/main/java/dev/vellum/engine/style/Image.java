package dev.vellum.engine.style;

import java.util.List;
import java.util.function.UnaryOperator;

/** An image value: a texture, a Minecraft GUI sprite, a canvas's pixels, or a gradient. */
public sealed interface Image {
    String SPRITE_SCHEME = "sprite:", CANVAS_SCHEME = "canvas:";

    /**
     * The image a URL names, the one place image URLs are read ({@code url()} in CSS, {@code <img src>}):
     * {@code sprite:ns:path} is a GUI sprite, {@code canvas:id} the canvas element with that id, and anything else a
     * texture, its URL resolved with {@code resolve} (against the stylesheet or document).
     */
    static Image ofUrl(String url, UnaryOperator<String> resolve) {
        if (url.startsWith(SPRITE_SCHEME)) return new Sprite(url.substring(SPRITE_SCHEME.length()));
        if (url.startsWith(CANVAS_SCHEME)) return new Canvas(url.substring(CANVAS_SCHEME.length()));
        return new Url(resolve.apply(url));
    }

    /** A texture: {@code url("ns:textures/....png")} or a path relative to the document. Hosts load it. */
    record Url(String url) implements Image {}

    /**
     * {@code sprite("minecraft:widget/button")}: a sprite from the GUI atlas. Hosts honour the sprite's own scaling
     * metadata (stretch, tile or nine-slice), which is how vanilla buttons and panels are drawn.
     */
    record Sprite(String id) implements Image {}

    /** {@code url("canvas:minimap")}: the pixels of the {@code <canvas id="minimap">} in the document, as they change. */
    record Canvas(String id) implements Image {}

    /** {@code linear-gradient(<angle>, stops...)}. Angle in degrees, CSS convention (0 = to top, 90 = to right). */
    record LinearGradient(float angleDeg, List<ColorStop> stops, boolean repeating) implements Image {}

    /** {@code radial-gradient(circle|ellipse at x y, stops...)}, farthest-corner sizing. */
    record RadialGradient(boolean circle, Length centerX, Length centerY, List<ColorStop> stops) implements Image {}

    /** A colour stop; {@code position} is a length or percentage along the gradient line, or null if unspecified. */
    record ColorStop(int color, Length position) {}
}
