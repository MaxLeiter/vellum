package dev.vellum.engine.input;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.layout.Box;
import dev.vellum.engine.paint.Affine;
import dev.vellum.engine.paint.Coordinates;
import dev.vellum.engine.paint.HitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Tree and geometry queries used across the input package. */
final class Dom {
    private Dom() {}

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

    /**
     * A viewport point in {@code box}'s border-box coordinates {x, y} (NaN when a transform collapses the box).
     * Drags use it for every pointer position after the press.
     */
    static float[] local(Box box, float x, float y) {
        Affine m = new Affine();
        if (!Coordinates.fromViewport(box, m)) return new float[] {Float.NaN, Float.NaN};
        return new float[] {m.mapX(x, y), m.mapY(x, y)};
    }

    /** Like {@link #local}, but taken from the hit test when it was a hit in {@code box}. */
    static float[] local(Box box, HitResult hit, float x, float y) {
        return hit != null && hit.box() == box ? new float[] {hit.localX(), hit.localY()} : local(box, x, y);
    }
}
