package dev.vellum.engine.script;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A long keyed v-for re-renders in time linear in its length. The bound is loose; the measured time is printed. */
class TemplatePerformanceTest {
    private static final int ITEMS = 3000;

    @Test
    void longListsDigestInLinearTime() {
        Page page = new Page("<ul id=l><li v-for=\"item in s.items\" :key=\"item\">{{ item }}</li></ul><script>"
                + "const s = vellum.state({items: Array.from({length: " + ITEMS + "}, (_, i) => i)})</script>");
        for (int i = 0; i < 30; i++) rotate(page);
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 20; i++) {
            long start = System.nanoTime();
            rotate(page);
            best = Math.min(best, System.nanoTime() - start);
        }
        assertEquals(ITEMS, page.byId("l").childCount() - 1); // and the anchor
        System.out.printf("v-for of %d items, one moved: %.2f ms per digest%n", ITEMS, best / 1e6);
        assertTrue(best < 50_000_000, "digest took " + best / 1e6 + " ms");
    }

    /** Moves the first item to the end, then renders. */
    private static void rotate(Page page) {
        page.run("s.items.push(s.items.shift())");
        page.render();
    }
}
