package dev.vellum.mod.client;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.vellum.engine.css.CssColors;
import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.mod.Constants;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * {@code <mc-text>}: Minecraft text as ordinary inline content, so it wraps and inherits CSS like any text.
 * <ul>
 *   <li>{@code <mc-text key="item.minecraft.diamond" args="a,b">}: a translation, with comma-separated arguments;</li>
 *   <li>{@code <mc-text json='{"text":"Hi","color":"gold"}'>}: a chat component (text, translate, colours,
 *       bold, italic, underline, strikethrough, font).</li>
 * </ul>
 * Styled parts become {@code <span style>} children. Elements are expanded once, when the page loads.
 */
final class McText {
    private McText() {}

    static void expand(Document document) {
        Element root = document.documentElement();
        if (root == null) return;
        for (Element e : root.getElementsByTagName("mc-text")) {
            Component text = component(e);
            if (text == null) continue;
            e.removeAllChildren();
            text.visit((style, part) -> {
                if (style.isEmpty()) {
                    e.appendChild(document.createTextNode(part));
                } else {
                    Element span = e.appendChild(document.createElement("span"));
                    span.setAttribute("style", css(style));
                    span.appendChild(document.createTextNode(part));
                }
                return Optional.empty();
            }, Style.EMPTY);
        }
    }

    private static @Nullable Component component(Element e) {
        String key = e.getAttribute("key");
        if (key != null) {
            String args = e.getAttribute("args");
            return Component.translatable(key.strip(), (Object[]) (args == null || args.isBlank() ? new String[0] : args.split(",")));
        }
        String json = e.getAttribute("json");
        if (json == null) return null;
        try {
            var level = Minecraft.getInstance().level;
            var ops = level == null ? JsonOps.INSTANCE : level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
            return ComponentSerialization.CODEC.parse(ops, JsonParser.parseString(json)).result().orElse(null);
        } catch (RuntimeException ex) {
            Constants.LOG.warn("Vellum: bad <mc-text json>: {}", ex.toString());
            return null;
        }
    }

    private static String css(Style style) {
        StringBuilder css = new StringBuilder();
        if (style.getColor() != null) css.append("color:").append(CssColors.serialize(0xFF000000 | style.getColor().getValue())).append(';');
        if (style.isBold()) css.append("font-weight:bold;");
        if (style.isItalic()) css.append("font-style:italic;");
        if (style.isUnderlined() || style.isStrikethrough()) {
            css.append("text-decoration:").append(style.isUnderlined() ? "underline " : "").append(style.isStrikethrough() ? "line-through" : "").append(';');
        }
        if (style.getFont() instanceof FontDescription.Resource font && !font.equals(FontDescription.DEFAULT)) css.append("font-family:").append(font.id()).append(';');
        return css.toString();
    }
}
