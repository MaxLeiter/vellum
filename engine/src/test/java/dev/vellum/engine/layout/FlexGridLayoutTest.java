package dev.vellum.engine.layout;

import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
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
        Page page = new TestHost().load(
                "<div id=flex style='display: flex; width: 100px'>  aa  <div id=b style='width: 10px; order: -1'></div>   </div>");
        Box flex = page.byId("flex").box;
        // The white-space-only run makes no item; order puts b first.
        assertEquals(2, flex.children.size());
        Box anonymous = flex.children.get(0);
        assertEquals(Box.Kind.ANONYMOUS, anonymous.kind);
        assertRect(page.byId("b"), 0, 0, 10, 9);
        assertRect(anonymous, 10, 0, 12, 9);
    }

    @Test
    void templateAreasPlaceItems() {
        Page page = new TestHost().load("""
                <div style='display: grid; width: 100px; grid-template-columns: 30px 1fr; grid-template-rows: 10px 20px;
                            grid-template-areas: "head head" "side main"'>
                  <div id=main style="grid-area: main"></div>
                  <div id=head style="grid-area: head"></div>
                  <div id=side style="grid-column: side-start / side-end; grid-row: 2"></div>
                </div>""");
        assertRect(page.byId("head"), 0, 0, 100, 10);
        assertRect(page.byId("side"), 0, 10, 30, 20);
        assertRect(page.byId("main"), 30, 10, 70, 20);
    }

    @Test
    void autoFillRepeatsAndDensePacking() {
        Page page = new TestHost().load("""
                <div style="display: grid; width: 100px; grid-template-columns: repeat(auto-fill, 30px); grid-auto-rows: 10px;
                            grid-auto-flow: row dense; column-gap: 5px">
                  <div id=wide style="grid-column: span 2"></div>
                  <div id=wider style="grid-column: span 2"></div>
                  <div id=small></div>
                  <div id=last style="grid-column: -2"></div>
                </div>""");
        // Three 30px columns fit (30 + 5 + 30 + 5 + 30); the small item fills the hole dense packing leaves.
        assertRect(page.byId("wide"), 0, 0, 65, 10);
        assertRect(page.byId("wider"), 0, 10, 65, 10);
        assertRect(page.byId("small"), 70, 0, 30, 10);
        assertRect(page.byId("last"), 70, 10, 30, 10);
    }

    @Test
    void percentHeightsResolveAgainstAnItemsOwnHeight() {
        // An item's own definite height is a percentage basis for its children (§9.8), in rows and columns alike,
        // and so is a grid item's.
        Page page = new TestHost().load("""
                <div style="display: flex"><div style="height: 30px; padding: 2px"><div id=row style="height: 100%"></div></div></div>
                <div style="display: flex; align-items: flex-start"><div style="width: 100px; height: 6px"><div id=start style="height: 100%; width: 50%"></div></div></div>
                <div style="display: flex; flex-direction: column"><div style="height: 20px"><div id=column style="height: 50%"></div></div></div>
                <div style="display: grid; align-items: start"><div style="height: 10px"><div id=grid style="height: 100%"></div></div></div>""");
        assertEquals(26, page.byId("row").box.height, 0.01);
        assertRect(page.byId("start"), 0, 30, 50, 6);
        assertEquals(10, page.byId("column").box.height, 0.01);
        assertEquals(10, page.byId("grid").box.height, 0.01);
    }
}
