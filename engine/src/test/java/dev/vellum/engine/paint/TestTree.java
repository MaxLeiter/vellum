package dev.vellum.engine.paint;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Text;
import dev.vellum.engine.host.FontSpec;
import dev.vellum.engine.layout.Box;
import dev.vellum.engine.layout.Fragment;
import dev.vellum.engine.layout.LineBox;
import dev.vellum.engine.layout.TextMeasure;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Display;
import dev.vellum.engine.style.Overflow;
import dev.vellum.engine.style.Position;
import dev.vellum.engine.testing.TestHost;

/** Builds box trees by hand (layout is another workstream) and paints or hit-tests them. */
final class TestTree {
    final TestHost host = new TestHost();
    final Document doc = Document.create(host, "test:paint.html");
    final Painter painter = new Painter(doc);

    /** An element with a fresh block style. */
    Element element(String tag) {
        Element e = doc.createElement(tag);
        e.style = new ComputedStyle();
        e.style.display = Display.BLOCK;
        return e;
    }

    Box box(Element e, float x, float y, float w, float h) {
        return box(Box.Kind.BLOCK, e, x, y, w, h);
    }

    Box box(Box.Kind kind, Element e, float x, float y, float w, float h) {
        Box b = new Box(kind, e, e.style);
        b.x = x;
        b.y = y;
        b.width = w;
        b.height = h;
        if (kind != Box.Kind.ANONYMOUS) e.box = b;
        return b;
    }

    /** A div box with a background colour. */
    Box div(float x, float y, float w, float h, int background) {
        Box b = box(element("div"), x, y, w, h);
        b.element.style.backgroundColor = background;
        return b;
    }

    /** Adds {@code child} to {@code parent} and returns the child. */
    static Box add(Box parent, Box child) {
        parent.add(child);
        return child;
    }

    static ComputedStyle style(Box b) {
        return b.element.style;
    }

    /** Positions a box; absolute and fixed ones are placed against the viewport, as layout would record. */
    static Box position(Box b, Position position) {
        style(b).position = position;
        b.outOfFlow = position.isOutOfFlow();
        return b;
    }

    static Box z(Box b, Position position, int z) {
        position(b, position);
        style(b).zIndexAuto = false;
        style(b).zIndex = z;
        return b;
    }

    /** Makes {@code b} a scroll container with the given scroll offset and content size. */
    static Box scroller(Box b, Overflow overflow, float scrollLeft, float scrollTop, float scrollWidth, float scrollHeight) {
        style(b).overflowX = overflow;
        style(b).overflowY = overflow;
        b.scrollWidth = scrollWidth;
        b.scrollHeight = scrollHeight;
        b.element.scrollTo(scrollLeft, scrollTop);
        return b;
    }

    /** A text node under {@code parent} and a run for it at (x, y), measured as layout measures it. */
    Fragment.TextRun text(Element parent, String data, float x, float y) {
        Text node = parent.appendChild(doc.createTextNode(data));
        TextMeasure measure = doc.layoutEngine().textMeasure();
        FontSpec font = FontSpec.of(parent.style);
        return new Fragment.TextRun(node, parent, parent.style, data, null, measure.spaced(data, font, parent.style),
                x, y, measure.width(data, font, parent.style), 9);
    }

    /**
     * The fragment of inline element {@code e} on a line, wrapping the line's fragments up to index {@code end}; it
     * gets an inline box with the fragment's bounds, as layout gives it.
     */
    static Fragment.InlineBox inline(Element e, float x, float y, float w, float h, int end) {
        Box box = new Box(Box.Kind.INLINE, e, e.style);
        box.x = x;
        box.y = y;
        box.width = w;
        box.height = h;
        e.box = box;
        return new Fragment.InlineBox(box, x, y, w, h, true, true, end);
    }

    static LineBox line(Box block, float x, float y, float w, float h, Fragment... fragments) {
        LineBox line = new LineBox(x, y, w, h, y + 7);
        line.fragments.addAll(java.util.List.of(fragments));
        for (Fragment f : fragments) if (f instanceof Fragment.InlineBox ib) ib.box().parent = block;
        block.lines.add(line);
        return line;
    }

    RecordingCanvas paint(Box root) {
        RecordingCanvas canvas = new RecordingCanvas();
        return paint(root, canvas);
    }

    RecordingCanvas paint(Box root, RecordingCanvas canvas) {
        painter.paint(canvas, root);
        if (!canvas.balanced()) throw new AssertionError("unbalanced save/restore");
        return canvas;
    }

    HitResult hit(Box root, float x, float y) {
        return painter.hitTest(root, x, y);
    }
}
