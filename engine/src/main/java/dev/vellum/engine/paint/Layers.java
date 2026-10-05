package dev.vellum.engine.paint;

import dev.vellum.engine.layout.Box;
import dev.vellum.engine.style.ComputedStyle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The z-ordered list each stacking context paints its positioned descendants and nested contexts from (CSS 2
 * Appendix E), built on first use and kept until the box tree is relaid out or a style change moves paint order
 * ({@code Document.stackingVersion()}). Painting and hit testing share it, so neither re-sorts the tree every frame or
 * on every pointer move.
 */
final class Layers {
    /** Stable sort by z-index: negatives first, then auto and 0 in tree order, then positives. */
    private static final Comparator<Box> Z_ORDER = Comparator.comparingInt(StackingOrder::zIndex);
    private static final Box[] NONE = {};

    private final Map<Box, Box[]> lists = new IdentityHashMap<>();
    private Box root;
    private int layoutVersion, stackingVersion;

    /** Drops the lists unless they were built for this tree, layout and stacking. */
    void validate(Box root, int layoutVersion, int stackingVersion) {
        if (root == this.root && layoutVersion == this.layoutVersion && stackingVersion == this.stackingVersion) return;
        lists.clear();
        this.root = root;
        this.layoutVersion = layoutVersion;
        this.stackingVersion = stackingVersion;
    }

    /** The context's positioned and stacking-context descendants, not crossing nested contexts, in paint order. */
    Box[] of(Box context) {
        Box[] list = lists.get(context);
        if (list == null) {
            List<Box> found = new ArrayList<>();
            collect(context, found);
            found.sort(Z_ORDER);
            list = found.isEmpty() ? NONE : found.toArray(NONE);
            lists.put(context, list);
        }
        return list;
    }

    private static void collect(Box parent, List<Box> out) {
        for (Box child : parent.children) {
            ComputedStyle s = StackingOrder.styleOf(child);
            if (StackingOrder.isLayer(s)) {
                out.add(child);
                if (s.createsStackingContext()) continue;
            }
            collect(child, out);
        }
    }
}
