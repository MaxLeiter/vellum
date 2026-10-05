package dev.vellum.engine.layout;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A 500-element page of nested flex rows and columns, grids and wrapping text must lay out well within a frame.
 * The bound is loose so the test is not flaky on a busy machine; the measured time is printed.
 */
class LayoutPerformanceTest {
    @Test
    void fiveHundredElementsLayOutQuickly() {
        StringBuilder html = new StringBuilder("<div style='display: flex; flex-direction: column; gap: 4px; padding: 8px'>");
        int elements = 1;
        while (elements < 500) {
            html.append("<div style='display: flex; flex-direction: column; padding: 4px; border: 1px solid'>")
                    .append("<div style='display: flex; justify-content: space-between; align-items: center'>");
            for (int i = 0; i < 3; i++) html.append("<span style='padding: 0 2px'>Title ").append(i).append("</span>");
            html.append("</div><div style='display: grid; grid-template-columns: repeat(auto-fill, minmax(40px, 1fr)); gap: 2px'>");
            for (int i = 0; i < 8; i++) {
                html.append("<div style='display: flex; flex-direction: column; align-items: center'><div>Item ").append(i)
                        .append("</div><span style='display: inline-block; width: 16px; height: 16px'></span></div>");
            }
            html.append("</div><div style='text-align: justify'>Some wrapping text with a <b>bold part</b>")
                    .append(" and a little more after it to make several lines.</div></div>");
            elements += 1 + 1 + 3 + 1 + 8 * 3 + 2;
        }
        Document doc = new TestHost().load(html.append("</div>").toString()).doc;
        for (int i = 0; i < 30; i++) relayout(doc);
        int runs = 30;
        long start = System.nanoTime();
        for (int i = 0; i < runs; i++) relayout(doc);
        double ms = (System.nanoTime() - start) / 1e6 / runs;
        System.out.printf("[layout] %d elements: %.2f ms per layout%n", elements, ms);
        assertTrue(ms < 50, "layout took " + ms + " ms");
    }

    private static void relayout(Document doc) {
        doc.invalidateLayout();
        doc.flushLayout();
    }
}
