package dev.vellum.engine.layout;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.style.Position;

/**
 * Builds the box tree from computed styles and lays it out: block, inline, flex, grid, absolute and fixed
 * positioning, scroll containers. Sets {@code element.box} on every rendered element (null otherwise).
 *
 * <p>A pass: {@link BoxTreeBuilder} builds a fresh tree, the root box is laid out in the initial containing block
 * (the viewport) by {@link LayoutPass}, {@link PositionedLayout} places out-of-flow boxes, and finally every box gets
 * its scrollable overflow extent and scroll containers have their scroll offsets clamped to it.
 */
public final class LayoutEngine {
    private final Document document;
    private Box root;

    public LayoutEngine(Document document) {
        this.document = document;
    }

    /** The root box (for the html element), or null before the first layout or when the root is not rendered. */
    public Box root() {
        return root;
    }

    /** Lays out the whole document against the current viewport. */
    public void layout() {
        Element html = document.documentElement();
        LayoutBox box = html == null ? null : new BoxTreeBuilder().build(html);
        root = box;
        if (box == null) return;

        float vw = document.viewportWidth(), vh = document.viewportHeight();
        LayoutPass pass = new LayoutPass(document.host().fonts(), vw, vh);
        BoxModel.resolveEdges(box, vw);
        float width = pass.usedWidth(box, vw - box.marginLeft - box.marginRight, vw, vh, true);
        BoxModel.resolveAutoMargins(box, Axis.HORIZONTAL, vw - width - box.marginLeft - box.marginRight);
        pass.layout(box, width, Float.NaN, vw, vh);
        box.x = box.marginLeft;
        box.y = box.marginTop;
        PositionedLayout.applyRelativeOffset(box, vw, vh);
        new PositionedLayout(pass).layoutOutOfFlow(box);
        overflow(box);
    }

    /**
     * Sets {@code scrollWidth}/{@code scrollHeight} of {@code box} and its descendants: the extent of their border
     * boxes and line fragments from the padding-box origin, plus the end padding, and at least the padding box.
     * Clamps scroll containers' scroll offsets to the new range. Returns the box's right and bottom overflow edges in
     * its parent's space as its contribution to the parent's extent (just its border box when it clips).
     */
    private static float[] overflow(Box box) {
        float right = Float.NEGATIVE_INFINITY, bottom = Float.NEGATIVE_INFINITY;
        for (Box child : box.children) {
            float[] edge = overflow(child);
            // Fixed boxes hang off the viewport, not their parent's content.
            if (child.style.position == Position.FIXED) continue;
            right = Math.max(right, edge[0]);
            bottom = Math.max(bottom, edge[1]);
        }
        for (LineBox line : box.lines) {
            for (Fragment f : line.fragments) {
                right = Math.max(right, f.x() + f.width());
                bottom = Math.max(bottom, f.y() + f.height());
            }
        }
        box.scrollWidth = box.paddingBoxWidth();
        box.scrollHeight = box.paddingBoxHeight();
        if (right != Float.NEGATIVE_INFINITY) {
            box.scrollWidth = Math.max(box.scrollWidth, right - box.borderLeft + box.paddingRight);
            box.scrollHeight = Math.max(box.scrollHeight, bottom - box.borderTop + box.paddingBottom);
        }
        if (box.isScrollContainer()) {
            Element e = box.element;
            e.scrollLeft = Math.max(0, Math.min(e.scrollLeft, box.scrollWidth - box.paddingBoxWidth()));
            e.scrollTop = Math.max(0, Math.min(e.scrollTop, box.scrollHeight - box.paddingBoxHeight()));
        }
        boolean clipsX = box.style.overflowX.clips(), clipsY = box.style.overflowY.clips();
        float ownRight = clipsX || right == Float.NEGATIVE_INFINITY ? box.width : Math.max(box.width, right);
        float ownBottom = clipsY || bottom == Float.NEGATIVE_INFINITY ? box.height : Math.max(box.height, bottom);
        return new float[] {box.x + ownRight, box.y + ownBottom};
    }
}
