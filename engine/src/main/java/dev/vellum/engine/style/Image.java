package dev.vellum.engine.style;

import java.util.List;

/** An image value: {@code url()}, a Minecraft GUI sprite, or a gradient. */
public sealed interface Image {
    /**
     * {@code url("ns:path/to/texture.png")} or a path relative to the document. Hosts resolve it; in Minecraft it
     * names a texture. {@code canvas:<id>} refers to a {@code <canvas>} element's backing texture.
     */
    record Url(String url) implements Image {}

    /**
     * {@code sprite("minecraft:widget/button")}: a sprite from the GUI atlas. Hosts honour the sprite's own scaling
     * metadata (stretch, tile or nine-slice), which is how vanilla buttons and panels are drawn.
     */
    record Sprite(String id) implements Image {}

    /** {@code linear-gradient(<angle>, stops...)}. Angle in degrees, CSS convention (0 = to top, 90 = to right). */
    record LinearGradient(float angleDeg, List<ColorStop> stops, boolean repeating) implements Image {}

    /** {@code radial-gradient(circle|ellipse at x y, stops...)}, farthest-corner sizing. */
    record RadialGradient(boolean circle, Length centerX, Length centerY, List<ColorStop> stops) implements Image {}

    /** A colour stop; {@code position} is a length or percentage along the gradient line, or null if unspecified. */
    record ColorStop(int color, Length position) {}
}
