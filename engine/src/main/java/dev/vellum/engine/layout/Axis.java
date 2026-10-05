package dev.vellum.engine.layout;

import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Length;

/**
 * A physical axis, with accessors for the axis-dependent style properties and box fields. Flex (main/cross) and grid
 * (columns/rows) are written once against this abstraction instead of once per direction.
 */
enum Axis {
    HORIZONTAL, VERTICAL;

    Axis other() { return this == HORIZONTAL ? VERTICAL : HORIZONTAL; }

    boolean isHorizontal() { return this == HORIZONTAL; }

    // ---- Style ----

    Length size(ComputedStyle s) { return this == HORIZONTAL ? s.width : s.height; }
    Length minSize(ComputedStyle s) { return this == HORIZONTAL ? s.minWidth : s.minHeight; }
    Length maxSize(ComputedStyle s) { return this == HORIZONTAL ? s.maxWidth : s.maxHeight; }
    Length marginStart(ComputedStyle s) { return this == HORIZONTAL ? s.marginLeft : s.marginTop; }
    Length marginEnd(ComputedStyle s) { return this == HORIZONTAL ? s.marginRight : s.marginBottom; }
    Length gap(ComputedStyle s) { return this == HORIZONTAL ? s.columnGap : s.rowGap; }

    // ---- Box geometry ----

    float size(Box b) { return this == HORIZONTAL ? b.width : b.height; }

    float marginStart(Box b) { return this == HORIZONTAL ? b.marginLeft : b.marginTop; }
    float marginEnd(Box b) { return this == HORIZONTAL ? b.marginRight : b.marginBottom; }
    float margins(Box b) { return marginStart(b) + marginEnd(b); }

    void setMargins(Box b, float start, float end) {
        if (this == HORIZONTAL) {
            b.marginLeft = start;
            b.marginRight = end;
        } else {
            b.marginTop = start;
            b.marginBottom = end;
        }
    }

    /** Border plus padding on the start side: the offset of the content box. */
    float contentStart(Box b) { return this == HORIZONTAL ? b.contentX() : b.contentY(); }

    /** Border plus padding on both sides. */
    float paddingBorder(Box b) {
        return this == HORIZONTAL ? BoxModel.paddingBorderWidth(b) : BoxModel.paddingBorderHeight(b);
    }

    /** Picks the horizontal or vertical value of a pair. */
    float pick(float horizontal, float vertical) { return this == HORIZONTAL ? horizontal : vertical; }
}
