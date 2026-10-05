package dev.vellum.engine.layout;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.input.Controls;

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
    private TextMeasure text;
    private Box root;

    public LayoutEngine(Document document) {
        this.document = document;
    }

    /** The root box (for the html element), or null before the first layout or when the root is not rendered. */
    public Box root() {
        return root;
    }

    /** The document's text measurement, shared with painting, hit testing and form controls. */
    public TextMeasure textMeasure() {
        if (text == null) text = new TextMeasure(document.host().fonts());
        return text;
    }

    /** Lays out the whole document against the current viewport. */
    public void layout() {
        Element html = document.documentElement();
        LayoutBox box = html == null ? null : new BoxTreeBuilder().build(html);
        root = box;
        if (box == null) return;

        float vw = document.viewportWidth(), vh = document.viewportHeight();
        LayoutPass pass = new LayoutPass(textMeasure(), vw, vh);
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
     * boxes and line fragments from the padding-box origin, plus the end padding, and at least the padding box. An
     * absolutely or fixed positioned box extends its containing block's content ({@link Box#contentParent()}), not
     * the scrollers it escapes, and nothing for the viewport. Then re-clamps the scroll offsets of scroll containers
     * to their new range (which fires {@code scroll} if that moves them).
     */
    private static void overflow(LayoutBox box) {
        for (Box c : box.children) {
            LayoutBox child = (LayoutBox) c;
            overflow(child);
            float right = child.x + child.overflowRight, bottom = child.y + child.overflowBottom;
            if (!child.outOfFlow) {
                box.extendOverflow(right, bottom);
            } else if (child.contentParent() instanceof LayoutBox holder) {
                // Into the holder's space: it is the parent or a further ancestor.
                for (Box p = box; p != holder; p = p.parent) {
                    right += p.x;
                    bottom += p.y;
                }
                holder.extendOverflow(right, bottom);
            }
        }
        for (LineBox line : box.lines) {
            for (Fragment f : line.fragments) box.extendOverflow(f.x() + f.width(), f.y() + f.height());
        }
        float right = box.overflowRight, bottom = box.overflowBottom;
        boolean content = right != Float.NEGATIVE_INFINITY;
        box.scrollWidth = Math.max(box.paddingBoxWidth(), content ? right - box.borderLeft + box.paddingRight : 0);
        box.scrollHeight = Math.max(box.paddingBoxHeight(), content ? bottom - box.borderTop + box.paddingBottom : 0);
        if (box.context == LayoutBox.Context.LEAF) Controls.overflow(box);
        if (box.isScrollContainer()) box.element.clampScroll();
        // What the box contributes to its parent's extent, in its own space: only its border box when it clips.
        box.overflowRight = box.style.overflowX.clips() || !content ? box.width : Math.max(box.width, right);
        box.overflowBottom = box.style.overflowY.clips() || !content ? box.height : Math.max(box.height, bottom);
    }
}
