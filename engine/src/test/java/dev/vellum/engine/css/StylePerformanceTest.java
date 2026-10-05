package dev.vellum.engine.css;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** A sanity check that a few hundred elements against a few hundred rules restyle in a few milliseconds. */
class StylePerformanceTest {
    private static final int ELEMENTS = 500, RULES = 200;

    private static String stylesheet() {
        StringBuilder css = new StringBuilder(":root { --accent: #5b8bd9; --pad: 2px }\n");
        for (int i = 0; i < RULES; i++) {
            String selector = switch (i % 5) {
                case 0 -> ".c" + i;
                case 1 -> ".list > .c" + i + ":hover";
                case 2 -> "#e" + i + ", div.c" + i + " span";
                case 3 -> "[data-k='" + i + "'] .c" + (i - 1) + ":not(.off)";
                default -> ".c" + i + ":nth-child(2n+1)";
            };
            css.append(selector).append(" { color: #").append(String.format("%06x", i * 4097))
                    .append("; padding: var(--pad) ").append(i % 7).append("px; margin: 0 ").append(i % 3)
                    .append("em; border: 1px solid var(--accent); transition: color .2s; ")
                    .append(i % 2 == 0 ? "display: flex; gap: 2px;" : "font-weight: bold; width: calc(50% - 4px);")
                    .append(" }\n");
        }
        return css.toString();
    }

    private static String body() {
        StringBuilder html = new StringBuilder("<div class=list data-k=3>");
        for (int i = 0; i < ELEMENTS / 5; i++) {
            html.append("<div class='row c").append(i % RULES).append("' id=e").append(i).append(">");
            for (int j = 0; j < 4; j++) html.append("<span class='c").append((i * 4 + j) % RULES).append("'>x</span>");
            html.append("</div>");
        }
        return html.append("</div>").toString();
    }

    @Test
    void restyleIsFast() {
        Document doc = new TestHost().load("<style>" + stylesheet() + "</style>" + body()).doc;
        List<Element> all = doc.querySelectorAll("*");
        assertTrue(all.size() >= ELEMENTS, "elements: " + all.size());
        Element root = doc.documentElement();
        Element hovered = doc.querySelectorAll(".row").get(7);

        // Warm up the JIT, then time full restyles (a root class change invalidates every element's inputs)
        // and hover restyles (most elements reuse their styles).
        for (int i = 0; i < 40; i++) fullRestyle(doc, root, i);
        long full = time(20, i -> fullRestyle(doc, root, i));
        long hover = time(20, i -> {
            doc.setHovered(hovered, i % 2 == 0);
            doc.flushStyle();
        });
        System.out.printf("restyle of %d elements x %d rules: full %.2f ms, hover %.2f ms%n", all.size(), RULES,
                full / 1e6, hover / 1e6);
        assertTrue(full < 20_000_000, "full restyle took " + full / 1e6 + " ms");
        assertTrue(hover < 20_000_000, "hover restyle took " + hover / 1e6 + " ms");
    }

    /**
     * Hovering a container whose hover style only changes its border keeps every descendant's style (their
     * inherited properties did not change), so 500 transitioned children cost neither cascade work nor transition
     * bookkeeping.
     */
    @Test
    void hoverOverTransitionedElementsIsCheap() {
        StringBuilder html = new StringBuilder("""
                <style>
                  .list { border: 1px solid #333 } .list:hover { border-color: #fff }
                  .card { padding: 2px; transition: background-color .2s, transform .2s, opacity .2s }
                  .card:hover { background-color: #444 }
                </style><div class=list>""");
        for (int i = 0; i < ELEMENTS; i++) html.append("<div class=card><span>item ").append(i).append("</span></div>");
        Document doc = new TestHost().load(html.append("</div>").toString()).doc;
        Element list = doc.querySelector(".list");
        for (int i = 0; i < 200; i++) hover(doc, list, i);
        long time = time(20, i -> hover(doc, list, i));
        long allocated = allocated(() -> hover(doc, list, 1)) + allocated(() -> hover(doc, list, 0));
        System.out.printf("hover restyle over %d transitioned elements: %.3f ms, %d KB allocated%n", ELEMENTS,
                time / 1e6, allocated / 2 / 1024);
        assertTrue(time < 5_000_000, "hover restyle took " + time / 1e6 + " ms");
        assertTrue(allocated / 2 < 256 * 1024, "hover restyle allocated " + allocated / 2 + " bytes");
    }

    private static void hover(Document doc, Element element, int i) {
        doc.setHovered(element, i % 2 == 0);
        doc.flushStyle();
        doc.animations().tick(0);
    }

    /** Bytes allocated by this thread while {@code body} runs. */
    private static long allocated(Runnable body) {
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long before = bean.getCurrentThreadAllocatedBytes();
        body.run();
        return bean.getCurrentThreadAllocatedBytes() - before;
    }

    private static void fullRestyle(Document doc, Element root, int i) {
        root.setAttribute("style", "font-size: " + (8 + i % 2) + "px");
        doc.flushStyle();
    }

    /** The best time of {@code runs} runs, in ns: the code's cost, without noise from a busy machine. */
    private static long time(int runs, java.util.function.IntConsumer body) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < runs; i++) {
            long start = System.nanoTime();
            body.accept(i);
            best = Math.min(best, System.nanoTime() - start);
        }
        return best;
    }
}
