package dev.vellum.engine.host;

import dev.vellum.engine.paint.Canvas;

/**
 * The content of a replaced element: an image, a canvas, or a Minecraft item, slot, entity or head. The engine
 * creates it when the element enters the document (from its own {@code img}, {@code sprite} and {@code canvas} or
 * the {@link Host#replacedElements host's elements}), keeps it while the element moves within the document, and
 * disposes it when the element leaves. Layout sizes it like an image (natural size, CSS size, object-fit); the
 * painter calls {@link #paint} with the box it fitted.
 */
public interface ReplacedContent {
    /** Intrinsic width in px, or NaN if none. */
    float intrinsicWidth();

    /** Intrinsic height in px, or NaN if none. */
    float intrinsicHeight();

    /**
     * Paints into the box {@code (x, y, width, height)}, in GUI px in the canvas's current transform. Content that
     * needs more than {@link Canvas} offers (a Minecraft item) may expect its host's canvas: a document is painted
     * on the canvas of the host that made its content.
     */
    void paint(Canvas canvas, float x, float y, float width, float height);

    /** Called when an attribute of the element changes, so the content can reload (e.g. a new src or item id). */
    default void attributeChanged(String name) {}

    /**
     * Whether the content shows a tooltip of its own while the pointer is on it, at once and with no delay (an
     * {@code <item tooltip>} shows the item's). The engine then reports it as the tooltip's
     * {@link dev.vellum.engine.input.Tooltip#content() content}, and the host draws it with the lines of the
     * {@code title} that applies after its own. Asked whenever the host asks for the tooltip, so it can follow
     * attributes.
     */
    default boolean showsTooltip() { return false; }

    /** Called every frame before layout; return true if the intrinsic size changed (e.g. an image finished loading). */
    default boolean update() { return false; }

    /**
     * Whether the content is still loading and will change by itself once it has (a player head's skin being
     * fetched). The document keeps asking for frames and does not count as settled meanwhile
     * ({@link dev.vellum.engine.dom.Document#settled}). Content that failed to load is not loading.
     */
    default boolean loading() { return false; }

    /** Called when the element leaves the document or the document is closed. */
    default void dispose() {}
}
