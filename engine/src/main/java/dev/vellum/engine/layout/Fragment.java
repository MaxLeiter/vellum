package dev.vellum.engine.layout;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Text;
import dev.vellum.engine.style.ComputedStyle;

/**
 * A piece of a line box. Coordinates are relative to the owning block box's border-box origin.
 */
public sealed interface Fragment {
    float x();
    float y();
    float width();
    float height();

    /**
     * A run of text in one style. {@code (x, y)} is the top-left of the glyph box; text is drawn there.
     * {@code start}/{@code end} index into the source text node's data (after whitespace processing they may not
     * map exactly; they are used for caret placement and selection). {@code node} is null for generated content.
     */
    record TextRun(Text node, Element styleSource, ComputedStyle style, String text, int start, int end,
                   float x, float y, float width, float height) implements Fragment {}

    /**
     * The part of an inline element (span, a, b...) on one line, for painting its background, border and padding.
     * {@code first}/{@code last} say whether the start/end edges (left/right border and padding) are on this line.
     * Painted before the text it contains.
     */
    record InlineBox(Element element, ComputedStyle style, float x, float y, float width, float height,
                     boolean first, boolean last) implements Fragment {}

    /**
     * An atomic inline (inline-block, inline-flex, inline-grid, replaced inline). The box is also in the block's
     * {@code children} with {@code atomicInline} set; its x/y are authoritative.
     */
    record Atomic(Box box) implements Fragment {
        public float x() { return box.x; }
        public float y() { return box.y; }
        public float width() { return box.width; }
        public float height() { return box.height; }
    }
}
