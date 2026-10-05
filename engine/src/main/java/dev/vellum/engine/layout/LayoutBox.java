package dev.vellum.engine.layout;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.style.ComputedStyle;

/**
 * The layout engine's own box: a {@link Box} plus the state the algorithms need during a pass. Everything outside
 * this package sees plain {@link Box}es. The tree is rebuilt on every pass, so the caches here never go stale.
 */
final class LayoutBox extends Box {
    /** How the box lays out its in-flow content. */
    enum Context {
        /** Block flow: block-level children stacked vertically, or an inline formatting context ({@link #inline}). */
        FLOW,
        FLEX,
        GRID,
        /** No laid-out content: replaced elements and form controls. */
        LEAF
    }

    final Context context;
    /** The inline-level content when this box establishes an inline formatting context, else null. */
    InlineContent inline;
    /**
     * Establishes an independent formatting context (flex/grid item, inline-block, scroll container, abspos, root):
     * its margins never collapse with its children's.
     */
    boolean independent;

    /** Natural (intrinsic) content size of a replaced element; NaN when it has none. */
    float naturalWidth = Float.NaN, naturalHeight = Float.NaN;

    /**
     * An area of {@code ref}'s border box where an out-of-flow box is placed, and how the box aligns in it in each
     * axis (0 start, 0.5 centre, 1 end).
     */
    record Area(LayoutBox ref, float x, float y, float width, float height, float alignX, float alignY) {}

    /**
     * The static position of an out-of-flow box: where it would have been in flow. Block and inline flow give an
     * empty area; a flex or grid container gives its content box, aligned as if the box were its only item. Null
     * until the parent's layout records it.
     */
    Area staticPosition;

    /**
     * For an out-of-flow child of a grid container: its grid area (auto lines at the padding edges) and self
     * alignment, which replace the containing block and static position when the grid container is the containing
     * block (CSS Grid §9.4). Null otherwise.
     */
    Area gridArea;

    /** Offset of the last line's baseline from the border-box top, or NaN (inline-block baseline alignment). */
    float lastBaseline = Float.NaN;

    /**
     * Used min-height and max-height (border-box, NaN when none) of the layout in progress, set by
     * {@link LayoutPass} for formatting contexts whose content sizing depends on them (flex, grid).
     */
    float minHeight = Float.NaN, maxHeight = Float.NaN;

    /** Content-based border-box widths (ignoring the box's own width and min/max), NaN until measured. */
    float minContent = Float.NaN, maxContent = Float.NaN;

    private static final int CACHE_SIZE = 4, KEY = 5;
    private float[] cacheKeys;
    private LayoutResult[] cacheValues;
    private int cacheNext;

    LayoutBox(Kind kind, Element element, ComputedStyle style, Context context) {
        super(kind, element, style);
        this.context = context;
    }

    /** A measurement made earlier in this pass with the same inputs, or null. */
    LayoutResult cached(float width, float height, boolean heightDefinite, float cbWidth, float cbHeight) {
        if (cacheKeys == null) return null;
        for (int i = 0; i < CACHE_SIZE; i++) {
            LayoutResult r = cacheValues[i];
            int k = i * KEY;
            if (r != null && same(cacheKeys[k], width) && same(cacheKeys[k + 1], height)
                    && same(cacheKeys[k + 2], cbWidth) && same(cacheKeys[k + 3], cbHeight)
                    && cacheKeys[k + 4] == (heightDefinite ? 1 : 0)) return r;
        }
        return null;
    }

    void cache(float width, float height, boolean heightDefinite, float cbWidth, float cbHeight, LayoutResult result) {
        if (cacheKeys == null) {
            cacheKeys = new float[CACHE_SIZE * KEY];
            cacheValues = new LayoutResult[CACHE_SIZE];
        }
        int k = cacheNext * KEY;
        cacheKeys[k] = width;
        cacheKeys[k + 1] = height;
        cacheKeys[k + 2] = cbWidth;
        cacheKeys[k + 3] = cbHeight;
        cacheKeys[k + 4] = heightDefinite ? 1 : 0;
        cacheValues[cacheNext] = result;
        cacheNext = (cacheNext + 1) % CACHE_SIZE;
    }

    private static boolean same(float a, float b) {
        return Float.compare(a, b) == 0;
    }

    boolean isReplaced() { return kind == Kind.REPLACED; }
}
