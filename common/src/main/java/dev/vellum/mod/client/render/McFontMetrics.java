package dev.vellum.mod.client.render;

import dev.vellum.engine.host.FontMetrics;
import dev.vellum.engine.host.FontSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Text measurement with Minecraft's font, and the {@link Style} that both measurement and {@link McCanvas#drawText}
 * use for a {@link FontSpec}, so laid-out text and painted text always agree (bold glyphs are wider).
 *
 * <p>Families: {@code minecraft:default | uniform | alt | illageralt}, any {@code namespace:id} with an
 * {@code assets/<ns>/font/<id>.json}, and the CSS generics {@code monospace} (uniform) and {@code sans-serif},
 * {@code serif}, {@code system-ui} (default). Unknown families fall through to the next; the last resort is the
 * default font. Render thread only.
 */
public final class McFontMetrics implements FontMetrics {
    public static final McFontMetrics INSTANCE = new McFontMetrics();

    private static final FontDescription UNIFORM = new FontDescription.Resource(Identifier.withDefaultNamespace("uniform"));
    private static final Map<String, FontDescription> GENERIC = Map.of(
            "monospace", UNIFORM,
            "sans-serif", FontDescription.DEFAULT,
            "serif", FontDescription.DEFAULT,
            "system-ui", FontDescription.DEFAULT);

    private final Map<FontSpec, Style> styles = new HashMap<>();
    private final Map<Identifier, Boolean> knownFonts = new HashMap<>();

    private McFontMetrics() {}

    @Override
    public float width(String text, FontSpec font) {
        return Minecraft.getInstance().font.getSplitter().stringWidth(FormattedText.of(text, style(font))) * font.scale();
    }

    /** The style for a font request: font resource, bold and italic. Colour and decorations are added by the caller. */
    public Style style(FontSpec font) {
        Style style = styles.get(font);
        if (style == null) {
            style = Style.EMPTY.withFont(resolve(font.families())).withBold(font.bold()).withItalic(font.italic());
            styles.put(font, style);
        }
        return style;
    }

    /** Forgets resolved fonts; resource packs may have added or removed some. */
    public void clearCache() {
        styles.clear();
        knownFonts.clear();
    }

    private FontDescription resolve(List<String> families) {
        for (String family : families) {
            String name = family.strip().toLowerCase(Locale.ROOT);
            FontDescription generic = GENERIC.get(name);
            if (generic != null) return generic;
            Identifier id = name.indexOf(':') > 0 ? Identifier.tryParse(name) : null;
            if (id != null && knownFonts.computeIfAbsent(id, McFontMetrics::exists)) {
                return id.equals(FontDescription.DEFAULT.id()) ? FontDescription.DEFAULT : new FontDescription.Resource(id);
            }
        }
        return FontDescription.DEFAULT;
    }

    private static boolean exists(Identifier font) {
        Identifier file = Identifier.fromNamespaceAndPath(font.getNamespace(), "font/" + font.getPath() + ".json");
        return Minecraft.getInstance().getResourceManager().getResource(file).isPresent();
    }
}
