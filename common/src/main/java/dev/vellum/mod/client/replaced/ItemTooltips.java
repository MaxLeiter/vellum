package dev.vellum.mod.client.replaced;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * An {@code <item tooltip>}'s tooltip: the vanilla item tooltip, with any extra lines (the {@code title} that applies)
 * after the item's own in the same box. Item tooltips don't wrap, and neither do the extra lines. Render thread only.
 */
public final class ItemTooltips {
    /** Sets a tooltip for the frame from an item's lines, its tooltip image and style, as an item tooltip. */
    @FunctionalInterface
    public interface Setter {
        void set(GuiGraphicsExtractor g, Font font, List<Component> lines, Optional<TooltipComponent> image,
                 ItemStack stack, int x, int y, @Nullable Identifier style);
    }

    private static Setter setter = (g, font, lines, image, stack, x, y, style) ->
            g.setTooltipForNextFrame(font, lines, image, x, y, style, true);

    private ItemTooltips() {}

    /**
     * Replaces how a tooltip with extra lines is set. NeoForge's client entry point installs its overload that takes
     * the stack, so NeoForge's tooltip events see the item as they do for vanilla's item tooltips.
     */
    public static void install(Setter setter) {
        ItemTooltips.setter = setter;
    }

    /** Shows {@code stack}'s tooltip at GUI point ({@code x}, {@code y}) for this frame, with {@code extra} after it. */
    static void show(GuiGraphicsExtractor g, ItemStack stack, List<Component> extra, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        if (extra.isEmpty()) {
            g.setTooltipForNextFrame(mc.font, stack, x, y);
            return;
        }
        List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(mc, stack));
        lines.addAll(extra);
        setter.set(g, mc.font, lines, stack.getTooltipImage(), stack, x, y, stack.get(DataComponents.TOOLTIP_STYLE));
    }
}
