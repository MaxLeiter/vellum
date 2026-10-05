package dev.vellum.engine.layout;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Position;

/**
 * Positioning (CSS 2.1 §9.3, §10.3.7, §10.6.4): relative offsets, and the absolute/fixed pass that runs after
 * in-flow layout. Out-of-flow boxes stay children of their tree parent; their geometry is computed against their
 * containing block (the padding box of the nearest positioned or transformed ancestor, else the viewport) and
 * converted into the parent's border-box space. Coordinates ignore scroll offsets, like all box coordinates.
 */
final class PositionedLayout {
    private final LayoutPass pass;

    PositionedLayout(LayoutPass pass) {
        this.pass = pass;
    }

    // ---- Relative positioning ----

    /** The {x, y} offset of a relatively positioned (or sticky, treated as relative) box; zero otherwise. */
    static float[] relativeOffset(ComputedStyle s, float cbWidth, float cbHeight) {
        if (s.position != Position.RELATIVE && s.position != Position.STICKY) return new float[2];
        float left = s.left.resolve(cbWidth, Float.NaN), right = s.right.resolve(cbWidth, Float.NaN);
        float top = s.top.resolve(cbHeight, Float.NaN), bottom = s.bottom.resolve(cbHeight, Float.NaN);
        float dx = !Float.isNaN(left) ? left : Float.isNaN(right) ? 0 : -right;
        float dy = !Float.isNaN(top) ? top : Float.isNaN(bottom) ? 0 : -bottom;
        return new float[] {dx, dy};
    }

    /** Shifts an in-flow box that its formatting context just placed by its relative offset. */
    static void applyRelativeOffset(Box box, float cbWidth, float cbHeight) {
        if (box.style.position != Position.RELATIVE && box.style.position != Position.STICKY) return;
        float[] offset = relativeOffset(box.style, cbWidth, cbHeight);
        box.x += offset[0];
        box.y += offset[1];
    }

    /** Records where an out-of-flow box would have been in block or inline flow, in {@code ref}'s border-box space. */
    static void setStaticPosition(LayoutBox box, LayoutBox ref, float x, float y) {
        box.staticPosition = new LayoutBox.Area(ref, x, y, 0, 0, 0, 0);
    }

    // ---- Absolute and fixed positioning ----

    /** Lays out every out-of-flow box under {@code box}, outer ones first so inner ones see final containing blocks. */
    void layoutOutOfFlow(Box box) {
        for (Box child : box.children) {
            if (child instanceof LayoutBox b && b.outOfFlow) place(b);
            layoutOutOfFlow(child);
        }
    }

    private void place(LayoutBox box) {
        ComputedStyle s = box.style;
        // The containing block's padding box, in the parent's coordinate space; a grid area replaces it and the
        // static position.
        float[] cb = containingBlock(box);
        LayoutBox.Area area = cb == null ? box.gridArea : box.staticPosition;
        if (cb == null) cb = rect(box, area);
        float cbX = cb[0], cbY = cb[1], cbW = cb[2], cbH = cb[3];
        BoxModel.resolveEdges(box, cbW);
        float left = s.left.resolve(cbW, Float.NaN), right = s.right.resolve(cbW, Float.NaN);
        float top = s.top.resolve(cbH, Float.NaN), bottom = s.bottom.resolve(cbH, Float.NaN);
        boolean replaced = box.isReplaced();
        float availableWidth = Math.max(0, cbW - BoxModel.or(left, 0) - BoxModel.or(right, 0)
                - box.marginLeft - box.marginRight);
        // Auto sizes stretch between two insets (non-replaced boxes), else shrink to fit; a ratio turns a size
        // from one axis into the other, and wins over stretching the height.
        float insetHeight = Float.NaN;
        if (s.height.isAuto() && !replaced && !Float.isNaN(top) && !Float.isNaN(bottom)) {
            insetHeight = pass.clampHeight(box, cbH - top - bottom - box.marginTop - box.marginBottom, cbW, cbH);
        }
        float width;
        if (s.width.isAuto() && !replaced && !Float.isNaN(left) && !Float.isNaN(right)) {
            width = pass.clampWidth(box, availableWidth, cbW, cbH, availableWidth);
        } else if (s.width.isAuto() && !Float.isNaN(insetHeight) && !Float.isNaN(s.aspectRatio)) {
            width = pass.clampWidth(box, BoxModel.transfer(box, insetHeight, Axis.VERTICAL), cbW, cbH, availableWidth);
        } else {
            width = pass.usedWidth(box, availableWidth, cbW, cbH, false);
        }
        pass.layout(box, width, Float.isNaN(s.aspectRatio) ? insetHeight : Float.NaN, cbW, cbH);

        // With both insets auto the box aligns in its static area (the containing block's origin if none).
        float[] statics = area == null ? new float[] {cbX, cbY} : rect(box, area);
        statics[0] += area == null ? 0 : area.alignX() * (area.width() - box.marginBoxWidth());
        statics[1] += area == null ? 0 : area.alignY() * (area.height() - box.marginBoxHeight());
        box.x = cbX + offset(Axis.HORIZONTAL, box, left, right, cbW, statics[0] - cbX);
        box.y = cbY + offset(Axis.VERTICAL, box, top, bottom, cbH, statics[1] - cbY);
    }

