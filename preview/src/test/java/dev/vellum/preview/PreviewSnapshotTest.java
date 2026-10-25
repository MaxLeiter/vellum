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
        try (TestScene t = tooltipPage("tooltip.html")) {
            t.render(0);
            float[] at = t.scene().document().getElementById("rich").getBoundingClientRect();
            t.hover(new float[] {at[0] + 10, at[1] + 5});
            t.render(400);
            assertNull(t.scene().document().input().tooltip(), "not before the delay");
            assertTrue(t.scene().needsFrame(520), "the delay ending needs a frame");
            BufferedImage image = t.render(520);
            assertNull(t.scene().error(), () -> "failed: " + t.scene().error());
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
        try (TestScene t = TestScene.open(modPages("dev").orElseThrow().resolve("shop_row.html"), JAR,
                CanvasTest.WIDTH, CanvasTest.HEIGHT, SCALE)) {
            t.render(0);
            t.hover(t.scene().document().pointerTarget(t.scene().document().querySelector("item")));
            BufferedImage image = t.render(16);
            assertNull(t.scene().error(), () -> "failed: " + t.scene().error());
            Snapshots.assertMatches("item-tooltip", image);

            t.hover(t.scene().document().pointerTarget(t.scene().document().getElementById("nowrap")));
            t.render(32);
            Snapshots.assertMatches("nowrap-tooltip", t.render(540));
        }
    }

    /**
     * The scene showcase opened at its last step: the cottage from its structure file, the builder and the captions.
     * Then a block hovered in it fires its event and shows its name.
     */
    @Test
    void sceneShowcaseMatchesGoldens() throws IOException {
        assumeTrue(JAR.isPresent(), "no Minecraft " + MinecraftAssets.MINECRAFT_VERSION + " jar found");
        try (TestScene t = TestScene.open(modPages("showcase").orElseThrow().resolve("scene.html"),
                "{\"start\": {\"step\": \"done\"}}", JAR, CanvasTest.WIDTH, CanvasTest.HEIGHT, SCALE)) {
            for (int frame = 0; frame < 10; frame++) t.render(frame * 16.0);
            BufferedImage image = t.render(2000);
            assertNull(t.scene().error(), () -> "failed: " + t.scene().error());
            Snapshots.assertMatches("scene-showcase", image);

            float[] at = t.scene().document().querySelector("scene").getBoundingClientRect();
            t.hover(new float[] {at[0] + at[2] / 2, at[1] + at[3] / 2});
            image = t.render(2016);
            assertTrue(t.scene().document().input().tooltip() != null, "the hovered block shows its name");
            assertTrue(!t.scene().document().scripts().evaluateToJson("String(window.lastHover)", "test").contains("undefined"),
                    "the page heard blockhover");
            Snapshots.assertMatches("scene-tooltip", image);
        }
    }

    /** A page of {@code src/test/resources/tooltip}, at the canvas test's size. */
    private static TestScene tooltipPage(String name) throws URISyntaxException {
        Path page = Path.of(PreviewSnapshotTest.class.getResource("/tooltip/" + name).toURI());
        return TestScene.open(page, JAR, CanvasTest.WIDTH, CanvasTest.HEIGHT, SCALE);
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
