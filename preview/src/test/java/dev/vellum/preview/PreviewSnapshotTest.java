package dev.vellum.preview;

import dev.vellum.engine.event.Modifiers;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Whole-frame snapshots through the previewer's rendering path: the canvas test sheet, and every page in
 * {@code src/test/resources/pages} plus the demo UIs in {@code common/src/main/resources/assets/vellum/vellum/demo}.
 * The item tooltips render the dev autopilot's shop row ({@code vellum/dev/shop_row.html}), which it shows in game.
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
        return Stream.concat(htmlFiles(testPages), modPages("demo").map(PreviewSnapshotTest::htmlFiles).orElse(Stream.empty()))
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

    /** A {@code title-json} tooltip: hovered, then drawn half a second later, wrapped and coloured as in game. */
    @Test
    void titleTooltipMatchesGolden() throws IOException, URISyntaxException {
        assumeTrue(JAR.isPresent(), "no Minecraft " + MinecraftAssets.MINECRAFT_VERSION + " jar found");
        Path page = Path.of(PreviewSnapshotTest.class.getResource("/tooltip/tooltip.html").toURI());
        try (MinecraftAssets assets = MinecraftAssets.open(List.of(), JAR)) {
            MinecraftFont font = new MinecraftFont(assets);
            PageScene scene = new PageScene(new PreviewHost(assets, font), PreviewHost.pageUrl(page), null,
                    new Viewport(CanvasTest.WIDTH, CanvasTest.HEIGHT, SCALE));
            FrameRenderer renderer = new FrameRenderer(assets, font);
            renderer.render(scene, CanvasTest.WIDTH * SCALE, CanvasTest.HEIGHT * SCALE, SCALE, 0, null);
            float[] at = scene.document().getElementById("rich").getBoundingClientRect();
            scene.input(in -> in.mouseMove(at[0] + 10, at[1] + 5, Modifiers.NONE));
            renderer.render(scene, CanvasTest.WIDTH * SCALE, CanvasTest.HEIGHT * SCALE, SCALE, 400, null);
            assertNull(scene.document().input().tooltip(), "not before the delay");
            assertTrue(scene.needsFrame(520), "the delay ending needs a frame");
            BufferedImage image = renderer.render(scene, CanvasTest.WIDTH * SCALE, CanvasTest.HEIGHT * SCALE, SCALE, 520, null);
            assertNull(scene.error(), () -> "failed: " + scene.error());
            Snapshots.assertMatches("tooltip", image);
        }
    }

    /**
     * An {@code <item tooltip>} in a row with a {@code title-json}: at once, the item's tooltip (its name, in the
     * previewer) with the row's lines after it, unwrapped. Then a {@code title-nowrap} title, half a second later.
     */
    @Test
    void itemAndNowrapTooltipsMatchGoldens() throws IOException {
        assumeTrue(JAR.isPresent(), "no Minecraft " + MinecraftAssets.MINECRAFT_VERSION + " jar found");
        Path page = modPages("dev").orElseThrow().resolve("shop_row.html");
        try (MinecraftAssets assets = MinecraftAssets.open(PreviewHost.packRoot(page).stream().toList(), JAR)) {
            MinecraftFont font = new MinecraftFont(assets);
            PageScene scene = new PageScene(new PreviewHost(assets, font), PreviewHost.pageUrl(page), null,
                    new Viewport(CanvasTest.WIDTH, CanvasTest.HEIGHT, SCALE));
            FrameRenderer renderer = new FrameRenderer(assets, font);
            int width = CanvasTest.WIDTH * SCALE, height = CanvasTest.HEIGHT * SCALE;
            renderer.render(scene, width, height, SCALE, 0, null);
            float[] item = scene.document().pointerTarget(scene.document().querySelector("item"));
            scene.input(in -> in.mouseMove(item[0], item[1], Modifiers.NONE));
            BufferedImage image = renderer.render(scene, width, height, SCALE, 16, null);
            assertNull(scene.error(), () -> "failed: " + scene.error());
            Snapshots.assertMatches("item-tooltip", image);

            float[] button = scene.document().pointerTarget(scene.document().getElementById("nowrap"));
            scene.input(in -> in.mouseMove(button[0], button[1], Modifiers.NONE));
            renderer.render(scene, width, height, SCALE, 32, null);
            Snapshots.assertMatches("nowrap-tooltip", renderer.render(scene, width, height, SCALE, 540, null));
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

    /**
     * A directory of the mod's pages ({@code demo}, {@code dev}), found by walking up from the working directory to
     * the repository.
     */
    private static Optional<Path> modPages(String name) {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path pages = dir.resolve("common/src/main/resources/assets/vellum/vellum/" + name);
            if (Files.isDirectory(pages)) return Optional.of(pages);
        }
        return Optional.empty();
    }
}