    /** An area as {x, y, width, height} in the box's parent's space. */
    private static float[] rect(LayoutBox box, LayoutBox.Area area) {
        float[] ref = origin(area.ref()), parent = origin(box.parent);
        return new float[] {ref[0] - parent[0] + area.x(), ref[1] - parent[1] + area.y(), area.width(), area.height()};
    }

    /**
     * The border-box offset of the box from its containing block's start in one axis, from its insets (NaN when
     * auto), size and margins; with both insets auto it goes to its static position. Auto margins share the free
     * space when both insets are set (centring), or zero when it is negative.
     */
    private static float offset(Axis axis, LayoutBox box, float start, float end, float cbSize, float staticOffset) {
        ComputedStyle s = box.style;
        float size = axis.size(box);
        float marginStart = axis.marginStart(box), marginEnd = axis.marginEnd(box);
        boolean autoStart = axis.marginStart(s).isAuto(), autoEnd = axis.marginEnd(s).isAuto();
        if (!Float.isNaN(start) && !Float.isNaN(end) && (autoStart || autoEnd)) {
            float free = cbSize - start - end - size - marginStart - marginEnd;
            if (autoStart && autoEnd) {
                marginStart = marginEnd = Math.max(0, free) / 2;
            } else if (autoStart) {
                marginStart = free;
            } else {
                marginEnd = free;
            }
            axis.setMargins(box, marginStart, marginEnd);
        }
        if (!Float.isNaN(start)) return start + marginStart;
        if (!Float.isNaN(end)) return cbSize - end - marginEnd - size;
        return staticOffset + marginStart;
    }

    /**
     * The padding box of the containing block of an out-of-flow box as {x, y, width, height} in its parent's space:
     * the nearest ancestor that is positioned (absolute only) or transformed, else the viewport. An inline element
     * as containing block contributes the bounds of its fragments. Null when it is the box's grid area.
     */
    private float[] containingBlock(LayoutBox box) {
        boolean fixed = box.style.position == Position.FIXED;
        // A pseudo-element's parent is its host element itself.
        Element e = box.kind == Box.Kind.PSEUDO ? box.element : box.element.parentElement();
        for (; e != null; e = e.parentElement()) {
            Box cb = e.box;
            if (cb == null || e.style == null) continue;
            if (e.style.hasTransform() || (!fixed && e.style.position.isPositioned())) {
                if (cb == box.parent && box.gridArea != null) return null;
                float[] o = origin(cb), p = origin(box.parent);
                float x = o[0] - p[0], y = o[1] - p[1];
                if (cb.kind == Box.Kind.INLINE) return new float[] {x, y, cb.width, cb.height};
                return new float[] {x + cb.borderLeft, y + cb.borderTop, cb.paddingBoxWidth(), cb.paddingBoxHeight()};
            }
        }
        float[] p = origin(box.parent);
        return new float[] {-p[0], -p[1], pass.viewportWidth, pass.viewportHeight};
    }

    /** The border-box origin of a box relative to the root's coordinate space, ignoring scrolling. */
    private static float[] origin(Box box) {
        float x = 0, y = 0;
        for (Box b = box; b != null; b = b.parent) {
            x += b.x;
            y += b.y;
        }
        return new float[] {x, y};
    }
}
