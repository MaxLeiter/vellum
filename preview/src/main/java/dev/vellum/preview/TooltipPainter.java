package dev.vellum.preview;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.vellum.engine.css.CssColors;
import dev.vellum.engine.host.FontMetrics;
import dev.vellum.engine.host.FontSpec;
import dev.vellum.engine.host.Host;
import dev.vellum.engine.input.Tooltip;
import dev.vellum.engine.paint.Canvas;
import dev.vellum.preview.render.MinecraftFont;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Draws a page's {@code title} tooltip ({@link Tooltip}) as Minecraft does: white shadowed lines, wrapped at 170 px
 * like widget tooltips, 12 px right of and above the pointer and kept on screen, over the {@code tooltip/background}
 * and {@code tooltip/frame} sprites. {@code title-json} is read as a chat component: text, translations, colours,
 * bold, italic, underline, strikethrough and {@code extra}; other components show as their text.
 */
final class TooltipPainter {
    private static final int WRAP = 170, LINE_HEIGHT = 10, MOUSE_OFFSET = 12, FRAME = 12;
    private static final int WHITE = 0xFFFFFFFF;

    /** A styled piece of text. */
    private record Run(String text, FontSpec font, int color, int decorations) {}

    /** Inherited chat style. */
    private record Style(int color, boolean bold, boolean italic, int decorations) {
        static final Style PLAIN = new Style(WHITE, false, false, 0);
    }

    private TooltipPainter() {}

    static void paint(Canvas canvas, Host host, Tooltip tooltip, float viewportWidth, float viewportHeight) {
        FontMetrics fonts = host.fonts();
        List<Run> runs = tooltip.json() != null ? component(host, tooltip.json()) : null;
        if (runs == null) runs = tooltip.text() == null ? List.of() : List.of(new Run(tooltip.text(), MinecraftFont.NATIVE, WHITE, 0));
        List<List<Run>> lines = wrap(fonts, runs);
        if (lines.isEmpty()) return;

        float width = 0;
        for (List<Run> line : lines) width = Math.max(width, width(fonts, line));
        int w = (int) Math.ceil(width), h = lines.size() * LINE_HEIGHT - 2;
        int x = (int) tooltip.x() + MOUSE_OFFSET, y = (int) tooltip.y() - MOUSE_OFFSET;
        if (x + w > viewportWidth) x = Math.max(x - 2 * MOUSE_OFFSET - w, 4);
        if (y + h + 3 > viewportHeight) y = (int) viewportHeight - h - 3;

        canvas.drawSprite("minecraft:tooltip/background", x - FRAME, y - FRAME, w + 2 * FRAME, h + 2 * FRAME, WHITE);
        canvas.drawSprite("minecraft:tooltip/frame", x - FRAME, y - FRAME, w + 2 * FRAME, h + 2 * FRAME, WHITE);
        for (int i = 0; i < lines.size(); i++) {
            float lx = x;
            for (Run run : lines.get(i)) {
                canvas.drawText(run.text, lx, y + i * LINE_HEIGHT, run.font, run.color, run.decorations, true);
                lx += fonts.width(run.text, run.font);
            }
        }
    }

    // ---- Wrapping ----

