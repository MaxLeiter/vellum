package dev.vellum.engine.paint;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.layout.Box;
import dev.vellum.engine.layout.Fragment;
import dev.vellum.engine.layout.LineBox;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Position;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Walks a box tree in paint order and reports each step to a {@link Visitor}. Painting and hit testing both use
 * it, so what you click is what you see: the painter draws each step, the hit tester keeps the last step under the
 * point (the topmost one).
 *
 * <p>The order is CSS 2 Appendix E, simplified as in DESIGN §7. For each stacking context: the root's background
 * and border; descendants with negative z-index; the backgrounds and borders of in-flow, non-positioned block
 * descendants in tree order; then their inline content (inline box decorations before the text they contain, text
 * runs, and atomic inlines painted whole in line order); positioned descendants with {@code z-index: auto | 0} in
 * tree order; positive z-index. Positioned boxes without a stacking context and atomic inlines are painted whole,
 * as if they made one, but their positioned descendants belong to the enclosing context.
 *
 * <p>Coordinates passed to the visitor are in its current space: the viewport until a transformed context is
 * entered, then that box's border box with its origin at 0,0. Descendants painted out of tree order (the z-index
 * lists) get the clips and scroll offsets of the ancestors between them and their stacking context, except those
 * an absolutely or fixed positioned box escapes (ancestors below its containing block).
 *
 * <p>Instances keep reusable scratch lists and are not reentrant: use one per visitor.
 */
final class StackingOrder {
    /** Receives the walk. Every {@code pushClip} or {@code enterContext} returning true is matched by a pop. */
    interface Visitor {
        /**
         * Enters a box with opacity below 1 and/or a transform. {@code transform} (null for none) maps the box's
         * border-box space, origin at 0,0, to the current space translated to ({@code x}, {@code y}); it is scratch,
         * valid only during the call. Return false to skip the box and its descendants.
         */
        boolean enterContext(Box box, ComputedStyle style, float x, float y, Affine transform);

        void exitContext();

        /** Clips what follows to a rectangle; return false when nothing inside can matter (it is skipped). */
        boolean pushClip(float x, float y, float width, float height);

        void popClip();

        /** The box's own painting (shadows, background, border, form control, replaced content) at border-box origin (x, y). */
        void box(Box box, ComputedStyle style, float x, float y);

        /** An inline box fragment of {@code block}; ({@code x}, {@code y}) is the origin of the fragments' space. */
        void inlineBox(Box block, Fragment.InlineBox fragment, float x, float y);

        /** A text run of {@code block}; ({@code x}, {@code y}) is the origin of the fragments' space. */
        void textRun(Box block, Fragment.TextRun run, float x, float y);

        /** After the box and everything inside it: scrollbars and outline. */
        void after(Box box, ComputedStyle style, float x, float y);
    }

    /** Stable sort by z-index: negatives first, then auto and 0 in tree order, then positives. */
    private static final Comparator<Box> Z_ORDER = Comparator.comparingInt(StackingOrder::zIndex);

    private final Affine transform = new Affine();
    /** One z-ordered list per stacking-context nesting level, reused across walks. */
    private final List<ArrayList<Box>> lists = new ArrayList<>();
    private int depth;
    private Box[] chain = new Box[16];

    /** Walks the tree rooted at {@code root}, which is a stacking context whatever its style. */
    void walk(Box root, Visitor visitor) {
        depth = 0;
        layer(root, styleOf(root), root.x, root.y, true, visitor);
    }

    // ---- Styles ----

    /**
     * The style to paint a box with. Element boxes use the element's live style, so paint-only animations
     * (opacity, transform, colours) show without a relayout; anonymous and pseudo boxes keep their layout style.
     */
    static ComputedStyle styleOf(Box box) {
        if (box.kind == Box.Kind.BLOCK || box.kind == Box.Kind.REPLACED) {
            ComputedStyle live = box.element == null ? null : box.element.style;
            if (live != null) return live;
        }
        return box.style;
    }

