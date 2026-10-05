package dev.vellum.mod.client.replaced;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * An {@code <item tooltip>}'s tooltip: the vanilla item tooltip, with any extra lines (the {@code title} that applies)
 * after the item's own in the same box. Item tooltips don't wrap, and neither do the extra lines. Render thread only.
 */
public final class ItemTooltips {
    /**
     * Sets a tooltip for the frame from an item's lines, as an item tooltip: with the stack's tooltip image and
     * style, and the gap after its name.
     */
    @FunctionalInterface
    public interface Setter {
        void set(GuiGraphicsExtractor g, Font font, List<Component> lines, ItemStack stack, int x, int y);
    }

    private static Setter setter = (g, font, lines, stack, x, y) ->
            g.setTooltipForNextFrame(font, lines, stack.getTooltipImage(), x, y, stack.get(DataComponents.TOOLTIP_STYLE), true);

    private ItemTooltips() {}

    /**
     * Replaces how the tooltip is set. NeoForge's client entry point installs its overload that takes the stack, so
     * NeoForge's tooltip events see the item as they do for vanilla's item tooltips.
     */
    public static void install(Setter setter) {
        ItemTooltips.setter = setter;
    }

    /**
     * Shows {@code stack}'s tooltip at GUI point ({@code x}, {@code y}) for this frame, with {@code extra} after it.
     * Without extra lines it is vanilla's item tooltip ({@code setTooltipForNextFrame(Font, ItemStack, int, int)}).
     */
    static void show(GuiGraphicsExtractor g, ItemStack stack, List<Component> extra, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(mc, stack));
        lines.addAll(extra);
        setter.set(g, mc.font, lines, stack, x, y);
    }
}
