package dev.vellum.engine.layout;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.ReplacedContent;
import dev.vellum.engine.paint.Canvas;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static dev.vellum.engine.layout.BlockLayoutTest.assertRect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Replaced elements sized from host content or attributes, and form controls as CSS-sized leaves. */
class ReplacedLayoutTest {
    /** A host whose {@code <item>} elements are 16x16 replaced content. */
    private static final class ItemHost extends TestHost {
        @Override
        public Map<String, Function<Element, ReplacedContent>> replacedElements() {
            return Map.of("item", element -> new ReplacedContent() {
                public float intrinsicWidth() { return 16; }
                public float intrinsicHeight() { return 16; }
                public void paint(Canvas canvas, float x, float y, float width, float height) {}
            });
        }
    }

    @Test
    void naturalSizeRatioAndAttributes() {
        Page page = new ItemHost().load("""
                <style>item { width: auto; height: auto }</style>
                <div id=p><item id=item></item></div>
                <item id=scaled style="display: block; width: 32px"></item>
                <img id=empty style="display: block">
                <img id=attrs width=20 height=10 style="display: block">
                <img id=fromHeight width=20 height=10 style="display: block; height: 20px">
                <canvas id=canvas style="display: block"></canvas>""");
        Element item = page.byId("item");
        assertNotNull(item.replaced);
        assertEquals(Box.Kind.REPLACED, item.box.kind);
        assertTrue(item.box.atomicInline);
        // Inline replaced content sits on the baseline: 16 above it, 2 of descent below.
        assertRect(item, 0, 0, 16, 16);
        assertEquals(18, page.byId("p").box.height, 0.01);
        assertRect(page.byId("scaled"), 0, 18, 32, 32);
        assertRect(page.byId("empty"), 0, 50, 0, 0);
        assertRect(page.byId("attrs"), 0, 50, 20, 10);
        assertRect(page.byId("fromHeight"), 0, 60, 40, 20);
        assertRect(page.byId("canvas"), 0, 80, 300, 150);
    }

    @Test
    void formControlsAreLeavesSizedByCss() {
        Page page = new TestHost().load("""
                <div><input id=input style="width: 50px; height: 12px; padding: 2px"></div>
                <select id=select style="display: block; width: 40px; height: 10px"><option id=option style="display: block">one</select>""");
        Box input = page.byId("input").box, select = page.byId("select").box;
        assertRect(input, 0, 0, 50, 12);
        assertTrue(select.children.isEmpty());
        assertNull(page.byId("option").box);
        // A single-line control's baseline is its text's, centred in the content box: 2 + (8 - 9) / 2 + 7.
        assertEquals(8.5f, input.baseline, 0.01);
    }
}
