package dev.vellum.preview.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.vellum.engine.host.FontFamilies;
import dev.vellum.engine.host.FontMetrics;
import dev.vellum.engine.host.FontSpec;
import dev.vellum.engine.host.MinecraftGlyphs;
import dev.vellum.engine.paint.Canvas;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minecraft's bitmap fonts read from the client jar: metrics for layout and glyphs for drawing, both computed the
 * way vanilla's {@code BitmapProvider} and {@code Font} do. A font is the providers listed in
 * {@code assets/<ns>/font/<name>.json}: {@code reference}s are followed, {@code bitmap} and {@code space} providers
 * are loaded, and the first provider that has a code point wins. Code points that no provider covers (vanilla draws
 * those from unifont, which is not in the jar) and every glyph when there is no jar use a Java2D pixel font instead.
 */
public final class MinecraftFont implements FontMetrics {
    /**
     * A glyph in font units (px at size 8): its bitmap (null for blank glyphs), the size of one bitmap pixel, the
     * bitmap's top relative to the glyph box ({@code 7 - ascent}), and the advance.
     */
    public record Glyph(BufferedImage image, float pixelSize, float top, float advance) {}

    /** Receives a run of text from {@link #draw}, in GUI px relative to the text origin, with final colours. */
    public interface GlyphSink {
        /** Draws {@code image} (all of it) mapped into text space by {@code transform}, multiplied by {@code argb}. */
        void glyph(BufferedImage image, AffineTransform transform, int argb);

        /** Fills a rectangle (underline, strikethrough). */
        void rect(float x0, float y0, float x1, float y1, int argb);
    }

    /** Minecraft's default font at its native 8px. */
    public static final FontSpec NATIVE = new FontSpec(List.of(FontFamilies.DEFAULT), 8, false, false);
    private static final Font FALLBACK_FONT = new Font(Font.DIALOG, Font.PLAIN, 9);
    /** Rows of a fallback glyph bitmap, and the row its baseline sits on. */
    private static final int FALLBACK_HEIGHT = 12, FALLBACK_BASELINE = 9;

    /** A spec's glyphs, kept in its host slot: the first of its families this font has. */
    private record Resolved(MinecraftFont font, Map<Integer, Glyph> glyphs) {}

    private final MinecraftAssets assets;
    private final Map<String, Map<Integer, Glyph>> fonts = new ConcurrentHashMap<>();
    private final Map<Integer, Glyph> fallback = new ConcurrentHashMap<>();

    public MinecraftFont(MinecraftAssets assets) {
        this.assets = assets;
    }

    // ---- Metrics ----

