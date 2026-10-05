package dev.vellum.mod.client.replaced;

import dev.vellum.engine.dom.Element;
import dev.vellum.mod.client.render.McCanvas;
import net.minecraft.world.item.ItemStack;

/**
 * {@code <item id="minecraft:diamond_sword" count="1" components="{...}" tooltip>}: an item stack drawn scaled from
 * 16 px to the content box, with count and durability. {@code components} is SNBT for the stack's data components
 * ({@link ItemStacks#parse}). With the {@code tooltip} attribute, hovering shows the vanilla item tooltip.
 */
final class ItemContent extends McReplaced {
    private ItemStack stack;

    ItemContent(Element element) {
        super(element);
        this.stack = parse();
    }

    @Override
    public float intrinsicWidth() {
        return 16;
    }

    @Override
    public float intrinsicHeight() {
        return 16;
    }

    @Override
    public void attributeChanged(String name) {
        if (name.equals("id") || name.equals("count") || name.equals("components")) stack = parse();
    }

    @Override
    protected void draw(McCanvas canvas, float x, float y, float width, float height) {
        float size = Math.min(width, height);
        canvas.drawItem(stack, x + (width - size) / 2, y + (height - size) / 2, size, true);
        if (element.isHovered() && element.hasAttribute("tooltip")) canvas.itemTooltip(stack);
    }

    private ItemStack parse() {
        return ItemStacks.parse(element.getAttribute("id"), Math.max(1, (int) element.numberAttribute("count", 1)),
                element.getAttribute("components"));
    }
}
