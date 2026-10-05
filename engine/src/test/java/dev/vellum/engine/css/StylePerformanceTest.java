package dev.vellum.engine.css;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import org.junit.jupiter.api.Test;

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
        Document doc = StyleTesting.page("<style>" + stylesheet() + "</style>" + body());
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
            doc.styleEngine().restyle();
        });
        System.out.printf("restyle of %d elements x %d rules: full %.2f ms, hover %.2f ms%n", all.size(), RULES,
                full / 1e6, hover / 1e6);
        assertTrue(full < 20_000_000, "full restyle took " + full / 1e6 + " ms");
        assertTrue(hover < 20_000_000, "hover restyle took " + hover / 1e6 + " ms");
    }

    private static void fullRestyle(Document doc, Element root, int i) {
        root.setAttribute("style", "font-size: " + (8 + i % 2) + "px");
        doc.styleEngine().restyle();
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
