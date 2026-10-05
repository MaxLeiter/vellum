package dev.vellum.engine.paint;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.layout.Box;

/**
 * Paints the box tree onto a {@link Canvas} in CSS paint order (stacking contexts, z-index), and answers hit tests
 * with the same traversal ({@link StackingOrder}) so what you click is what you see.
 */
public final class Painter {
    private final Document document;
    /** Separate walkers so a hit test from inside painting (a control, say) cannot disturb the paint walk. */
    private final StackingOrder paintOrder = new StackingOrder(), hitOrder = new StackingOrder();
    private BoxPainter boxPainter;
    private HitTester hitTester;

    public Painter(Document document) {
        this.document = document;
    }

    /** Paints the current layout, then the input handler's overlays (dropdowns...) on top with an identity transform. */
    public void paint(Canvas canvas) {
        Box root = document.layoutEngine().root();
        if (root != null) paint(canvas, root);
        document.input().paintOverlays(canvas);
    }

    /** Paints the box tree under {@code root} (no overlays). */
    public void paint(Canvas canvas, Box root) {
        if (boxPainter == null) boxPainter = new BoxPainter(this, document.host().fonts());
        boxPainter.begin(canvas);
        try {
            paintOrder.walk(root, boxPainter);
        } finally {
            boxPainter.end();
        }
    }

    /** The topmost hit at a viewport point, or null. */
    public HitResult hitTest(float x, float y) {
        Box root = document.layoutEngine().root();
        return root == null ? null : hitTest(root, x, y);
    }

    /** The topmost hit at a point in {@code root}'s coordinate space, or null. */
    public HitResult hitTest(Box root, float x, float y) {
        if (hitTester == null) hitTester = new HitTester(this, document.host().fonts());
        hitTester.begin(x, y);
        hitOrder.walk(root, hitTester);
        return hitTester.result();
    }

    /**
     * Whether the scrollbar of {@code container} on that axis is widened (hovered or being dragged), for both
     * painting and hit testing. TODO(integration): {@code document.input().isScrollbarHovered(container.element, vertical)}.
     */
    boolean scrollbarHovered(Box container, boolean vertical) {
        return container.element.isHovered();
    }
}
