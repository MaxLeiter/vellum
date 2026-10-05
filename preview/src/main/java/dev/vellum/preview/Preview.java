package dev.vellum.preview;

import dev.vellum.engine.dom.Viewport;
import dev.vellum.preview.host.PreviewHost;
import dev.vellum.preview.render.MinecraftAssets;
import dev.vellum.preview.render.MinecraftFont;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** The standalone previewer: a window that renders a page live, or a headless snapshot to PNG. */
public final class Preview {
    private static final String USAGE = """
            Usage: preview <page.html> [--scale N] [--size WxH] [--data data.json] [--snapshot out.png [--frames N]]
                   preview <page.html> --actions actions.txt [--out dir] [--snapshot out.png] [--scale N] [--size WxH]
                   preview --canvas-test [--scale N] [--size WxH] [--snapshot out.png]
            The GUI size defaults to 427x240 (1280x720 at GUI scale 3), the scale to 2.""";

    /** Command line options. Sizes are GUI px. */
    record Options(Path page, boolean canvasTest, int scale, int width, int height, Path data, Path snapshot, int frames,
                   Path actions, Path out) {
        static Options parse(String... args) {
            Path page = null, data = null, snapshot = null, actions = null, out = null;
            boolean canvasTest = false;
            int scale = 2, width = CanvasTest.WIDTH, height = CanvasTest.HEIGHT, frames = 1;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--canvas-test" -> canvasTest = true;
                    case "--scale" -> scale = positive(value(args, ++i, arg));
                    case "--frames" -> frames = positive(value(args, ++i, arg));
                    case "--data" -> data = Path.of(value(args, ++i, arg));
                    case "--snapshot" -> snapshot = Path.of(value(args, ++i, arg));
                    case "--actions" -> actions = Path.of(value(args, ++i, arg));
                    case "--out" -> out = Path.of(value(args, ++i, arg));
                    case "--size" -> {
                        String[] size = value(args, ++i, arg).split("x");
                        if (size.length != 2) throw new IllegalArgumentException("--size takes WxH, e.g. 427x240");
                        width = positive(size[0]);
                        height = positive(size[1]);
                    }
                    default -> {
                        if (arg.startsWith("--") || page != null) throw new IllegalArgumentException("Unexpected argument: " + arg);
                        page = Path.of(arg);
                    }
                }
            }
            if (canvasTest == (page != null)) throw new IllegalArgumentException("Give either a page or --canvas-test");
            if (actions != null && canvasTest) throw new IllegalArgumentException("--actions needs a page");
            return new Options(page, canvasTest, scale, width, height, data, snapshot, frames, actions, out);
        }

        /** Runs without a window: a snapshot or a script of actions. */
        boolean headless() {
            return snapshot != null || actions != null;
        }

        /** Where {@code shot} actions write: {@code --out}, else beside the snapshot, else the working directory. */
        Path shots() {
            if (out != null) return out;
            Path parent = snapshot == null ? null : snapshot.toAbsolutePath().getParent();
            return parent != null ? parent : Path.of("");
        }

        private static String value(String[] args, int i, String option) {
            if (i >= args.length) throw new IllegalArgumentException(option + " needs a value");
            return args[i];
        }

        private static int positive(String value) {
            try {
                int n = Integer.parseInt(value);
                if (n > 0) return n;
            } catch (NumberFormatException e) {
                // reported below
            }
            throw new IllegalArgumentException("Expected a positive number, got " + value);
        }
    }

    private Preview() {}

    public static void main(String[] args) throws IOException {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage() + "\n" + USAGE);
            System.exit(2);
            return;
        }
        if (options.headless()) System.setProperty("java.awt.headless", "true");

        Optional<Path> jar = MinecraftAssets.findClientJar();
        System.out.println(jar.map(j -> "Minecraft assets: " + j).orElse("No Minecraft " + MinecraftAssets.MINECRAFT_VERSION
                + " jar found (set -D" + MinecraftAssets.JAR_PROPERTY + " or " + MinecraftAssets.JAR_ENV + "); using a fallback font"));
        Optional<Path> pack = options.canvasTest() ? Optional.empty() : PreviewHost.packRoot(options.page());
        MinecraftAssets assets = MinecraftAssets.open(pack.stream().toList(), jar);
        MinecraftFont font = new MinecraftFont(assets);
        PreviewHost host = new PreviewHost(assets, font);
        Scene scene = options.canvasTest() ? new CanvasTest(host) : new PageScene(host, PreviewHost.pageUrl(options.page()),
                options.data() == null ? null : Files.readString(options.data()),
                new Viewport(options.width(), options.height(), options.scale()));

        if (options.actions() != null) {
            System.exit(actions((PageScene) scene, new FrameRenderer(assets, font), options));
        }
        if (options.snapshot() != null) {
            System.exit(snapshot(scene, new FrameRenderer(assets, font), options));
        }
        SwingUtilities.invokeLater(() -> new PreviewWindow(scene, host, options.scale(), options.width(), options.height()).show());
    }

    /**
     * Runs the {@code --actions} script, then writes the last frame to {@code --snapshot} if given. Exits 1 if the
     * page failed, 2 if the script is malformed or names something that is not there.
     */
    private static int actions(PageScene scene, FrameRenderer renderer, Options options) throws IOException {
        try {
            Actions actions = Actions.parse(Files.readString(options.actions()));
            Path shots = options.shots();
            Files.createDirectories(shots.toAbsolutePath());
            BufferedImage image = actions.run(scene, renderer, options.width(), options.height(), options.scale(), shots,
                    System.out);
            if (options.snapshot() != null) {
                ImageIO.write(image, "png", options.snapshot().toFile());
                System.out.println("Wrote " + options.snapshot().toAbsolutePath());
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println(options.actions() + ": " + e.getMessage());
            return 2;
        }
        return scene.error() == null ? 0 : 1;
    }

    /** Renders {@code frames} frames 16 ms apart and writes the last; exits non-zero if the page failed. */
    private static int snapshot(Scene scene, FrameRenderer renderer, Options options) throws IOException {
        BufferedImage image = null;
        for (int i = 0; i < options.frames(); i++) {
            image = renderer.render(scene, options.width() * options.scale(), options.height() * options.scale(),
                    options.scale(), i * 16.0, null);
        }
        ImageIO.write(image, "png", options.snapshot().toFile());
        System.out.println("Wrote " + options.snapshot().toAbsolutePath());
        return scene.error() == null ? 0 : 1;
    }
}