    @Override
    public float width(String text, FontSpec font) {
        Map<Integer, Glyph> glyphs = glyphs(font);
        float width = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            width += advance(glyph(glyphs, cp), font.bold());
        }
        return width * font.scale();
    }

    @Override
    public float charWidth(int codePoint, FontSpec font) {
        return advance(glyph(glyphs(font), codePoint), font.bold()) * font.scale();
    }

    /** The glyph used for a code point in the first available family of {@code font}. */
    public Glyph glyph(int codePoint, FontSpec font) {
        return glyph(glyphs(font), codePoint);
    }

    private static float advance(Glyph glyph, boolean bold) {
        return glyph.advance() + (bold ? 1 : 0); // GlyphInfo.getBoldOffset
    }

    // ---- Drawing ----

    /**
     * Lays out one line of text the way vanilla's {@code Font.drawInBatch} does: the native shadow (RGB × 0.25, offset
     * 1px) under everything, bold as a second copy 1px to the right with 0.1px extra thickness, italic as a shear
     * of 0.25px per px, underline and strikethrough as 1px bars. {@code argb} is final (alpha already applied).
     */
    public void draw(String text, FontSpec font, int argb, int decorations, boolean shadow, GlyphSink sink) {
        if ((argb >>> 24) == 0) return; // vanilla skips fully transparent text
        Map<Integer, Glyph> glyphs = glyphs(font);
        if (shadow) drawPass(text, glyphs, font, scaleRgb(argb, 0.25f), decorations, 1, sink);
        drawPass(text, glyphs, font, argb, decorations, 0, sink);
    }

    private void drawPass(String text, Map<Integer, Glyph> glyphs, FontSpec font, int argb, int decorations,
                          float offset, GlyphSink sink) {
        float s = font.scale();
        float x = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            Glyph glyph = glyph(glyphs, cp);
            if (glyph.image() != null) {
                sink.glyph(glyph.image(), glyphTransform(glyph, x + offset, offset, font, s), argb);
                if (font.bold()) sink.glyph(glyph.image(), glyphTransform(glyph, x + offset + 1, offset, font, s), argb);
            }
            x += advance(glyph, font.bold());
        }
        if ((decorations & Canvas.STRIKETHROUGH) != 0) bar(sink, 3.5f, x, offset, s, argb);
        if ((decorations & Canvas.UNDERLINE) != 0) bar(sink, 8, x, offset, s, argb);
    }

    /** A 1px bar at {@code top}. Vanilla adds one per glyph, the first starting 1px early: together, [-1, width]. */
    private static void bar(GlyphSink sink, float top, float width, float offset, float s, int argb) {
        sink.rect((offset - 1) * s, (offset + top) * s, (offset + width) * s, (offset + top + 1) * s, argb);
    }

    /**
     * Maps a glyph bitmap onto its quad as {@code BakedSheetGlyph.render} builds it: the top edge sheared right by
     * {@code 1 - 0.25 * top}, the bottom by {@code 1 - 0.25 * bottom}, and bold glyphs grown 0.1px on every side.
     */
    private static AffineTransform glyphTransform(Glyph glyph, float x, float y, FontSpec font, float s) {
        int w = glyph.image().getWidth(), h = glyph.image().getHeight();
        float up = glyph.top(), down = up + h * glyph.pixelSize(), right = w * glyph.pixelSize();
        float extra = font.bold() ? 0.1f : 0;
        float shearTop = font.italic() ? 1 - 0.25f * up : 0, shearBottom = font.italic() ? 1 - 0.25f * down : 0;
        float scaleX = (right + 2 * extra) / w, shearX = (shearBottom - shearTop) / h, scaleY = (down - up + 2 * extra) / h;
        return new AffineTransform(scaleX * s, 0, shearX * s, scaleY * s, (x + shearTop - extra) * s, (y + up - extra) * s);
    }

    /** {@code ARGB.scaleRGB}: scales the colour channels, keeps alpha. */
    private static int scaleRgb(int argb, float f) {
        int r = (int) (((argb >> 16) & 0xFF) * f), g = (int) (((argb >> 8) & 0xFF) * f), b = (int) ((argb & 0xFF) * f);
        return (argb & 0xFF000000) | r << 16 | g << 8 | b;
    }

    // ---- Font loading ----

    private Map<Integer, Glyph> glyphs(FontSpec font) {
        if (font.hostFont() instanceof Resolved r && r.font == this) return r.glyphs;
        Map<Integer, Glyph> glyphs = glyphs(font.families());
        font.setHostFont(new Resolved(this, glyphs));
        return glyphs;
    }

    private Map<Integer, Glyph> glyphs(List<String> families) {
        for (String family : families) {
            Map<Integer, Glyph> glyphs = fonts.computeIfAbsent(FontFamilies.fontId(family), this::load);
            if (!glyphs.isEmpty()) return glyphs;
        }
        return fonts.computeIfAbsent(FontFamilies.DEFAULT, this::load);
    }

    private Glyph glyph(Map<Integer, Glyph> glyphs, int codePoint) {
        Glyph glyph = glyphs.get(codePoint);
        return glyph != null ? glyph : fallback.computeIfAbsent(codePoint, MinecraftFont::rasterize);
    }

    private Map<Integer, Glyph> load(String fontId) {
        Map<Integer, Glyph> glyphs = new HashMap<>();
        addProviders(fontId, glyphs, 0);
        return glyphs;
    }

    private void addProviders(String fontId, Map<Integer, Glyph> glyphs, int depth) {
        JsonObject definition = assets.json(MinecraftAssets.assetUrl(fontId, "font/", ".json")).orElse(null);
        if (definition == null || depth > 8) return;
        for (JsonElement element : definition.getAsJsonArray("providers")) {
            JsonObject provider = element.getAsJsonObject();
            if (!passesFilter(provider)) continue;
            switch (provider.get("type").getAsString()) {
                case "reference" -> addProviders(provider.get("id").getAsString(), glyphs, depth + 1);
                case "bitmap" -> addBitmap(provider, glyphs);
                case "space" -> provider.getAsJsonObject("advances").entrySet().forEach(e ->
                        glyphs.putIfAbsent(e.getKey().codePointAt(0), new Glyph(null, 1, 0, e.getValue().getAsFloat())));
                default -> {} // unihex and ttf: covered by the fallback font
            }
        }
    }

    /** A provider's {@code filter} must match the font options; the preview uses the defaults (all off). */
    private static boolean passesFilter(JsonObject provider) {
        JsonObject filter = provider.getAsJsonObject("filter");
        return filter == null || filter.entrySet().stream().noneMatch(e -> e.getValue().getAsBoolean());
    }

    /** {@code BitmapProvider.Definition.load}: a grid of glyph cells, each advancing its opaque width + 1. */
    private void addBitmap(JsonObject provider, Map<Integer, Glyph> glyphs) {
        String file = provider.get("file").getAsString();
        BufferedImage sheet = assets.texture(MinecraftAssets.assetUrl(file, "textures/", "")).map(Texture::image).orElse(null);
        if (sheet == null) return;
        List<int[]> rows = provider.getAsJsonArray("chars").asList().stream()
                .map(row -> row.getAsString().codePoints().toArray()).toList();
        int height = provider.has("height") ? provider.get("height").getAsInt() : 8;
        int ascent = provider.get("ascent").getAsInt();
        int cellWidth = sheet.getWidth() / rows.getFirst().length, cellHeight = sheet.getHeight() / rows.size();
        float pixelSize = (float) height / cellHeight;
        for (int row = 0; row < rows.size(); row++) {
            int[] chars = rows.get(row);
            for (int col = 0; col < chars.length; col++) {
                if (chars[col] == 0 || glyphs.containsKey(chars[col])) continue;
                BufferedImage cell = sheet.getSubimage(col * cellWidth, row * cellHeight, cellWidth, cellHeight);
                int advance = (int) (0.5f + opaqueWidth(cell) * pixelSize) + 1;
                glyphs.put(chars[col], new Glyph(cell, pixelSize, 7 - ascent, advance));
            }
        }
    }

    /** Columns up to and including the rightmost one with a non-transparent pixel. */
    private static int opaqueWidth(BufferedImage image) {
        for (int x = image.getWidth() - 1; x >= 0; x--) {
            for (int y = 0; y < image.getHeight(); y++) {
                if ((image.getRGB(x, y) >>> 24) != 0) return x + 1;
            }
        }
        return 0;
    }

    /**
     * Draws a code point with a small Java2D font, unsmoothed, on Minecraft's baseline, and sizes its advance the
     * way {@code BitmapProvider} does (ASCII uses Minecraft's own advances, so layout matches the game).
     */
    private static Glyph rasterize(int codePoint) {
        BufferedImage image = new BufferedImage(16, FALLBACK_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setFont(FALLBACK_FONT);
        g.setColor(Color.WHITE);
        g.drawString(Character.toString(codePoint), 0, FALLBACK_BASELINE);
        g.dispose();
        int width = opaqueWidth(image);
        float advance = MinecraftGlyphs.isAscii(codePoint) ? MinecraftGlyphs.asciiAdvance(codePoint) : width + 1;
        BufferedImage bitmap = width == 0 ? null : image.getSubimage(0, 0, width, FALLBACK_HEIGHT);
        return new Glyph(bitmap, 1, 7 - FALLBACK_BASELINE, advance);
    }
}
