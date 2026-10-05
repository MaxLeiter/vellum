package dev.vellum.preview.render;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MinecraftAssetsTest {
    @TempDir
    Path dir;

    @Test
    void readsSpriteScalingFromMcmeta() throws IOException {
        MinecraftAssets assets = new TestPack(dir)
                .png("test:textures/gui/sprites/a.png", 1, 1, 0)
                .text("test:textures/gui/sprites/a.png.mcmeta", "{\"gui\":{\"scaling\":{\"type\":\"nine_slice\",\"width\":200,\"height\":20,\"border\":3}}}")
                .png("test:textures/gui/sprites/b.png", 1, 1, 0)
                .text("test:textures/gui/sprites/b.png.mcmeta", "{\"gui\":{\"scaling\":{\"type\":\"nine_slice\",\"width\":8,\"height\":20,"
                        + "\"border\":{\"left\":2,\"top\":2,\"right\":2,\"bottom\":3},\"stretch_inner\":true}}}")
                .png("test:textures/gui/sprites/c.png", 1, 1, 0)
                .text("test:textures/gui/sprites/c.png.mcmeta", "{\"gui\":{\"scaling\":{\"type\":\"tile\",\"width\":16,\"height\":8}}}")
                .png("test:textures/gui/sprites/d.png", 3, 2, 0, 0, 0, 0, 0, 0)
                .assets();
        assertEquals(new SpriteScaling.NineSlice(200, 20, 3, 3, 3, 3, false), assets.sprite("test:a").orElseThrow().scaling());
        assertEquals(new SpriteScaling.NineSlice(8, 20, 2, 2, 2, 3, true), assets.sprite("test:b").orElseThrow().scaling());
        assertEquals(new SpriteScaling.Tile(16, 8), assets.sprite("test:c").orElseThrow().scaling());
        Texture plain = assets.sprite("test:d").orElseThrow();
        assertEquals(SpriteScaling.STRETCH, plain.scaling());
        assertEquals(List.of(200, 20, 3, 2), List.of(assets.sprite("test:a").orElseThrow().naturalWidth(),
                assets.sprite("test:a").orElseThrow().naturalHeight(), plain.naturalWidth(), plain.naturalHeight()));
    }

    @Test
    void animatedTexturesShowTheirFirstFrameAndBlurIsRead() throws IOException {
        MinecraftAssets assets = new TestPack(dir)
                .png("test:textures/square.png", 2, 6, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3)
                .text("test:textures/square.png.mcmeta", "{\"animation\":{},\"texture\":{\"blur\":true}}")
                .png("test:textures/tall.png", 2, 6, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3)
                .text("test:textures/tall.png.mcmeta", "{\"animation\":{\"height\":3}}")
                .assets();
        Texture square = assets.texture("test:textures/square.png").orElseThrow();
        assertEquals(List.of(2, 2, true), List.of(square.image().getWidth(), square.image().getHeight(), square.blur()));
        Texture tall = assets.texture("test:textures/tall.png").orElseThrow();
        assertEquals(List.of(2, 3, false), List.of(tall.image().getWidth(), tall.image().getHeight(), tall.blur()));
    }

    @Test
    void earlierRootsOverrideLaterOnes(@TempDir Path other) throws IOException {
        new TestPack(dir).text("test:data.txt", "first");
        new TestPack(other).text("test:data.txt", "second").text("test:only.txt", "only");
        MinecraftAssets assets = MinecraftAssets.open(List.of(dir, other), Optional.empty());
        assertEquals(Optional.of("first"), assets.readText("test:data.txt"));
        assertEquals(Optional.of("only"), assets.readText("test:only.txt"));
        assertEquals(Optional.empty(), assets.readText("test:missing.txt"));
        assertTrue(assets.texture("test:missing.png").isEmpty());
    }

    @Test
    void urlsAreIdsOrFilePaths() throws IOException {
        assertTrue(MinecraftAssets.isAssetId("minecraft:textures/a.png"));
        assertFalse(MinecraftAssets.isAssetId("C:\\pages\\a.html"));
        assertFalse(MinecraftAssets.isAssetId("/pages/a.html"));
        assertEquals("minecraft:textures/gui/sprites/widget/button.png", MinecraftAssets.assetUrl("widget/button", "textures/gui/sprites/", ".png"));
        assertEquals("vellum:font/x.json", MinecraftAssets.assetUrl("vellum:x", "font/", ".json"));
        Path file = Files.writeString(dir.resolve("page.html"), "<p>hi");
        MinecraftAssets assets = MinecraftAssets.open(List.of(), Optional.empty());
        assertEquals(Optional.of("<p>hi"), assets.readText(file.toString()));
        assertFalse(assets.hasMinecraft());
    }

    @Test
    void readsSpritesFromTheClientJar() throws IOException {
        Optional<Path> jar = MinecraftAssets.findClientJar();
        assumeTrue(jar.isPresent(), "no Minecraft " + MinecraftAssets.MINECRAFT_VERSION + " jar found");
        try (MinecraftAssets assets = MinecraftAssets.open(List.of(), jar)) {
            assertTrue(assets.hasMinecraft());
            assertEquals(new SpriteScaling.NineSlice(200, 20, 3, 3, 3, 3, false), assets.sprite("minecraft:widget/button").orElseThrow().scaling());
            assertEquals(new SpriteScaling.NineSlice(8, 20, 2, 2, 2, 3, false), assets.sprite("widget/slider_handle").orElseThrow().scaling());
            assertEquals(new SpriteScaling.NineSlice(100, 100, 10, 10, 10, 10, true), assets.sprite("tooltip/frame").orElseThrow().scaling());
            assertEquals(16, assets.texture("minecraft:textures/item/diamond.png").orElseThrow().image().getWidth());
        }
    }
}
