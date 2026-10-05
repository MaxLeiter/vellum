package dev.vellum.engine.layout;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.ReplacedContent;
import dev.vellum.engine.paint.Canvas;
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
        TestDoc t = new TestDoc(new ItemHost());
        Element p = t.div(t.body, "");
        Element item = t.add(p, "item", "");
        Element scaled = t.add(t.body, "item", "display: block; width: 32px");
        Element empty = t.add(t.body, "img", "display: block");
        Element attrs = t.add(t.body, "img", "display: block");
        attrs.setAttribute("width", "20");
        attrs.setAttribute("height", "10");
        Element fromHeight = t.add(t.body, "img", "display: block; height: 20px");
        fromHeight.setAttribute("width", "20");
        fromHeight.setAttribute("height", "10");
        Element canvas = t.add(t.body, "canvas", "display: block");
        t.layout();
        assertNotNull(item.replaced);
        assertEquals(Box.Kind.REPLACED, item.box.kind);
        assertTrue(item.box.atomicInline);
        // Inline replaced content sits on the baseline: 16 above it, 2 of descent below.
        assertRect(item, 0, 0, 16, 16);
        assertEquals(18, p.box.height, 0.01);
        assertRect(scaled, 0, 18, 32, 32);
        assertRect(empty, 0, 50, 0, 0);
        assertRect(attrs, 0, 50, 20, 10);
        assertRect(fromHeight, 0, 60, 40, 20);
        assertRect(canvas, 0, 80, 300, 150);
    }

    @Test
    void formControlsAreLeavesSizedByCss() {
        TestDoc t = new TestDoc();
        Element p = t.div(t.body, "");
        Element input = t.add(p, "input", "display: inline-block; width: 50px; height: 12px; padding: 2px");
        Element select = t.add(t.body, "select", "display: block; width: 40px; height: 10px");
        Element option = t.add(select, "option", "display: block");
        t.text(option, "one");
        t.layout();
        assertRect(input.box, 0, 0, 50, 12);
        assertTrue(select.box.children.isEmpty());
        assertNull(option.box);
        // A single-line control's baseline is its text's, centred in the content box: 2 + (8 - 9) / 2 + 7.
        assertEquals(8.5f, input.box.baseline, 0.01);
    }
}
