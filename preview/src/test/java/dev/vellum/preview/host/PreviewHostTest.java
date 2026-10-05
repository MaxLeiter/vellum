package dev.vellum.preview.host;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.ReplacedContent;
import dev.vellum.preview.render.MinecraftAssets;
import dev.vellum.preview.render.MinecraftFont;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    void replacedElementsHaveMinecraftSizes() {
        PreviewHost host = host(List.of());
        Document document = Document.create(host, "test");
        assertEquals(List.of(16f, 16f), size(document, document.createElement("item")));
        assertEquals(List.of(18f, 18f), size(document, document.createElement("slot")));
        assertEquals(List.of(48f, 48f), size(document, document.createElement("entity")));
        assertEquals(List.of(16f, 16f), size(document, document.createElement("player-head")));
        Element canvas = document.createElement("canvas");
        canvas.setAttribute("width", "64");
        assertEquals(List.of(64f, 150f), size(document, canvas));
        Element img = document.createElement("img");
        img.setAttribute("src", "minecraft:textures/missing.png");
        assertTrue(Float.isNaN(document.replacedContent(img).intrinsicWidth()));
        assertEquals(Set.of("item", "slot", "entity", "player-head"), host.replacedElements().keySet());
        assertNull(document.replacedContent(document.createElement("div")));
    }

    private static List<Float> size(Document document, Element element) {
        ReplacedContent content = document.replacedContent(element);
        return List.of(content.intrinsicWidth(), content.intrinsicHeight());
    }
}
