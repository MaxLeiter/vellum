package dev.vellum.preview.host;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.FontSpec;
import dev.vellum.engine.host.ReplacedContent;
import dev.vellum.engine.paint.Canvas;
import dev.vellum.preview.render.MinecraftAssets;
import dev.vellum.preview.render.MinecraftFont;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewHostTest {
    @TempDir
    Path root;

    private PreviewHost host(List<Path> packs) {
        MinecraftAssets assets = MinecraftAssets.open(packs, Optional.empty());
        return new PreviewHost(assets, new MinecraftFont(assets));
    }

    @Test
    void pagesUnderAssetsAreAddressedByIdAndLoadFromTheirPack() throws IOException {
        Path page = root.resolve("assets/vellum/vellum/demo/menu.html");
        Files.createDirectories(page.getParent());
        Files.writeString(page, "<p>menu");
        Files.writeString(page.resolveSibling("menu.css"), "p {}");

        assertEquals("vellum:vellum/demo/menu.html", PreviewHost.pageUrl(page));
        assertEquals(Optional.of(root.toAbsolutePath()), PreviewHost.packRoot(page));
        PreviewHost host = host(List.of(root));
        assertEquals("vellum:vellum/demo/menu.css", host.resolveUrl(PreviewHost.pageUrl(page), "menu.css"));
        assertEquals("p {}", host.loadText("vellum:vellum/demo/menu.css"));
        assertTrue(host.loadedFiles().contains(page.resolveSibling("menu.css")));
    }

    @Test
    void otherPagesUseFilePaths() throws IOException {
        Path page = root.resolve("pages/index.html");
        Path css = root.resolve("css/site.css");
        Files.createDirectories(css.getParent());
        Files.writeString(css, "body {}");

        String url = PreviewHost.pageUrl(page);
        assertEquals(page.toAbsolutePath().toString(), url);
        assertEquals(Optional.empty(), PreviewHost.packRoot(page));
        PreviewHost host = host(List.of());
        assertEquals(css.toAbsolutePath().toString(), host.resolveUrl(url, "../css/site.css"));
        assertEquals("minecraft:textures/a.png", host.resolveUrl(url, "minecraft:textures/a.png"));
        assertEquals("body {}", host.loadText(host.resolveUrl(url, "../css/site.css")));
        assertNull(host.loadText(host.resolveUrl(url, "missing.css")));
    }

    @Test
    void itemCountsAreNumbersAsInGame() {
        // As ItemStacks.of in game: a number, at least 1; vanilla draws no count of 1.
        PreviewHost host = host(List.of());
        Document document = Document.create(host, "test");
        Map<String, String> drawn = new LinkedHashMap<>();
        for (String count : new String[] {"5", "07", "2.9", "abc", "0", "1", " 12 "}) {
            Element item = document.createElement("item");
            item.setAttribute("id", "minecraft:stone");
            item.setAttribute("count", count);
            List<String> texts = new ArrayList<>();
            document.replacedContent(item).paint(textRecorder(texts), 0, 0, 16, 16);
            drawn.put(count, String.join(",", texts));
        }
        assertEquals(Map.of("5", "5", "07", "7", "2.9", "2", "abc", "", "0", "", "1", "", " 12 ", "12"), drawn);
    }

    /** A canvas that only records the text drawn on it. */
    private static Canvas textRecorder(List<String> texts) {
        return new Canvas() {
            @Override public void save() {}
            @Override public void restore() {}
            @Override public int saveCount() { return 0; }
            @Override public void translate(float dx, float dy) {}
            @Override public void transform(float a, float b, float c, float d, float e, float f) {}
            @Override public void multiplyAlpha(float alpha) {}
            @Override public void clipRect(float x, float y, float width, float height) {}
            @Override public void fillRect(float x, float y, float width, float height, int argb) {}
            @Override public void fillQuads(float[] xy, int[] colors, int quadCount) {}
            @Override public void drawText(String text, float x, float y, FontSpec font, int argb, int decorations, boolean shadow) {
                texts.add(text);
            }
            @Override public void drawImage(String url, float x, float y, float width, float height,
                                            float u0, float v0, float u1, float v1, int tint, boolean smooth) {}
            @Override public void drawSprite(String spriteId, float x, float y, float width, float height, int tint) {}
        };
    }

    @Test
    void replacedElementsHaveMinecraftSizes() {
        PreviewHost host = host(List.of());
        Document document = Document.create(host, "test");
        assertEquals(List.of(16f, 16f), size(document, document.createElement("item")));
        assertEquals(List.of(18f, 18f), size(document, document.createElement("slot")));
        assertEquals(List.of(48f, 48f), size(document, document.createElement("entity")));
        assertEquals(List.of(32f, 32f), size(document, document.createElement("model")));
        assertEquals(List.of(16f, 16f), size(document, document.createElement("player-head")));
        assertEquals(List.of(Float.NaN, Float.NaN), size(document, document.createElement("scene")), "sized by CSS");
        Element canvas = document.createElement("canvas");
        canvas.setAttribute("width", "64");
        assertEquals(List.of(64f, 150f), size(document, canvas));
        Element img = document.createElement("img");
        img.setAttribute("src", "minecraft:textures/missing.png");
        assertTrue(Float.isNaN(document.replacedContent(img).intrinsicWidth()));
        assertEquals(Set.of("item", "slot", "entity", "model", "player-head", "scene"), host.replacedElements().keySet());
        assertNull(document.replacedContent(document.createElement("div")));
    }

    private static List<Float> size(Document document, Element element) {
        ReplacedContent content = document.replacedContent(element);
        return List.of(content.intrinsicWidth(), content.intrinsicHeight());
    }
}
