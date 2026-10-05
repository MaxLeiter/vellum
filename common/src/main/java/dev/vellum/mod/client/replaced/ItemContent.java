package dev.vellum.mod.client.replaced;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.vellum.engine.dom.Element;
import dev.vellum.mod.client.render.McCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * {@code <item id="minecraft:diamond_sword" count="1" components="{...}" tooltip>}: an item stack drawn scaled from
 * 16 px to the content box, with count and durability. {@code components} is SNBT for the stack's data components
 * (as in {@code /give}), parsed with the world's registries; parse errors fall back to the plain item. With the
 * {@code tooltip} attribute, hovering shows the vanilla item tooltip.
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
    public void draw(McCanvas canvas, Element element, float x, float y, float width, float height) {
        float size = Math.min(width, height);
        canvas.drawItem(stack, x + (width - size) / 2, y + (height - size) / 2, size, true);
        if (element.isHovered() && element.hasAttribute("tooltip")) canvas.itemTooltip(stack);
    }

    private ItemStack parse() {
        Identifier id = Identifier.tryParse(attr("id", "minecraft:air"));
        int count = Math.max(1, (int) number("count", 1));
        if (id == null) return ItemStack.EMPTY;
        var level = Minecraft.getInstance().level;
        String components = element.getAttribute("components");
        if (components != null && level != null) {
            try {
                CompoundTag tag = new CompoundTag();
                tag.putString("id", id.toString());
                tag.putInt("count", count);
                tag.put("components", TagParser.parseCompoundFully(components));
                var parsed = ItemStack.CODEC.parse(level.registryAccess().createSerializationContext(NbtOps.INSTANCE), tag).result();
                if (parsed.isPresent()) return parsed.get();
            } catch (CommandSyntaxException ignored) {
                // Malformed SNBT: show the plain item.
            }
        }
        return BuiltInRegistries.ITEM.getOptional(id).map(item -> new ItemStack(item, count)).orElse(ItemStack.EMPTY);
    }
}