    /** The live style of an inline box fragment (a pseudo-element's fragment keeps its own). */
    static ComputedStyle styleOf(Fragment.InlineBox f) {
        Element e = f.element();
        if (e == null || e.style == null || f.style() == e.beforeStyle || f.style() == e.afterStyle) return f.style();
        return e.style;
    }

    /** The live style of a text run (generated content keeps its own). */
    static ComputedStyle styleOf(Fragment.TextRun run) {
        Element source = run.styleSource();
        return run.node() == null || source == null || source.style == null ? run.style() : source.style;
    }

    /** Positioned boxes and stacking contexts are painted from their stacking context's z-ordered list. */
    private static boolean isLayer(ComputedStyle s) {
        return s.position.isPositioned() || s.createsStackingContext();
    }

    private static int zIndex(Box box) {
        ComputedStyle s = styleOf(box);
        boolean applies = !s.zIndexAuto && (s.position.isPositioned() || s.isFlexOrGridItemHint);
        return applies ? s.zIndex : 0;
    }

    static boolean clips(Box box, ComputedStyle s) {
        return box.kind != Box.Kind.ANONYMOUS && (s.overflowX.clips() || s.overflowY.clips());
    }

    // ---- Walk ----

    /** Paints a box whole: a stacking context, or a positioned box or atomic inline treated like one. */
    private void layer(Box box, ComputedStyle s, float x, float y, boolean context, Visitor v) {
        boolean effects = s.opacity < 1 || s.hasTransform();
        if (effects) {
            Affine m = s.hasTransform() ? resolveTransform(box, s) : null;
            if (!v.enterContext(box, s, x, y, m)) return;
            if (m != null) {
                x = 0;
                y = 0;
            }
        }
        v.box(box, s, x, y);
        if (context) {
            ArrayList<Box> items = acquireList();
            collect(box, items);
            items.sort(Z_ORDER);
            int i = 0, n = items.size();
            for (; i < n && zIndex(items.get(i)) < 0; i++) item(box, items.get(i), x, y, v);
            blocks(box, x, y, v);
            inlines(box, x, y, v);
            for (; i < n; i++) item(box, items.get(i), x, y, v);
            items.clear();
            depth--;
        } else {
            blocks(box, x, y, v);
            inlines(box, x, y, v);
        }
        v.after(box, s, x, y);
        if (effects) v.exitContext();
    }

    /** The context's positioned and stacking-context descendants, not crossing nested contexts, in tree order. */
    private static void collect(Box parent, List<Box> out) {
        for (Box child : parent.children) {
            ComputedStyle s = styleOf(child);
            if (isLayer(s)) {
                out.add(child);
                if (s.createsStackingContext()) continue;
            }
            collect(child, out);
        }
    }

    private static boolean inFlow(Box box) {
        return !box.atomicInline && !isLayer(styleOf(box));
    }

    /** Backgrounds and borders of the in-flow block descendants of {@code box} at (x, y), in tree order. */
    private void blocks(Box box, float x, float y, Visitor v) {
        if (box.children.isEmpty() || !enterContent(box, x, y, v)) return;
        float cx = x - box.scrollLeft(), cy = y - box.scrollTop();
        for (Box child : box.children) {
            if (!inFlow(child)) continue;
            v.box(child, styleOf(child), cx + child.x, cy + child.y);
            blocks(child, cx + child.x, cy + child.y, v);
        }
        exitContent(box, v);
    }

