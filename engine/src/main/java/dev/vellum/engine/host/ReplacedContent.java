package dev.vellum.engine.host;

import dev.vellum.engine.input.Drag;
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

    /**
     * A primary press on the element that no {@code mousedown} listener cancelled, at (x, y) in its border box (local
     * px): return a drag to receive the pointer until the button is released (it gets viewport px), or null to leave
     * the press alone. 3D content uses it to turn under the pointer ({@code rotatable}).
     */
    default Drag press(float x, float y) { return null; }

    /** Called when an attribute of the element changes, so the content can reload (e.g. a new src or item id). */
    default void attributeChanged(String name) {}

    /** Called every frame before layout; return true if the intrinsic size changed (e.g. an image finished loading). */
    default boolean update() { return false; }

    /** Called when the element leaves the document or the document is closed. */
    default void dispose() {}
}
