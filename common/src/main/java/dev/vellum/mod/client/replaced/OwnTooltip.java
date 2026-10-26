package dev.vellum.mod.client.replaced;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Replaced content that draws the tooltip it shows ({@code ReplacedContent.showsTooltip}): the Minecraft elements and scenes. */
public interface OwnTooltip {
    /**
     * Shows the content's own tooltip for this frame at GUI point ({@code x}, {@code y}), followed by {@code extra}
     * (the lines of the {@code title} that applies), in one vanilla tooltip. Returns whether it set a tooltip.
     */
    boolean showTooltip(GuiGraphicsExtractor g, List<Component> extra, int x, int y);
}
