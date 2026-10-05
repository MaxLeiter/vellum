package dev.vellum.engine.host;

/**
 * Content of a replaced element, supplied by the host: images, Minecraft items, container slots, entities, canvases.
 * The engine lays it out like an {@code <img>} (intrinsic size, object-fit) and asks the canvas to draw it via
 * {@link dev.vellum.engine.paint.Canvas#drawReplaced}, where the host backend recognises its own implementation.
 */
public interface ReplacedContent {
    /** Intrinsic width in px, or NaN if none. */
    float intrinsicWidth();

    /** Intrinsic height in px, or NaN if none. */
    float intrinsicHeight();

    /** Called when an attribute of the element changes, so the content can reload (e.g. a new src or item id). */
    default void attributeChanged(String name) {}

    /** Called every frame before layout; return true if the intrinsic size changed (e.g. an image finished loading). */
    default boolean update() { return false; }

    /** Called when the element leaves the document or the document is closed. */
    default void dispose() {}
}
