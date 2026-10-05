package dev.vellum.mod.client.replaced;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.vellum.engine.dom.Element;
import dev.vellum.mod.client.render.McCanvas;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code <model block="minecraft:oak_stairs[facing=east]">} or {@code <model item="minecraft:diamond_sword">}. On
 * Minecraft 1.21.1 Vellum does not draw models in 3D yet: the model's square ({@code -mc-model-scale} and
 * {@code object-position} place it as on 26.x) shows the item's inventory icon, or the block's item. Blocks without an
 * item (fluids, air) draw nothing, and {@code -mc-yaw}, {@code -mc-pitch} and {@code -mc-tint} have no effect.
 */
final class ModelContent extends TurnableContent {
    private ItemStack item = ItemStack.EMPTY;

    ModelContent(Element element) {
        super(element);
        load();
    }

    @Override
    public float intrinsicWidth() {
        return 32;
    }

    @Override
    public float intrinsicHeight() {
        return 32;
    }

    @Override
    public void attributeChanged(String name) {
        if (name.equals("block") || name.equals("item") || name.equals("count") || name.equals("components")) load();
    }

    @Override
    protected void draw(McCanvas canvas, float x, float y, float width, float height) {
        if (item.isEmpty()) return;
        float[] square = element.computedStyle().objectSquare(x, y, width, height, modelScale());
        if (square[2] > 0) canvas.drawItem(item, square[0], square[1], square[2], false);
    }

    private void load() {
        String state = attr("block", "");
        BlockState block = null;
        if (!state.isEmpty()) {
            try {
                block = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), state, false).blockState();
            } catch (CommandSyntaxException ignored) {
                // An unknown block or property: nothing to draw.
            }
        }
        item = block != null ? new ItemStack(block.getBlock()) : ItemStacks.of(element, "item");
    }
}
