package dev.vellum.mod.client;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.vellum.engine.css.CssColors;
import dev.vellum.engine.host.Host;
import dev.vellum.mod.Constants;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
//? if >=26
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Chat components for {@code <mc-text json>} ({@link Host#formatText}): the component's text (translations
 * resolved), as runs styled with CSS for its colour, bold, italic, underline, strikethrough and font. The engine
 * turns the runs into the element's children. {@code title-json} tooltips parse the same way ({@link #component}), and
 * come apart into lines at newlines ({@link #lines}).
 */
final class McText {
    private McText() {}

    /** The runs of a chat component in JSON, or null when it is malformed. */
    static @Nullable List<Host.TextRun> runs(String json) {
        Component text = component(json);
        if (text == null) return null;
        List<Host.TextRun> runs = new ArrayList<>();
        text.visit((style, part) -> {
            runs.add(new Host.TextRun(part, css(style)));
            return Optional.empty();
        }, Style.EMPTY);
        return runs;
    }

    /** A chat component from JSON (with the world's registries when there is one), or null when it is malformed. */
    static @Nullable Component component(String json) {
        try {
            var level = Minecraft.getInstance().level;
            var ops = level == null ? JsonOps.INSTANCE : level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
            return ComponentSerialization.CODEC.parse(ops, JsonParser.parseString(json)).result().orElse(null);
        } catch (RuntimeException e) {
            Constants.LOG.warn("Vellum: bad <mc-text json>: {}", e.toString());
            return null;
        }
    }

    /** The lines of {@code text}, split at its newlines; each keeps the styles of its parts. */
    static List<Component> lines(Component text) {
        List<Component> lines = new ArrayList<>();
        MutableComponent[] line = {Component.empty()};
        text.visit((style, part) -> {
            int start = 0;
            for (int end; (end = part.indexOf('\n', start)) >= 0; start = end + 1) {
                if (end > start) line[0].append(Component.literal(part.substring(start, end)).withStyle(style));
                lines.add(line[0]);
                line[0] = Component.empty();
            }
            if (start < part.length()) line[0].append(Component.literal(part.substring(start)).withStyle(style));
            return Optional.empty();
        }, Style.EMPTY);
        lines.add(line[0]);
        return lines;
    }

    private static String css(Style style) {
        StringBuilder css = new StringBuilder();
        if (style.getColor() != null) css.append("color:").append(CssColors.serialize(0xFF000000 | style.getColor().getValue())).append(';');
        if (style.isBold()) css.append("font-weight:bold;");
        if (style.isItalic()) css.append("font-style:italic;");
        if (style.isUnderlined() || style.isStrikethrough()) {
            css.append("text-decoration:").append(style.isUnderlined() ? "underline " : "").append(style.isStrikethrough() ? "line-through" : "").append(';');
        }
        //? if >=26 {
        if (style.getFont() instanceof FontDescription.Resource font && !font.equals(FontDescription.DEFAULT)) css.append("font-family:").append(font.id()).append(';');
        //?} else
        //if (!style.getFont().equals(Style.DEFAULT_FONT)) css.append("font-family:").append(style.getFont()).append(';');
        return css.toString();
    }
}
