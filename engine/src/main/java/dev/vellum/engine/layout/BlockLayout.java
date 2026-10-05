package dev.vellum.engine.layout;

/**
 * Block flow (CSS 2.1 §9.4.1, §10.3.3, §8.3.1): block-level children stacked vertically with their widths from the
 * containing block, auto margins centring, and vertical margin collapsing between siblings, between a box and its
 * first/last child when nothing separates them, and through empty boxes. A container whose children are all
 * inline-level delegates to {@link InlineLayout}.
 */
final class BlockLayout implements FormattingContext {
    private final LayoutPass pass;

    BlockLayout(LayoutPass pass) {
        this.pass = pass;
    }

    @Override
    public LayoutResult layoutContent(LayoutBox box, float contentWidth, float contentHeight, float percentHeight,
                                      boolean measure) {
        if (box.inline != null) return pass.inline.layout(box, contentWidth, percentHeight, measure);

        // A box's margins adjoin its first/last child's when no border, padding or new formatting context separates
        // them (and, at the bottom, when its height is auto).
        boolean collapseTop = !box.independent && box.borderTop == 0 && box.paddingTop == 0;
        boolean collapseBottom = !box.independent && box.borderBottom == 0 && box.paddingBottom == 0
                && Float.isNaN(contentHeight);
        float x = box.contentX();
        float cursor = box.contentY();
        MarginSet pending = MarginSet.EMPTY;
        MarginSet first = MarginSet.EMPTY;
        boolean atStart = true;
        float firstBaseline = Float.NaN, lastBaseline = Float.NaN;

        for (Box c : box.children) {
            LayoutBox child = (LayoutBox) c;
            if (child.outOfFlow) {
                float staticY = atStart && collapseTop ? cursor : cursor + pending.resolve();
                PositionedLayout.setStaticPosition(child, box, x, staticY);
                continue;
            }
            BoxModel.resolveEdges(child, contentWidth);
            float width = pass.usedWidth(child, contentWidth - child.marginLeft - child.marginRight, contentWidth,
                    percentHeight, true);
            BoxModel.resolveAutoMargins(child, Axis.HORIZONTAL,
                    contentWidth - width - child.marginLeft - child.marginRight);
            LayoutResult r = measure ? pass.measure(child, width, Float.NaN, contentWidth, percentHeight)
                    : pass.layout(child, width, Float.NaN, contentWidth, percentHeight);
            MarginSet childTop = r.top().with(child.marginTop);
            MarginSet childBottom = r.bottom().with(child.marginBottom);

            // Margins before the first in-flow content escape through the top of a collapsing parent.
            boolean escapes = atStart && collapseTop;
            float y = escapes ? cursor : cursor + pending.with(childTop).resolve();
            if (escapes) first = first.with(childTop);
            if (r.collapsesThrough()) {
                MarginSet through = childTop.with(childBottom);
                if (escapes) first = first.with(through);
                pending = pending.with(through);
            } else {
                atStart = false;
                cursor = y + r.height();
                pending = childBottom;
            }
            child.x = x + child.marginLeft;
            child.y = y;
            if (!measure) PositionedLayout.applyRelativeOffset(child, contentWidth, percentHeight);
            if (Float.isNaN(firstBaseline) && !Float.isNaN(r.firstBaseline())) firstBaseline = y + r.firstBaseline();
            if (!Float.isNaN(r.lastBaseline())) lastBaseline = y + r.lastBaseline();
        }

        float end = collapseBottom ? cursor : cursor + pending.resolve();
        // align-content moves the in-flow content as one block within a definite height (CSS Box Alignment §5.4).
        float shift = Float.isNaN(contentHeight) ? 0
                : Alignment.distribute(box.style.alignContent, contentHeight - (end - box.contentY()), 1)[0];
        if (shift != 0) {
            for (Box c : box.children) if (!((LayoutBox) c).outOfFlow) c.y += shift;
            firstBaseline += shift;
            lastBaseline += shift;
        }
        // An empty box with no vertical border or padding lets margins collapse through it (if its height is 0).
        boolean collapsesThrough = atStart && collapseTop && !box.independent && box.borderBottom == 0
                && box.paddingBottom == 0;
        return new LayoutResult(end - box.contentY(), firstBaseline, lastBaseline,
                collapseTop ? first : MarginSet.EMPTY, collapseBottom ? pending : MarginSet.EMPTY, collapsesThrough);
    }

    @Override
    public float intrinsicContentWidth(LayoutBox box, boolean max) {
        if (box.inline != null) return pass.inline.intrinsicWidth(box, max);
        float width = 0;
        for (Box c : box.children) {
            LayoutBox child = (LayoutBox) c;
            if (!child.outOfFlow) width = Math.max(width, pass.contribution(child, max));
        }
        return width;
    }
}
