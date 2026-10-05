package dev.vellum.mod.client.replaced;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.vellum.engine.dom.Element;
import dev.vellum.mod.client.render.McCanvas;
import dev.vellum.mod.client.render.Scene;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * {@code <model block="minecraft:oak_stairs[facing=east]">} or {@code <model item="minecraft:diamond_sword">}: a
 * block (any block state, in {@code /setblock} syntax) or an item ({@code count} and {@code components} as on
 * {@code <item>}) drawn in 3D, centred in the content box. At {@code -mc-yaw}/{@code -mc-pitch} 0 it looks as in the
 * inventory (blocks in the three-quarter view, flat items face on, at the size an item fills its slot);
 * {@code -mc-yaw} turns it, {@code -mc-pitch} views it from further above, {@code -mc-model-scale} sizes it, and
 * {@code rotatable} lets the pointer turn it. Blocks without a model (fluids, air) draw nothing.
 */
final class ModelContent extends TurnableContent {
    private static final BlockDisplayContext DISPLAY = BlockDisplayContext.create();

    private @Nullable BlockState block;
    private ItemStack item = ItemStack.EMPTY;
    private final BlockModelRenderState blockModel = new BlockModelRenderState();

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
        Scene scene = scene();
        if (scene != null) canvas.drawScene(scene, Math.min(width, height) * modelScale(), tint(), x, y, width, height);
    }

    private void load() {
        String state = element.getAttribute("block");
        block = null;
        if (state != null && !state.isBlank()) {
            try {
                block = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, state.strip(), false).blockState();
            } catch (CommandSyntaxException ignored) {
                // An unknown block or property: nothing to draw.
            }
        }
        item = block != null ? ItemStack.EMPTY : ItemStacks.parse(element.getAttribute("item"),
                Math.max(1, (int) element.numberAttribute("count", 1)), element.getAttribute("components"));
    }

    /** This frame's model (items animate, and models change with resource packs), or null when there is none. */
    private @Nullable Scene scene() {
        Minecraft mc = Minecraft.getInstance();
        if (block != null) {
            new BlockModelResolver(mc.getModelManager()).update(blockModel, block, DISPLAY);
            if (blockModel.isEmpty()) return null;
            return Scene.block(this, blockModel, mc.getModelManager().getBlockModelSet().get(block), yaw(), pitch());
        }
        if (item.isEmpty()) return null;
        TrackingItemStackRenderState state = new TrackingItemStackRenderState();
        mc.getItemModelResolver().updateForTopItem(state, item, ItemDisplayContext.GUI, mc.level, null, 0);
        return state.isEmpty() ? null : Scene.item(this, state, yaw(), pitch());
    }
}
