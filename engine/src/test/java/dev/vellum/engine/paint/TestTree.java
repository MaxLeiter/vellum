package dev.vellum.engine.paint;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Text;
import dev.vellum.engine.layout.Box;
import dev.vellum.engine.layout.Fragment;
import dev.vellum.engine.layout.LineBox;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Display;
import dev.vellum.engine.style.Overflow;
import dev.vellum.engine.style.Position;
import dev.vellum.engine.testing.TestHost;

/** Builds box trees by hand (layout is another workstream) and paints or hit-tests them. */
final class TestTree {
    final Document doc = Document.create(new TestHost(), "test:paint.html");
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

    static Box position(Box b, Position position) {
        style(b).position = position;
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
        b.element.scrollLeft = scrollLeft;
        b.element.scrollTop = scrollTop;
        b.scrollWidth = scrollWidth;
        b.scrollHeight = scrollHeight;
        return b;
    }

    /** A text node under {@code parent} and a run for it at (x, y), sized with the test font. */
    Fragment.TextRun text(Element parent, String data, float x, float y) {
        Text node = parent.appendChild(doc.createTextNode(data));
        float w = doc.host().fonts().width(data, dev.vellum.engine.host.FontSpec.of(parent.style));
        return new Fragment.TextRun(node, parent, parent.style, data, 0, data.length(), x, y, w, 9);
    }

    static LineBox line(Box block, float x, float y, float w, float h, Fragment... fragments) {
        LineBox line = new LineBox(x, y, w, h, y + 7);
        line.fragments.addAll(java.util.List.of(fragments));
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
