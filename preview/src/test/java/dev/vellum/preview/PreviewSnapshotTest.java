package dev.vellum.preview;

import dev.vellum.engine.dom.Viewport;
import dev.vellum.preview.host.PreviewHost;
import dev.vellum.preview.render.MinecraftAssets;
import dev.vellum.preview.render.MinecraftFont;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Whole-frame snapshots through the previewer's rendering path: the canvas test sheet, and every page in
 * {@code src/test/resources/pages} plus the demo UIs in {@code common/src/main/resources/assets/vellum/vellum/demo}.
 * They need Minecraft's jar (fonts and sprites) and are skipped without it. Rendering is deterministic: pages run
 * the same frames at the same times on every run.
 */
class PreviewSnapshotTest {
    private static final int SCALE = 2;
    private static final Optional<Path> JAR = MinecraftAssets.findClientJar();

    @Test
    void canvasTestMatchesGolden() throws IOException {
        assumeTrue(JAR.isPresent(), "no Minecraft " + MinecraftAssets.MINECRAFT_VERSION + " jar found");
        try (MinecraftAssets assets = MinecraftAssets.open(List.of(), JAR)) {
            MinecraftFont font = new MinecraftFont(assets);
            Scene scene = new CanvasTest(new PreviewHost(assets, font));
            Snapshots.assertMatches("canvas-test", render(scene, assets, font));
        }
    }

    @TestFactory
    Stream<DynamicTest> pagesMatchGoldens() throws IOException, URISyntaxException {
        Path testPages = Path.of(PreviewSnapshotTest.class.getResource("/pages").toURI());
        return Stream.concat(htmlFiles(testPages), demoDirectory().map(PreviewSnapshotTest::htmlFiles).orElse(Stream.empty()))
                .map(page -> DynamicTest.dynamicTest(page.getFileName().toString(), () -> pageMatchesGolden(page)));
    }

    private static void pageMatchesGolden(Path page) throws IOException {
        assumeTrue(JAR.isPresent(), "no Minecraft " + MinecraftAssets.MINECRAFT_VERSION + " jar found");
        try (MinecraftAssets assets = MinecraftAssets.open(PreviewHost.packRoot(page).stream().toList(), JAR)) {
            MinecraftFont font = new MinecraftFont(assets);
            PageScene scene = new PageScene(new PreviewHost(assets, font), PreviewHost.pageUrl(page), null,
                    new Viewport(CanvasTest.WIDTH, CanvasTest.HEIGHT, SCALE));
            BufferedImage image = render(scene, assets, font);
            assertNull(scene.error(), () -> page + " failed: " + scene.error());
            String name = page.getFileName().toString().replaceFirst("\\.html$", "");
            Snapshots.assertMatches("page-" + name, image);
        }
    }

    /** Ten frames, 16 ms apart from t = 0, at 427×240 GUI px: load-time timers have run and animations are under way. */
    private static BufferedImage render(Scene scene, MinecraftAssets assets, MinecraftFont font) {
        FrameRenderer renderer = new FrameRenderer(assets, font);
        BufferedImage image = null;
        for (int frame = 0; frame < 10; frame++) {
            image = renderer.render(scene, CanvasTest.WIDTH * SCALE, CanvasTest.HEIGHT * SCALE, SCALE, frame * 16.0, null);
        }
        return image;
    }

    private static Stream<Path> htmlFiles(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(f -> f.toString().endsWith(".html")).sorted().toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The mod's demo UIs, found by walking up from the working directory to the repository. */
    private static Optional<Path> demoDirectory() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path demos = dir.resolve("common/src/main/resources/assets/vellum/vellum/demo");
            if (Files.isDirectory(demos)) return Optional.of(demos);
        }
        return Optional.empty();
    }
}