    /** Lines at {@code '\n'}, then at spaces before {@link #WRAP} px; a word wider than that is broken anywhere. */
    private static List<List<Run>> wrap(FontMetrics fonts, List<Run> runs) {
        List<List<Run>> lines = new ArrayList<>();
        List<Run> line = new ArrayList<>();
        float x = 0;
        for (Run run : runs) {
            String text = run.text;
            int i = 0;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == '\n') {
                    lines.add(line);
                    line = new ArrayList<>();
                    x = 0;
                    i++;
                    continue;
                }
                int end = i;
                if (c == ' ') {
                    while (end < text.length() && text.charAt(end) == ' ') end++;
                } else {
                    while (end < text.length() && text.charAt(end) != ' ' && text.charAt(end) != '\n') end++;
                }
                String piece = text.substring(i, end);
                float pw = fonts.width(piece, run.font);
                if (c == ' ') {
                    if (!line.isEmpty()) {
                        line.add(new Run(piece, run.font, run.color, run.decorations));
                        x += pw;
                    }
                } else {
                    if (x + pw > WRAP && x > 0) {
                        trimTrailingSpaces(line);
                        lines.add(line);
                        line = new ArrayList<>();
                        x = 0;
                    }
                    while (pw > WRAP && piece.length() > 1) { // a word wider than a line
                        int cut = piece.length() - 1;
                        while (cut > 1 && fonts.width(piece.substring(0, cut), run.font) > WRAP) cut--;
                        line.add(new Run(piece.substring(0, cut), run.font, run.color, run.decorations));
                        lines.add(line);
                        line = new ArrayList<>();
                        piece = piece.substring(cut);
                        pw = fonts.width(piece, run.font);
                    }
                    line.add(new Run(piece, run.font, run.color, run.decorations));
                    x += pw;
                }
                i = end;
            }
        }
        if (!line.isEmpty()) lines.add(line);
        return lines;
    }

    private static void trimTrailingSpaces(List<Run> line) {
        while (!line.isEmpty() && line.getLast().text.isBlank()) line.removeLast();
    }

    private static float width(FontMetrics fonts, List<Run> line) {
        float w = 0;
        for (Run run : line) w += fonts.width(run.text, run.font);
        return w;
    }

    // ---- Chat components ----

    /** The runs of a chat component in JSON, or null when it is not one. */
    private static List<Run> component(Host host, String json) {
        try {
            List<Run> runs = new ArrayList<>();
            append(host, JsonParser.parseString(json), Style.PLAIN, runs);
            return runs;
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException | ClassCastException e) {
            host.log(Host.LogLevel.WARN, "Bad title-json: " + e.getMessage());
            return null;
        }
    }

    private static void append(Host host, JsonElement e, Style inherited, List<Run> out) {
        if (e.isJsonPrimitive()) {
            add(e.getAsString(), inherited, out);
        } else if (e.isJsonArray()) {
            // As in Minecraft: the first element is the parent, the rest are its children.
            JsonArray array = e.getAsJsonArray();
            if (array.isEmpty()) return;
            JsonObject parent = array.get(0).isJsonObject() ? array.get(0).getAsJsonObject() : null;
            Style style = parent == null ? inherited : style(parent, inherited);
            append(host, array.get(0), inherited, out);
            for (int i = 1; i < array.size(); i++) append(host, array.get(i), style, out);
        } else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            Style style = style(o, inherited);
            if (o.has("text")) {
                add(o.get("text").getAsString(), style, out);
            } else if (o.has("translate")) {
                List<String> args = new ArrayList<>();
                if (o.has("with")) for (JsonElement arg : o.getAsJsonArray("with")) args.add(plain(host, arg));
                String key = o.get("translate").getAsString();
                String text = host.translate(key, args.toArray(String[]::new));
                if (text.equals(key) && o.has("fallback")) text = o.get("fallback").getAsString();
                add(text, style, out);
            } else if (o.has("keybind")) {
                add(o.get("keybind").getAsString(), style, out);
            }
            if (o.has("extra")) for (JsonElement child : o.getAsJsonArray("extra")) append(host, child, style, out);
        }
    }

    /** A component's text without styles (translation arguments). */
    private static String plain(Host host, JsonElement e) {
        List<Run> runs = new ArrayList<>();
        append(host, e, Style.PLAIN, runs);
        StringBuilder text = new StringBuilder();
        for (Run run : runs) text.append(run.text);
        return text.toString();
    }

    private static void add(String text, Style style, List<Run> out) {
        if (text.isEmpty()) return;
        FontSpec font = style.bold || style.italic
                ? new FontSpec(MinecraftFont.NATIVE.families(), MinecraftFont.NATIVE.size(), style.bold, style.italic)
                : MinecraftFont.NATIVE;
        out.add(new Run(text, font, style.color, style.decorations));
    }

    private static Style style(JsonObject o, Style parent) {
        int color = parent.color;
        if (o.has("color")) {
            String name = o.get("color").getAsString().toLowerCase(Locale.ROOT);
            Integer c = CssColors.parse(name.startsWith("#") ? name : "mc-" + name.replace('_', '-'), WHITE);
            if (c != null) color = c;
        }
        int decorations = parent.decorations;
        decorations = flag(o, "underlined", decorations, Canvas.UNDERLINE);
        decorations = flag(o, "strikethrough", decorations, Canvas.STRIKETHROUGH);
        boolean bold = o.has("bold") ? o.get("bold").getAsBoolean() : parent.bold;
        boolean italic = o.has("italic") ? o.get("italic").getAsBoolean() : parent.italic;
        return new Style(color, bold, italic, decorations);
    }

    private static int flag(JsonObject o, String name, int decorations, int bit) {
        if (!o.has(name)) return decorations;
        return o.get(name).getAsBoolean() ? decorations | bit : decorations & ~bit;
    }
}
