package dev.vellum.engine.host;

import java.util.Locale;
import java.util.Map;

/**
 * Font family names and the Minecraft fonts they mean: the one mapping the style engine and every host share, so a
 * family resolves to the same font in game, in the previewer and in tests.
 *
 * <p>CSS generic families stand for Minecraft's fonts ({@code monospace} is {@code minecraft:uniform}, the others
 * {@code minecraft:default}); other names are font ids ({@code assets/<ns>/font/<id>.json}), read as Minecraft reads
 * ids: without a namespace they are {@code minecraft:}.
 */
public final class FontFamilies {
    public static final String DEFAULT = "minecraft:default";
    public static final String UNIFORM = "minecraft:uniform";

    private static final Map<String, String> GENERIC = Map.of("monospace", UNIFORM, "ui-monospace", UNIFORM,
            "serif", DEFAULT, "sans-serif", DEFAULT, "system-ui", DEFAULT, "ui-serif", DEFAULT, "ui-sans-serif", DEFAULT,
            "cursive", DEFAULT, "fantasy", DEFAULT);

    private FontFamilies() {}

    /** The computed value of a family name: a generic family becomes its Minecraft font; other names are kept. */
    public static String computed(String family) {
        return GENERIC.getOrDefault(family.toLowerCase(Locale.ROOT), family);
    }

    /** The id of the font a host loads for a family: {@code ns:path}, lower case. */
    public static String fontId(String family) {
        String id = computed(family.strip()).toLowerCase(Locale.ROOT);
        return id.indexOf(':') > 0 ? id : "minecraft:" + id;
    }
}
