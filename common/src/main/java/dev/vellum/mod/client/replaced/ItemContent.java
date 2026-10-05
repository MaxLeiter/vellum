package dev.vellum.mod.client.replaced;

import dev.vellum.engine.dom.Element;
import dev.vellum.mod.client.render.McCanvas;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * {@code <item id="minecraft:diamond_sword" count="1" components="{...}" tooltip>}: an item stack drawn scaled from
 * 16 px to the square {@code object-fit: contain} fits in the content box (placed by {@code object-position}), with
 * count and durability. {@code components} is SNBT for the stack's data components
 * ({@link ItemStacks#of}). With the {@code tooltip} attribute, hovering shows the vanilla item tooltip, with the lines
 * of the {@code title} that applies after the item's own ({@link ItemTooltips}).
 */
final class ItemContent extends McReplaced {
    private ItemStack stack;
    private boolean tooltip;

    ItemContent(Element element) {
        super(element);
        this.stack = ItemStacks.of(element, "id");
        this.tooltip = element.hasAttribute("tooltip");
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
        if (name.equals("id") || name.equals("count") || name.equals("components")) stack = ItemStacks.of(element, "id");
        if (name.equals("tooltip")) tooltip = element.hasAttribute("tooltip");
    }

    @Override
    public boolean showsTooltip() {
        return tooltip && !stack.isEmpty();
    }

    @Override
    public boolean showTooltip(GuiGraphicsExtractor g, List<Component> extra, int x, int y) {
        if (stack.isEmpty()) return false;
        ItemTooltips.show(g, stack, extra, x, y);
        return true;
    }

    /** Fills the box, which {@code object-fit: contain} (the UA's) makes a square placed by {@code object-position}. */
    @Override
    protected void draw(McCanvas canvas, float x, float y, float width, float height) {
        canvas.drawItem(stack, x, y, Math.min(width, height), true);
    }
}
