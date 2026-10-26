package dev.vellum.engine.host;

import dev.vellum.engine.dom.Element;
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

    /**
     * What the narrator calls the content (an item's name, the item in a slot), or null when it has none (an empty
     * slot). It names the element unless the page labels it ({@code aria-label}), and is read where the element is in
     * text being narrated, as an image's {@code alt} is. Asked when narrating, so it can follow the content.
     */
    default String accessibleName() { return null; }

    /** Called every frame before layout; return true if the intrinsic size changed (e.g. an image finished loading). */
    default boolean update() { return false; }

    /**
     * Whether a child element of the element is laid out after all (a scene's {@code <scene-anchor>}s): as an
     * out-of-flow box of the element's box, which the content may move every frame after layout
     * ({@link dev.vellum.engine.layout.Box#hostOffsetX}). The other children of replaced elements never are.
     */
    default boolean laysOutChild(Element child) { return false; }

    /**
     * Called every frame after layout, so content can follow the layout, styles and pointer (and move the boxes it
     * lays out); it may fire events. Return true if what it paints changed.
     */
    default boolean afterLayout() { return false; }

    /**
     * Whether the content is still loading and will change by itself once it has (a player head's skin being
     * fetched). The document keeps asking for frames and does not count as settled meanwhile
     * ({@link dev.vellum.engine.dom.Document#settled}). Content that failed to load is not loading.
     */
    default boolean loading() { return false; }

    /** Called when the element leaves the document or the document is closed. */
    default void dispose() {}
}
