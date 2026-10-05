package dev.vellum.engine.paint;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.layout.Box;
import dev.vellum.engine.layout.Fragment;
import dev.vellum.engine.layout.LineBox;
import dev.vellum.engine.style.Colors;
import dev.vellum.engine.style.ComputedStyle;

import java.util.Arrays;
import java.util.List;

/**
 * Walks a box tree in paint order and reports each step to a {@link Visitor}. Painting and hit testing both use
 * it, so what you click is what you see: the painter draws each step, the hit tester keeps the last step under the
 * point (the topmost one).
 *
 * <p>The order is CSS 2 Appendix E, simplified. For each stacking context: the root's background
 * and border; descendants with negative z-index; the backgrounds and borders of in-flow, non-positioned block
 * descendants in tree order; then their inline content (inline box decorations before the text they contain, text
 * runs, and atomic inlines painted whole in line order); positioned descendants with {@code z-index: auto | 0} in
 * tree order; positive z-index. Positioned boxes without a stacking context and atomic inlines are painted whole,
 * as if they made one, but their positioned descendants belong to the enclosing context.
 *
 * <p>Coordinates passed to the visitor are in its current space: the viewport until a transformed context is
 * entered, then that box's border box with its origin at 0,0. Descendants painted out of tree order (the z-index
 * lists, kept in {@link Layers}) are placed by {@link Coordinates} and get the clips of the ancestors between them
 * and their stacking context whose content they are in ({@link Box#contentParent()}): an absolutely or fixed
 * positioned box escapes the clips and scroll offsets of the ancestors below its containing block. (Clips above the
 * stacking context still apply to it: painting cannot undo a clip.)
 *
 * <p>Instances keep scratch state and are not reentrant: use one per visitor.
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
        void inlineBox(Box block, Fragment.InlineBox fragment, ComputedStyle style, float x, float y);

        /** A text run of {@code block}; ({@code x}, {@code y}) is the origin of the fragments' space. */
        void textRun(Box block, Fragment.TextRun run, float x, float y);

        /** After the box and everything inside it: scrollbars and outline. */
        void after(Box box, ComputedStyle style, float x, float y);

        /** After all of {@code block}'s lines, for each inline box fragment that has an outline. */
        void inlineOutline(Fragment.InlineBox fragment, ComputedStyle style, float x, float y);
    }

    private final Layers layers;
    private final Affine transform = new Affine(), offset = new Affine();
    /** The transformed box whose border box is the current space, or null for the viewport. */
    private Box space;
    private Box[] chain = new Box[16];
    /** The fragment index each open inline opacity group ends at, innermost last (a stack across nested lines). */
    private int[] groupEnds = new int[4];
    private int groups;

    StackingOrder(Layers layers) {
        this.layers = layers;
    }

    /** Walks the tree rooted at {@code root}, which is a stacking context whatever its style. */
    void walk(Box root, Visitor visitor) {
        space = null;
        groups = 0;
        layer(root, styleOf(root), root.x, root.y, true, visitor);
    }

    // ---- Styles ----

    /**
     * The style to paint a box with. Element boxes (block, replaced, inline) use the element's live style, so
     * paint-only animations (opacity, transform, colours) show without a relayout; anonymous and pseudo-element
     * boxes keep their layout style.
     */
    static ComputedStyle styleOf(Box box) {
        if (box.kind == Box.Kind.BLOCK || box.kind == Box.Kind.REPLACED || box.kind == Box.Kind.INLINE) {
            ComputedStyle live = box.element == null ? null : box.element.style;
            if (live != null) return live;
        }
        return box.style;
    }

    /** The live style of a text run (generated content keeps its own). */
    static ComputedStyle styleOf(Fragment.TextRun run) {
        Element source = run.styleSource();
        return run.node() == null || source == null || source.style == null ? run.style() : source.style;
    }

    /** Positioned boxes and stacking contexts are painted from their stacking context's z-ordered list. */
    static boolean isLayer(ComputedStyle s) {
        return s.position.isPositioned() || s.createsStackingContext();
    }

    static int zIndex(Box box) {
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
        Box outerSpace = space;
        boolean transformed = Coordinates.transform(box, s, transform);
        if (s.opacity < 1 || transformed) {
            if (!v.enterContext(box, s, x, y, transformed ? transform : null)) return;
            if (transformed) {
                space = box;
                x = 0;
                y = 0;
            }
        }
        v.box(box, s, x, y);
        if (context) {
            Box[] items = layers.of(box);
            int i = 0;
            for (; i < items.length && zIndex(items[i]) < 0; i++) item(box, items[i], v);
            blocks(box, x, y, v);
            inlines(box, x, y, v);
            for (; i < items.length; i++) item(box, items[i], v);
        } else {
            blocks(box, x, y, v);
            inlines(box, x, y, v);
        }
        v.after(box, s, x, y);
        if (s.opacity < 1 || transformed) v.exitContext();
        space = outerSpace;
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
        boolean outlines = false;
        for (LineBox line : box.lines) outlines |= line(box, line, cx, cy, v);
        if (outlines) {
            for (LineBox line : box.lines) {
                for (Fragment f : line.fragments) {
                    if (!(f instanceof Fragment.InlineBox ib)) continue;
                    ComputedStyle s = styleOf(ib.box());
                    if (hasOutline(s)) v.inlineOutline(ib, s, cx, cy);
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

    /**
     * One line's fragments in order. An inline element's fragment comes before the content it wraps, up to its
     * {@code end}: when the element is translucent, that range is one group with the opacity applied (skipped at
     * opacity 0, like any translucent box). Returns whether an inline box on the line has an outline.
     */
    private boolean line(Box block, LineBox line, float cx, float cy, Visitor v) {
        List<Fragment> fragments = line.fragments;
        int outer = groups;
        boolean outlines = false;
        for (int i = 0; i < fragments.size(); i++) {
            while (groups > outer && groupEnds[groups - 1] == i) {
                v.exitContext();
                groups--;
            }
            switch (fragments.get(i)) {
                case Fragment.InlineBox ib -> {
                    ComputedStyle s = styleOf(ib.box());
                    outlines |= hasOutline(s);
                    if (s.opacity < 1) {
                        if (!v.enterContext(ib.box(), s, cx, cy, null)) {
                            i = ib.end() - 1;
                            continue;
                        }
                        if (groups == groupEnds.length) groupEnds = Arrays.copyOf(groupEnds, groups * 2);
                        groupEnds[groups++] = ib.end();
                    }
                    v.inlineBox(block, ib, s, cx, cy);
                }
                case Fragment.TextRun run -> v.textRun(block, run, cx, cy);
                case Fragment.Atomic atomic -> {
                    Box b = atomic.box();
                    ComputedStyle s = styleOf(b);
                    if (!isLayer(s)) layer(b, s, cx + b.x, cy + b.y, false, v);
                }
            }
        }
        for (; groups > outer; groups--) v.exitContext();
        return outlines;
    }

    static boolean hasOutline(ComputedStyle s) {
        return s.outlineStyle.isVisible() && s.outlineWidth > 0 && !Colors.isTransparent(s.outlineColor);
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
     * Paints {@code item}, a descendant of the context {@code root}, from the context's z-ordered list: inside the
     * clips of the ancestors up to the root whose content holds it, then whole at its place in the current space.
     */
    private void item(Box root, Box item, Visitor v) {
        int n = 0;
        for (Box p = item.parent; p != null; p = p.parent) {
            if (n == chain.length) chain = Arrays.copyOf(chain, n * 2);
            chain[n++] = p;
            if (p == root) break;
        }
        // Keep the ancestors whose content holds the item, innermost first: content parents come up the tree in
        // order, and one an out-of-flow box escapes to above the root is never met.
        int holders = 0;
        Box next = item.contentParent();
        for (int i = 0; i < n && next != null; i++) {
            if (chain[i] != next) continue;
            chain[holders++] = next;
            next = next.contentParent();
        }
        int pushed = 0;
        boolean visible = true;
        for (int i = holders - 1; i >= 0 && visible; i--) {
            Box a = chain[i];
            if (!clips(a, styleOf(a))) continue;
            Coordinates.offset(a, space, offset);
            visible = a == space ? clipToPaddingBox(a, 0, 0, v) : clipToPaddingBox(a, offset.e, offset.f, v);
            if (visible) pushed++;
        }
        if (visible) {
            ComputedStyle s = styleOf(item);
            Coordinates.offset(item, space, offset);
            layer(item, s, offset.e, offset.f, s.createsStackingContext(), v);
        }
        for (; pushed > 0; pushed--) v.popClip();
    }
}
