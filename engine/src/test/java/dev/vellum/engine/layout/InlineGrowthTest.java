package dev.vellum.engine.layout;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Inline runs longer than the initial piece buffers (16) must grow without losing pieces. */
class InlineGrowthTest {
    @Test
    void longRunsWithSpacesAtBufferBoundaries() {
        TestHost host = new TestHost();
        for (int words = 14; words <= 70; words++) {
            StringBuilder html = new StringBuilder("<p><span>");
            for (int i = 0; i < words; i++) html.append("w").append(i % 3 == 0 ? "-x " : " ");
            html.append("</span></p>");
            Document doc = host.load(html.toString());
            assertNull(doc.error(), "layout failed for " + words + " words");
            assertNotNull(doc.querySelector("p").box);
        }
    }
}
