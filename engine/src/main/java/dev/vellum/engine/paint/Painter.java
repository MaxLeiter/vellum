package dev.vellum.engine.paint;

import dev.vellum.engine.dom.Document;

/**
 * Paints the box tree onto a {@link Canvas} in CSS paint order (stacking contexts, z-index), and answers hit tests
 * with the same traversal so what you click is what you see. STUB: implemented by the paint workstream.
 */
public final class Painter {
    private final Document document;

    public Painter(Document document) {
        this.document = document;
    }

    public void paint(Canvas canvas) {
        throw new UnsupportedOperationException("TODO");
    }

    /** The topmost hit at a viewport point, or null. */
    public HitResult hitTest(float x, float y) {
        throw new UnsupportedOperationException("TODO");
    }
}
