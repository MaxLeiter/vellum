package dev.vellum.engine.css;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.testing.TestHost;

/** Helpers for style tests: parse a page and restyle it without running layout (owned by another workstream). */
final class StyleTesting {
    private StyleTesting() {}

    static Document page(String html) {
        return page(new TestHost(), html);
    }

    static Document page(TestHost host, String html) {
        Document doc = Document.parse(host, "test:page.html", html);
        doc.setViewport(320, 240, 2);
        doc.styleEngine().restyle();
        return doc;
    }

    static Element element(Document doc, String selector) {
        Element e = doc.querySelector(selector);
        if (e == null) throw new AssertionError("No element matches " + selector);
        return e;
    }

    static ComputedStyle style(Document doc, String selector) {
        return element(doc, selector).baseStyle;
    }

    /** The computed style of a single {@code <div id=t>} with the given declarations. */
    static ComputedStyle styleOf(String declarations) {
        return style(page("<div id=t style='" + declarations.replace("'", "&#39;") + "'></div>"), "#t");
    }

    static String computed(ComputedStyle style, String property) {
        return StyleEngine.computedValue(style, property);
    }

    /**
     * Clears the document's layout-dirty flag, so a test can observe whether a restyle invalidates layout. (Running
     * a frame would clear it, but these tests run without the layout engine.)
     */
    static void clearLayoutFlag(Document doc) {
        try {
            java.lang.reflect.Field f = Document.class.getDeclaredField("layoutDirty");
            f.setAccessible(true);
            f.setBoolean(doc, false);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** Restyles and reports whether that invalidated layout. */
    static boolean restyleInvalidatesLayout(Document doc) {
        clearLayoutFlag(doc);
        doc.styleEngine().restyle();
        return doc.needsLayout();
    }
}
