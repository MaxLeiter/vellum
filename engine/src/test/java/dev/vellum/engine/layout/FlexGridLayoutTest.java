package dev.vellum.engine.layout;

import dev.vellum.engine.dom.Element;
import org.junit.jupiter.api.Test;

import static dev.vellum.engine.layout.BlockLayoutTest.assertRect;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Flex and grid behaviour the Taffy fixtures do not cover: loose text, order, grid-template-areas, auto-fill and
 * dense placement. (The algorithms themselves are verified by {@link TaffyFixtureTest}.)
 */
class FlexGridLayoutTest {
    @Test
    void looseTextInAFlexContainerBecomesAnAnonymousItem() {
        TestDoc t = new TestDoc();
        Element flex = t.div(t.body, "display: flex; width: 100px");
        t.text(flex, "  aa  ");
        Element b = t.div(flex, "width: 10px; order: -1");
        t.text(flex, "   ");
        t.layout();
        // The white-space-only run makes no item; order puts b first.
        assertEquals(2, flex.box.children.size());
        Box anonymous = flex.box.children.get(0);
        assertEquals(Box.Kind.ANONYMOUS, anonymous.kind);
        assertRect(b, 0, 0, 10, 9);
        assertRect(anonymous, 10, 0, 12, 9);
    }

    @Test
    void templateAreasPlaceItems() {
        TestDoc t = new TestDoc();
        Element grid = t.div(t.body, "display: grid; width: 100px; grid-template-columns: 30px 1fr; "
                + "grid-template-rows: 10px 20px; grid-template-areas: \"head head\" \"side main\"");
        Element main = t.div(grid, "grid-area: main");
        Element head = t.div(grid, "grid-area: head");
        Element side = t.div(grid, "grid-column: side-start / side-end; grid-row: 2");
        t.layout();
        assertRect(head, 0, 0, 100, 10);
        assertRect(side, 0, 10, 30, 20);
        assertRect(main, 30, 10, 70, 20);
    }

    @Test
    void autoFillRepeatsAndDensePacking() {
        TestDoc t = new TestDoc();
        Element grid = t.div(t.body, "display: grid; width: 100px; grid-template-columns: repeat(auto-fill, 30px); "
                + "grid-auto-rows: 10px; grid-auto-flow: row dense; column-gap: 5px");
        Element wide = t.div(grid, "grid-column: span 2");
        Element wider = t.div(grid, "grid-column: span 2");
        Element small = t.div(grid, "");
        Element last = t.div(grid, "grid-column: -2");
        t.layout();
        // Three 30px columns fit (30 + 5 + 30 + 5 + 30); the small item fills the hole dense packing leaves.
        assertRect(wide, 0, 0, 65, 10);
        assertRect(wider, 0, 10, 65, 10);
        assertRect(small, 70, 0, 30, 10);
        assertRect(last, 70, 10, 30, 10);
    }
}
