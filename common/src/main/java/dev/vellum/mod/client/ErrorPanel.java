package dev.vellum.mod.client;

import dev.vellum.mod.client.render.McGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * What a Vellum page shows instead of crashing when it fails to load or the engine throws: the problem, the
 * exception and the top of its stack, drawn with plain vanilla calls so it works whatever state the engine is in.
 * Screens get a centred panel; HUD overlays a single line in the corner, so the game stays playable.
 */
record ErrorPanel(String title, List<String> details) {
    private static final int MAX_LINES = 10, PAD = 6, LINE = 10, GAP = 4;
    private static final String HINT = "Esc closes this screen; /vellum reload tries again.";

    static ErrorPanel of(String title, @Nullable Throwable error) {
        List<String> details = new ArrayList<>();
        for (Throwable t = error; t != null && details.size() < MAX_LINES; t = t.getCause()) {
            details.add((t == error ? "" : "Caused by: ") + t);
            for (StackTraceElement f : t.getStackTrace()) {
                if (details.size() >= MAX_LINES) break;
                // Without "at" and the module prefix (TRANSFORMER/vellum@0.1.0/...), which only make lines wrap.
                details.add("  " + f.getClassName() + "." + f.getMethodName() + "(" + f.getFileName() + ":" + f.getLineNumber() + ")");
            }
        }
        return new ErrorPanel(title, details);
    }

    void extract(GuiGraphicsExtractor g, int width, int height, boolean screen) {
        Font font = Minecraft.getInstance().font;
        if (!screen) {
            String line = details.isEmpty() ? title : title + ": " + details.getFirst();
            int w = Math.min(font.width(line), width - 8 - 2 * PAD);
            McGui.fill(g, 4, 4, 4 + w + 2 * PAD, 4 + LINE + PAD, 0xC0200808);
            McGui.text(g, font, font.plainSubstrByWidth(line, w), 4 + PAD, 4 + PAD / 2 + 1, 0xFFFF6B6B, false);
            return;
        }
        int w = Math.min(width - 16, 420), inner = w - 2 * PAD, x = (width - w) / 2;
        List<FormattedCharSequence> head = font.split(Component.literal(title), inner);
        List<FormattedCharSequence> body = new ArrayList<>();
        for (String d : details) body.addAll(font.split(Component.literal(d), inner));
        List<FormattedCharSequence> hint = font.split(Component.literal(HINT), inner);
        int h = (head.size() + body.size() + hint.size()) * LINE + 2 * PAD + 2 * GAP;
        int y = Math.max(4, (height - h) / 2);
        McGui.fill(g, x, y, x + w, y + h, 0xF0200808);
        McGui.outline(g, x, y, w, h, 0xFFB03030);
        int ty = y + PAD;
        ty = lines(g, font, head, x + PAD, ty, 0xFFFF6B6B) + GAP;
        ty = lines(g, font, body, x + PAD, ty, 0xFFD0D0D0) + GAP;
        lines(g, font, hint, x + PAD, ty, 0xFF909090);
    }

    private static int lines(GuiGraphicsExtractor g, Font font, List<FormattedCharSequence> lines, int x, int y, int color) {
        for (FormattedCharSequence line : lines) {
            McGui.text(g, font, line, x, y, color, false);
            y += LINE;
        }
        return y;
    }
}
