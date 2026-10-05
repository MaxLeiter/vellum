package dev.vellum.engine.layout;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** A closed details shows only its summary: its other elements and its loose text are not rendered. */
class DetailsTest {
    private static List<String> texts(Box box) {
        List<String> out = new ArrayList<>();
        for (LineBox line : box.lines) for (Fragment f : line.fragments) if (f instanceof Fragment.TextRun r) out.add(r.text());
        for (Box child : box.children) out.addAll(texts(child));
        return out;
    }

    @Test
    void closedDetailsHideAllButTheSummary() {
        Page page = new TestHost().load("<details id=d><summary id=s>S</summary>loose<button id=b>x</button></details>");
        Element d = page.byId("d"), b = page.byId("b");
        assertNull(b.box);
        assertEquals(List.of("▶ ", "S"), texts(d.box));
        page.key("Tab");
        page.key("Tab");
        assertSame(page.byId("s"), page.doc.focusedElement(), "the hidden button is not a tab stop");

        d.setAttribute("open", "");
        page.frame();
        assertNotNull(b.box);
        assertEquals(List.of("▼ ", "S", "loose", "x"), texts(d.box));
    }
}
