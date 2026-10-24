package dev.vellum.engine.testing;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.ReplacedContent;
import dev.vellum.engine.paint.Canvas;

/**
 * A 16x16 item as Minecraft's {@code <item>}: named by its {@code id}, with a tooltip of its own when it has
 * {@code tooltip}. Register it with {@code host.replaced.put("item", TestItem::new)}.
 */
public record TestItem(Element element) implements ReplacedContent {
    @Override public float intrinsicWidth() { return 16; }
    @Override public float intrinsicHeight() { return 16; }
    @Override public void paint(Canvas canvas, float x, float y, float width, float height) {}
    @Override public boolean showsTooltip() { return element.hasAttribute("tooltip"); }
    @Override public String accessibleName() {
        String id = element.getAttribute("id");
        return id == null || id.isEmpty() ? null : id;
    }
}
