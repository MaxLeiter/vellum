package dev.vellum.engine.input;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.layout.Box;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Tree and geometry queries used across the input package. */
final class Dom {
    private Dom() {}

    /** Every element of the document in tree order, starting with the root element. */
    static List<Element> elements(Document document) {
        Element root = document.documentElement();
        if (root == null) return List.of();
        List<Element> all = new ArrayList<>();
        all.add(root);
        all.addAll(root.getElementsByTagName("*"));
        return all;
    }

    /** The element itself and its ancestors, innermost first; empty for null. */
    static List<Element> chain(Element e) {
        List<Element> chain = new ArrayList<>();
        for (Element p = e; p != null; p = p.parentElement()) chain.add(p);
        return chain;
    }

    /** The nearest inclusive ancestor matching {@code test}, or null. */
    static Element closest(Element e, Predicate<Element> test) {
        for (Element p = e; p != null; p = p.parentElement()) if (test.test(p)) return p;
        return null;
    }

    /** True when (x, y) lies in the rectangle {x, y, width, height}; false for a null rectangle. */
    static boolean inside(float[] rect, float x, float y) {
        return rect != null && x >= rect[0] && x < rect[0] + rect[2] && y >= rect[1] && y < rect[1] + rect[3];
    }

    /** The element's box, or the nearest ancestor's for boxless (inline, display: contents) elements. */
    static Box boxOf(Element e) {
        Element owner = closest(e, p -> p.box != null);
        return owner == null ? null : owner.box;
    }
}
