package dev.vellum.engine.paint;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.FontSpec;
import dev.vellum.engine.host.ReplacedContent;

/**
 * The drawing backend. The {@link Painter} turns the box tree into calls on this interface each frame; Minecraft
 * implements it over {@code GuiGraphicsExtractor}, tests and the previewer implement it over Java2D.
 *
 * <p>Backends implement a small set of primitives. Shapes such as rounded rectangles, rings and gradients have
 * default implementations that tessellate into {@link #fillQuads}; a backend may override them with something
 * better (an SDF shader, say).
 *
 * <p>Coordinates are GUI px in the current transform. Colours are ARGB and are multiplied by the current alpha.
 */
public interface Canvas {
    /** Text decoration flags for {@link #drawText}. */
    int UNDERLINE = 1, STRIKETHROUGH = 2;

    // ---- State ----

    /** Pushes transform, clip and alpha. */
    void save();

    /** Pops what the matching {@link #save} pushed. */
    void restore();

    void translate(float dx, float dy);

    /**
     * Post-multiplies the current transform by the affine matrix {@code [a c e; b d f; 0 0 1]} (the CSS
     * {@code matrix(a, b, c, d, e, f)} convention).
     */
    void transform(float a, float b, float c, float d, float e, float f);

    /** Multiplies the alpha applied to everything drawn until the matching {@link #restore}. */
    void multiplyAlpha(float alpha);

    /**
     * Intersects the clip with a rectangle in the current coordinate space. Backends that only support
     * axis-aligned scissoring clip to the transformed rectangle's bounding box.
     */
    void clipRect(float x, float y, float width, float height);

    /** Size of one device pixel in GUI px at the current transform (1 / GUI scale), for crisp hairlines. */
    default float devicePixel() { return 1f; }

    // ---- Primitives ----

    void fillRect(float x, float y, float width, float height, int argb);

    /**
     * Fills {@code quadCount} convex quads. {@code xy} holds 8 floats per quad (four vertices in order around the
     * quad); {@code colors} holds 4 ARGB values per quad, one per vertex, interpolated across it. A triangle is a
     * quad with its last vertex repeated. The painter emits every quad with the winding of vanilla's {@code fill}
     * ((x0,y0), (x0,y1), (x1,y1), (x1,y0): a negative signed area in GUI coordinates), so back-face culling keeps
     * them unless the transform mirrors. The arrays may be longer than needed and are reused by the caller after
     * the call returns: copy what you keep.
     */
    void fillQuads(float[] xy, int[] colors, int quadCount);

    /**
     * Draws a single line of text with the top-left of its glyph box at (x, y). {@code shadow} requests the
     * host's native drop shadow (Minecraft's 1px dark shadow); CSS text-shadows are drawn by the painter as extra
     * passes instead.
     */
    void drawText(String text, float x, float y, FontSpec font, int argb, int decorations, boolean shadow);

    /**
     * Draws (part of) an image. UVs are normalised 0..1. {@code tint} is multiplied in (white for none).
     * Hosts resolve {@code url} to a texture; unknown images draw nothing (or a placeholder).
     */
    void drawImage(String url, float x, float y, float width, float height,
                   float u0, float v0, float u1, float v1, int tint, boolean smooth);

    /**
     * Draws a GUI sprite by id, honouring the sprite's own scaling (stretch, tile, nine-slice). Backends without
     * sprites may treat the id as an image URL.
     */
    void drawSprite(String spriteId, float x, float y, float width, float height, int tint);

    /**
     * The intrinsic size {width, height} in px of the image {@link #drawImage} would draw for {@code url}, or null
     * when unknown (not loaded yet, or a host without the information). Used for {@code background-size}; images of
     * unknown size fill the background positioning area. The array may be reused by the backend.
     */
    default float[] imageSize(String url) { return null; }

    /** Draws host-provided replaced content (items, entities, slots...) into the given content box. */
    void drawReplaced(ReplacedContent content, Element element, float x, float y, float width, float height);

    // ---- Shapes with default tessellation ----

    /**
     * Fills a rounded rectangle. {@code radii} holds 8 floats: horizontal and vertical radius for the top-left,
     * top-right, bottom-right and bottom-left corners, already clamped to fit.
     */
    default void fillRoundedRect(float x, float y, float width, float height, float[] radii, int argb) {
        Shapes.fillRoundedRect(this, x, y, width, height, radii, argb);
    }

    /**
     * Fills the region between an outer rounded rectangle and an inner one (a border). Side colours follow the
     * CSS rule: each side's trapezoid gets its own colour, with corners split diagonally.
     *
     * @param outer  {x, y, width, height} of the border box
     * @param radii  outer corner radii, as in {@link #fillRoundedRect}
     * @param widths top, right, bottom, left border widths
     * @param colors top, right, bottom, left ARGB colours
     */
    default void fillBorder(float[] outer, float[] radii, float[] widths, int[] colors) {
        Shapes.fillBorder(this, outer, radii, widths, colors);
    }
}