    /** Line fragments of {@code box} and of its in-flow block descendants, each followed by its after-steps. */
    private void inlines(Box box, float x, float y, Visitor v) {
        if ((box.children.isEmpty() && box.lines.isEmpty()) || !enterContent(box, x, y, v)) return;
        float cx = x - box.scrollLeft(), cy = y - box.scrollTop();
        for (LineBox line : box.lines) {
            for (Fragment f : line.fragments) {
                switch (f) {
                    case Fragment.InlineBox ib -> v.inlineBox(box, ib, cx, cy);
                    case Fragment.TextRun run -> v.textRun(box, run, cx, cy);
                    case Fragment.Atomic atomic -> {
                        Box b = atomic.box();
                        ComputedStyle s = styleOf(b);
                        if (!isLayer(s)) layer(b, s, cx + b.x, cy + b.y, false, v);
                    }
                }
            }
        }
        for (Box child : box.children) {
            if (!inFlow(child)) continue;
            inlines(child, cx + child.x, cy + child.y, v);
            v.after(child, styleOf(child), cx + child.x, cy + child.y);
        }
        exitContent(box, v);
    }

    /** Clips to the padding box when {@code box} clips its content; false when the content can be skipped. */
    private static boolean enterContent(Box box, float x, float y, Visitor v) {
        return !clips(box, styleOf(box)) || clipToPaddingBox(box, x, y, v);
    }

    private static boolean clipToPaddingBox(Box box, float x, float y, Visitor v) {
        return v.pushClip(x + box.borderLeft, y + box.borderTop, box.paddingBoxWidth(), box.paddingBoxHeight());
    }

    private static void exitContent(Box box, Visitor v) {
        if (clips(box, styleOf(box))) v.popClip();
    }

    /**
     * Paints {@code item}, a descendant of the context {@code root} (at x, y), from the context's z-ordered list:
     * applies the clips and scroll offsets of the ancestors in between, then paints it whole.
     */
    private void item(Box root, Box item, float x, float y, Visitor v) {
        int n = 0;
        for (Box p = item.parent; p != root; p = p.parent) {
            if (n == chain.length) chain = Arrays.copyOf(chain, n * 2);
            chain[n++] = p;
        }
        // Path from the root down: root, chain[n-1], ..., chain[0]. Clips and scrolling apply down to `last`.
        ComputedStyle s = styleOf(item);
        int last = n;
        if (s.position.isOutOfFlow()) {
            Box cb = containingBlock(item, s);
            last = -1;
            if (cb == root) last = 0;
            for (int i = 0; i < n; i++) if (chain[i] == cb) last = n - i;
        }
        int pushed = 0;
        boolean visible = true;
        for (int i = 0; i <= n; i++) {
            Box a = i == 0 ? root : chain[n - i];
            if (i <= last) {
                if (clips(a, styleOf(a))) {
                    if (!clipToPaddingBox(a, x, y, v)) {
                        visible = false;
                        break;
                    }
                    pushed++;
                }
                x -= a.scrollLeft();
                y -= a.scrollTop();
            }
            Box next = i == n ? item : chain[n - i - 1];
            x += next.x;
            y += next.y;
        }
        if (visible) layer(item, s, x, y, s.createsStackingContext(), v);
        for (; pushed > 0; pushed--) v.popClip();
    }

    /** The box an absolutely or fixed positioned box is placed against, or null for the viewport. */
    private static Box containingBlock(Box item, ComputedStyle s) {
        for (Box a = item.parent; a != null; a = a.parent) {
            ComputedStyle as = styleOf(a);
            if (as.hasTransform() || (s.position == Position.ABSOLUTE && as.position.isPositioned())) return a;
        }
        return null;
    }

    private ArrayList<Box> acquireList() {
        if (depth == lists.size()) lists.add(new ArrayList<>());
        ArrayList<Box> list = lists.get(depth++);
        list.clear(); // in case an earlier walk was interrupted by an exception
        return list;
    }

    /** {@code translate(origin) · transform · translate(-origin)}, percentages against the border box. */
    private Affine resolveTransform(Box box, ComputedStyle s) {
        float ox = s.transformOriginX.resolve(box.width), oy = s.transformOriginY.resolve(box.height);
        return transform.identity().translate(ox, oy).concat(s.transform, box.width, box.height).translate(-ox, -oy);
    }
}
