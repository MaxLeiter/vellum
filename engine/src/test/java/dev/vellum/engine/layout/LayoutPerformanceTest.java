package dev.vellum.engine.layout;

import dev.vellum.engine.dom.Element;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A 500-element page of nested flex rows and columns, grids and wrapping text must lay out well within a frame.
 * The bound is loose so the test is not flaky on a busy machine; the measured time is printed.
 */
class LayoutPerformanceTest {
    @Test
    void fiveHundredElementsLayOutQuickly() {
        TestDoc t = new TestDoc();
        int elements = 0;
        Element page = t.div(t.body, "display: flex; flex-direction: column; gap: 4px; padding: 8px");
        while (elements < 500) {
            Element card = t.div(page, "display: flex; flex-direction: column; padding: 4px; border: 1px");
            Element header = t.div(card, "display: flex; justify-content: space-between; align-items: center");
            for (int i = 0; i < 3; i++) t.text(t.add(header, "span", "padding: 0 2px"), "Title " + i);
            Element grid = t.div(card,
                    "display: grid; grid-template-columns: repeat(auto-fill, minmax(40px, 1fr)); gap: 2px");
            for (int i = 0; i < 8; i++) {
                Element cell = t.div(grid, "display: flex; flex-direction: column; align-items: center");
                t.text(t.div(cell, ""), "Item " + i);
                t.add(cell, "span", "display: inline-block; width: 16px; height: 16px");
            }
            Element p = t.div(card, "text-align: justify");
            t.text(p, "Some wrapping text with a ");
            t.text(t.add(p, "b", "font-weight: bold"), "bold part");
            t.text(p, " and a little more after it to make several lines.");
            elements += 1 + 1 + 3 + 1 + 8 * 3 + 2;
        }
        t.layout(320, 240);
        for (int i = 0; i < 30; i++) t.doc.layoutEngine().layout();
        int runs = 30;
        long start = System.nanoTime();
        for (int i = 0; i < runs; i++) t.doc.layoutEngine().layout();
        double ms = (System.nanoTime() - start) / 1e6 / runs;
        System.out.printf("[layout] %d elements: %.2f ms per layout%n", elements, ms);
        assertTrue(ms < 50, "layout took " + ms + " ms");
    }
}
