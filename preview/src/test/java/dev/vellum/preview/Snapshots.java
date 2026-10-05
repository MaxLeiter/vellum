package dev.vellum.preview;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Golden-image assertions. Goldens live in {@code preview/src/test/snapshots/<name>.png}. On a mismatch the actual
 * image and a diff (differing pixels red over a faded copy of the golden) are written to
 * {@code preview/build/snapshots}; {@code -Dvellum.updateSnapshots=true} rewrites the goldens instead.
 */
public final class Snapshots {
    /** Largest per-channel difference that still counts as equal (Java2D blending may round differently). */
    private static final int TOLERANCE = 2;

    private Snapshots() {}

    public static void assertMatches(String name, BufferedImage actual) {
        Path golden = Path.of(System.getProperty("vellum.goldens", "src/test/snapshots"), name + ".png");
        Path out = Path.of(System.getProperty("vellum.snapshots", "build/snapshots"));
        if (Boolean.getBoolean("vellum.updateSnapshots")) {
            write(actual, golden);
            return;
        }
        if (!Files.exists(golden)) {
            write(actual, out.resolve(name + ".actual.png"));
            fail("No golden " + golden + " (actual written to " + out + "); run with -Dvellum.updateSnapshots=true to create it");
        }
        BufferedImage expected = read(golden);
        if (expected.getWidth() != actual.getWidth() || expected.getHeight() != actual.getHeight()) {
            write(actual, out.resolve(name + ".actual.png"));
            fail(name + ": size " + actual.getWidth() + "x" + actual.getHeight() + ", golden " + expected.getWidth() + "x" + expected.getHeight());
        }
        BufferedImage diff = new BufferedImage(actual.getWidth(), actual.getHeight(), BufferedImage.TYPE_INT_ARGB);
        int mismatches = 0;
        for (int y = 0; y < actual.getHeight(); y++) {
            for (int x = 0; x < actual.getWidth(); x++) {
                int e = expected.getRGB(x, y), a = actual.getRGB(x, y);
                boolean same = maxChannelDifference(e, a) <= TOLERANCE;
                if (!same) mismatches++;
                diff.setRGB(x, y, same ? 0x40000000 | e & 0xFFFFFF : 0xFFFF0000);
            }
        }
        if (mismatches > 0) {
            write(actual, out.resolve(name + ".actual.png"));
            write(diff, out.resolve(name + ".diff.png"));
            fail(name + ": " + mismatches + " pixels differ from " + golden + "; see " + out + " (-Dvellum.updateSnapshots=true accepts them)");
        }
    }

    private static int maxChannelDifference(int a, int b) {
        int max = 0;
        for (int shift = 0; shift < 32; shift += 8) max = Math.max(max, Math.abs((a >>> shift & 0xFF) - (b >>> shift & 0xFF)));
        return max;
    }

    private static BufferedImage read(Path file) {
        try {
            return ImageIO.read(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(BufferedImage image, Path file) {
        try {
            Files.createDirectories(file.getParent());
            ImageIO.write(image, "png", file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
