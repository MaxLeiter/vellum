package dev.vellum.engine.layout;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Node;
import dev.vellum.engine.dom.Text;
import dev.vellum.engine.input.Controls;
import dev.vellum.engine.layout.InlineContent.Span;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Display;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Builds a pass's box tree from the DOM and the computed styles (CSS Display box generation, simplified):
 * <ul>
 *   <li>{@code display: none} generates nothing; {@code contents} generates only its children (and
 *       pseudo-elements);</li>
 *   <li>a block container whose children mix block- and inline-level content wraps each inline run in an anonymous
 *       block; runs of collapsible white space between blocks generate nothing;</li>
 *   <li>an inline element that contains block-level content is blockified (instead of CSS's block-in-inline
 *       split);</li>
 *   <li>flex and grid containers blockify their children and wrap loose text in anonymous items;</li>
 *   <li>{@code ::before}/{@code ::after} with content become {@link Box.Kind#PSEUDO} boxes, or inline spans of
 *       text;</li>
 *   <li>replaced elements and form controls are leaves; their DOM children are never laid out;</li>
 *   <li>absolutely and fixed positioned boxes are children of their nearest box (an anonymous block's, when they sit
 *       in a wrapped inline run), with an inline placeholder or their place among the block children marking the
 *       static position.</li>
 * </ul>
 */
final class BoxTreeBuilder {
    /**
     * Builds the tree for the root element and returns its box, or null when it is not rendered. Clears every
     * element's previous box first, so elements that generate no box end up with null.
     */
    LayoutBox build(Element root) {
        clearBoxes(root);
        ComputedStyle s = root.style;
        if (s == null || s.display == Display.NONE) return null;
        LayoutBox box = elementBox(root, s, s.display == Display.CONTENTS ? Display.BLOCK : s.display.blockified());
        box.independent = true;
        return box;
    }

    private static void clearBoxes(Node node) {
        if (node instanceof Element e) e.box = null;
        for (int i = 0, n = node.childCount(); i < n; i++) clearBoxes(node.childAt(i));
    }

    // ---- Boxes ----

    /** The principal box of an element that is block-level, an atomic inline, or a flex/grid item. */
    private LayoutBox elementBox(Element e, ComputedStyle s, Display display) {
        boolean replaced = e.replaced != null;
        LayoutBox.Context context = replaced || Controls.isControl(e.tagName()) ? LayoutBox.Context.LEAF
                : contextOf(display);
        LayoutBox box = new LayoutBox(replaced ? Box.Kind.REPLACED : Box.Kind.BLOCK, e, s, context);
        box.independent = s.isScrollContainer();
        e.box = box;
        if (replaced) naturalSize(box);
        buildContent(box, flow -> addChildren(flow, e, flow.root), items -> addItems(items, e));
        return box;
    }

    /** The box of a ::before/::after that is not an inline span; its only content is the {@code content} text. */
    private LayoutBox pseudoBox(Element host, ComputedStyle s, Display display) {
        LayoutBox box = new LayoutBox(Box.Kind.PSEUDO, host, s, contextOf(display));
        box.independent = s.isScrollContainer();
        buildContent(box, flow -> flow.run().addText(flow.root, null, s.content),
                items -> items.text(null, s.content));
        return box;
    }

    private static LayoutBox.Context contextOf(Display display) {
        return display.isFlex() ? LayoutBox.Context.FLEX
                : display.isGrid() ? LayoutBox.Context.GRID : LayoutBox.Context.FLOW;
    }

    private void buildContent(LayoutBox box, Consumer<Flow> flowContent, Consumer<Items> itemContent) {
        switch (box.context) {
            case FLOW -> {
                Flow flow = new Flow(box);
                flowContent.accept(flow);
                flow.finish();
            }
            case FLEX, GRID -> {
                Items items = new Items(box);
                itemContent.accept(items);
                items.finish();
            }
            case LEAF -> { }
        }
    }

    /** A box for an element that is taken out of flow (absolute or fixed). */
    private LayoutBox outOfFlowBox(Element e, ComputedStyle s) {
        LayoutBox box = elementBox(e, s, s.display.blockified());
        box.outOfFlow = true;
        box.independent = true;
        return box;
    }

    private LayoutBox outOfFlowPseudo(Element host, ComputedStyle s) {
        LayoutBox box = pseudoBox(host, s, s.display.blockified());
        box.outOfFlow = true;
        box.independent = true;
        return box;
    }

    /** The content's natural size; without one, the {@code width}/{@code height} attributes (as HTML sizes images). */
    private static void naturalSize(LayoutBox box) {
        Element e = box.element;
        float w = e.replaced.intrinsicWidth(), h = e.replaced.intrinsicHeight();
        if (Float.isNaN(w)) w = e.numberAttribute("width", Float.NaN);
        if (Float.isNaN(h)) h = e.numberAttribute("height", Float.NaN);
        if (Float.isNaN(w) && Float.isNaN(h)) w = h = 0;
        box.naturalWidth = w;
        box.naturalHeight = h;
    }

    private static boolean rendered(ComputedStyle s) {
        return s != null && s.display != Display.NONE;
    }

    private static boolean hasPseudo(ComputedStyle s) {
        return rendered(s) && s.content != null;
    }

    // ---- Block flow ----

    /** Children of {@code parent} (with its pseudo-elements) into a block flow, inside inline {@code span}. */
    private void addChildren(Flow flow, Element parent, Span span) {
        addPseudo(flow, parent, parent.beforeStyle, span);
        for (int i = 0, n = parent.childCount(); i < n; i++) {
            Node child = parent.childAt(i);
            if (child instanceof Text t) flow.run().addText(span, t, t.data());
            else if (child instanceof Element e) addElement(flow, e, span);
        }
        addPseudo(flow, parent, parent.afterStyle, span);
    }

    private void addElement(Flow flow, Element e, Span span) {
        ComputedStyle s = e.style;
        if (!rendered(s)) return;
        Display d = s.display;
        if (d == Display.CONTENTS) {
            addChildren(flow, e, span);
        } else if (s.position.isOutOfFlow()) {
            flow.outOfFlow(outOfFlowBox(e, s));
        } else if (e.tagName().equals("br")) {
            Box br = new Box(Box.Kind.INLINE, e, s);
            e.box = br;
            flow.run().lineBreak(span, br);
        } else if (d == Display.INLINE && !isAtomic(e)) {
            if (containsBlockLevel(e)) {
                flow.block(elementBox(e, s, Display.BLOCK));
            } else {
                Box box = new Box(Box.Kind.INLINE, e, s);
                e.box = box;
                Span inner = new Span(e, s, span, box);
                flow.run().open(inner);
                addChildren(flow, e, inner);
                flow.run().close(inner);
            }
        } else if (d.isInlineLevel()) {
            flow.atomic(span, elementBox(e, s, d));
        } else {
            flow.block(elementBox(e, s, d));
        }
    }

    private void addPseudo(Flow flow, Element host, ComputedStyle s, Span span) {
        if (!hasPseudo(s)) return;
        if (s.position.isOutOfFlow()) {
            flow.outOfFlow(outOfFlowPseudo(host, s));
        } else if (s.display == Display.INLINE || s.display == Display.CONTENTS) {
            Span inner = new Span(host, s, span, null);
            flow.run().open(inner);
            flow.run().addText(inner, null, s.content);
            flow.run().close(inner);
        } else if (s.display.isInlineLevel()) {
            flow.atomic(span, pseudoBox(host, s, s.display));
        } else {
            flow.block(pseudoBox(host, s, s.display));
        }
    }

    private boolean isAtomic(Element e) {
        return e.replaced != null || Controls.isControl(e.tagName());
    }

    /** Whether an inline element has in-flow block-level content (through inline and display:contents children). */
    private boolean containsBlockLevel(Element e) {
        if (isBlockLevel(e.beforeStyle) || isBlockLevel(e.afterStyle)) return true;
        for (int i = 0, n = e.childCount(); i < n; i++) {
            if (!(e.childAt(i) instanceof Element c) || !rendered(c.style) || c.style.position.isOutOfFlow()) continue;
            Display d = c.style.display;
            if (d == Display.CONTENTS || (d == Display.INLINE && !isAtomic(c))) {
                if (containsBlockLevel(c)) return true;
            } else if (!d.isInlineLevel()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlockLevel(ComputedStyle s) {
        return hasPseudo(s) && !s.position.isOutOfFlow() && !s.display.isInlineLevel() && s.display != Display.CONTENTS;
    }

    /** Collects a block container's children: block-level boxes and runs of inline-level content. */
    private static final class Flow {
        final LayoutBox container;
        final Span root;
        /** Block-level boxes and finished inline runs, in order. */
        private final List<Object> entries = new ArrayList<>();
        private Run run;
        private boolean hasBlocks;

        /** An inline run and the boxes met inside it (atomic inlines and out-of-flow boxes), in order. */
        private record Run(InlineContent content, List<LayoutBox> boxes) {}

        Flow(LayoutBox container) {
            this.container = container;
            this.root = new Span(container.element, container.style, null, null);
        }

        InlineContent run() {
            if (run == null) run = new Run(new InlineContent(root), new ArrayList<>());
            return run.content;
        }

        void atomic(Span span, LayoutBox box) {
            box.atomicInline = true;
            box.independent = true;
            run().atomic(span, box);
            run.boxes.add(box);
        }

        /**
         * Out-of-flow boxes get an inline placeholder; if their run turns out to be only white space between
         * blocks, they take their place among the block children instead (see {@link #finish}).
         */
        void outOfFlow(LayoutBox box) {
            run().placeholder(box);
            run.boxes.add(box);
        }

        void block(LayoutBox box) {
            endRun();
            hasBlocks = true;
            entries.add(box);
        }

        private void endRun() {
            if (run != null) entries.add(run);
            run = null;
        }

        void finish() {
            endRun();
            ComputedStyle anonymousStyle = null;
            for (Object entry : entries) {
                if (entry instanceof LayoutBox box) {
                    container.add(box);
                } else if (entry instanceof Run r && !hasBlocks) {
                    // Only inline content: the container itself establishes the inline formatting context.
                    own(container, r);
                } else if (entry instanceof Run r && r.content.hasContent()) {
                    if (anonymousStyle == null) {
                        anonymousStyle = ComputedStyle.inheritFrom(container.style);
                        anonymousStyle.display = Display.BLOCK;
                    }
                    LayoutBox anonymous = new LayoutBox(Box.Kind.ANONYMOUS, container.element, anonymousStyle,
                            LayoutBox.Context.FLOW);
                    own(anonymous, r);
                    container.add(anonymous);
                } else if (entry instanceof Run r) {
                    // Collapsible white space between blocks: no box; its out-of-flow boxes become block-positioned.
                    r.content.adopt(container);
                    for (LayoutBox b : r.boxes) container.add(b);
                }
            }
        }

        private static void own(LayoutBox owner, Run r) {
            owner.inline = r.content;
            r.content.adopt(owner);
            for (LayoutBox b : r.boxes) owner.add(b);
        }
    }

    // ---- Flex and grid items ----

    /** Children of {@code parent} as flex/grid items (descending into display:contents). */
    private void addItems(Items items, Element parent) {
        addPseudoItem(items, parent, parent.beforeStyle);
        for (int i = 0, n = parent.childCount(); i < n; i++) {
            Node child = parent.childAt(i);
            if (child instanceof Text t) {
                items.text(t, t.data());
            } else if (child instanceof Element e && rendered(e.style)) {
                ComputedStyle s = e.style;
                if (s.display == Display.CONTENTS) addItems(items, e);
                else if (s.position.isOutOfFlow()) items.add(outOfFlowBox(e, s));
                else items.add(elementBox(e, s, s.display.blockified()));
            }
        }
        addPseudoItem(items, parent, parent.afterStyle);
    }

    private void addPseudoItem(Items items, Element host, ComputedStyle s) {
        if (!hasPseudo(s)) return;
        items.add(s.position.isOutOfFlow() ? outOfFlowPseudo(host, s)
                : pseudoBox(host, s, s.display == Display.CONTENTS ? Display.BLOCK : s.display.blockified()));
    }

    /** Collects flex/grid items; consecutive loose text becomes one anonymous item (unless it is all white space). */
    private static final class Items {
        final LayoutBox container;
        private InlineContent text;

        Items(LayoutBox container) {
            this.container = container;
        }

        void text(Text node, String data) {
            if (text == null) text = new InlineContent(new Span(container.element, anonymousStyle(), null, null));
            text.addText(text.root, node, data);
        }

        void add(LayoutBox box) {
            endText();
            box.independent = true;
            container.add(box);
        }

        private void endText() {
            if (text != null && text.hasContent()) {
                LayoutBox anonymous = new LayoutBox(Box.Kind.ANONYMOUS, container.element, text.root.style,
                        LayoutBox.Context.FLOW);
                anonymous.inline = text;
                anonymous.independent = true;
                container.add(anonymous);
            }
            text = null;
        }

        private ComputedStyle anonymousStyle() {
            ComputedStyle s = ComputedStyle.inheritFrom(container.style);
            s.display = Display.BLOCK;
            return s;
        }

        void finish() {
            endText();
        }
    }
}
