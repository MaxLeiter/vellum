package dev.vellum.preview.render;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** A resource pack written into a temporary directory, for tests that must not depend on Minecraft's jar. */
final class TestPack {
    private final Path root;

    TestPack(Path root) {
        this.root = root;
    }

    /** Writes a PNG at an {@code ns:path} URL from row-major ARGB pixels. */
    TestPack png(String url, int width, int height, int... pixels) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, pixels, 0, width);
        Path file = file(url);
        Files.createDirectories(file.getParent());
        ImageIO.write(image, "png", file.toFile());
        return this;
    }

    TestPack text(String url, String text) throws IOException {
        Path file = file(url);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return this;
    }

    /** An asset stack of just this pack (no Minecraft jar). */
    MinecraftAssets assets() {
        return MinecraftAssets.open(List.of(root), Optional.empty());
    }

    private Path file(String url) {
        String[] id = url.split(":", 2);
        return root.resolve("assets").resolve(id[0]).resolve(id[1]);
    }
}
