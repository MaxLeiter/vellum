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
     * A run of text in one style. {@code (x, y)} is the top-left of the glyph box; text is drawn there, in the parts
     * of {@code spaced} when letter- or word-spacing apply (null otherwise). {@code text} is after white-space
     * processing and {@code text-transform}; {@code source} maps it back to the text node's data: {@code source[i]} is
     * where char {@code i} came from, and its last entry the end of the run (null when there is no node: generated
     * content).
     */
    record TextRun(Text node, Element styleSource, ComputedStyle style, String text, int[] source, SpacedText spaced,
                   float x, float y, float width, float height) implements Fragment {
        /**
         * The offset in the node's data of the caret before char {@code i} of the text ({@code text.length()} for the
         * end); chars that came from no data (an ellipsis) map to the run's end. {@code i} itself without a map.
         */
        public int sourceIndex(int i) {
            return source == null ? i : source[Math.min(i, source.length - 1)];
        }
    }

    /**
     * The part of an inline element (span, a, b...) or inline ::before/::after on one line: its {@code box}'s
     * background, border and padding (edges as layout resolved them), painted before the content it wraps, which runs
     * to index {@code end} (exclusive) of the line's fragments. {@code first}/{@code last} say whether the start/end
     * edges (left/right border and padding) are on this line.
     */
    record InlineBox(Box box, float x, float y, float width, float height, boolean first, boolean last, int end)
            implements Fragment {}

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
