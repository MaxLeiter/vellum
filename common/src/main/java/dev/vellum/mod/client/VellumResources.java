package dev.vellum.mod.client;

import dev.vellum.mod.Constants;
import dev.vellum.mod.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Where pages, stylesheets and scripts come from. A URL {@code ns:path/file.css} is the resource
 * {@code assets/ns/path/file.css}, read through the resource manager (so resource packs can restyle UIs).
 *
 * <p>In a dev environment the source tree ({@code common/src/main/resources/assets}, found from the run directory)
 * is read first and watched: saving an .html, .css or .js file reloads open documents without a rebuild.
 */
public final class VellumResources {
    private static final long POLL_MS = 500;
    private static @Nullable Path sourceAssets;

    private VellumResources() {}

    static void init() {
        if (!Services.PLATFORM.isDevelopmentEnvironment()) return;
        sourceAssets = findSourceAssets();
        if (sourceAssets == null) return;
        Constants.LOG.info("Vellum: dev mode, reading and watching UI sources in {}", sourceAssets);
        Thread watcher = new Thread(VellumResources::watch, "Vellum source watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    /** The text of a resource, or null when missing. */
    public static @Nullable String loadText(String url) {
        Identifier id = Identifier.tryParse(url);
        if (id == null) return null;
        Path source = sourceFile(id);
        try {
            if (source != null) return Files.readString(source, StandardCharsets.UTF_8);
            var resource = Minecraft.getInstance().getResourceManager().getResource(id);
            if (resource.isEmpty()) return null;
            try (InputStream in = resource.get().open()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            Constants.LOG.warn("Vellum: cannot read {}: {}", url, e.toString());
            return null;
        }
    }

    private static @Nullable Path sourceFile(Identifier id) {
        if (sourceAssets == null) return null;
        Path p = sourceAssets.resolve(id.getNamespace()).resolve(id.getPath()).normalize();
        return p.startsWith(sourceAssets) && Files.isRegularFile(p) ? p : null;
    }

    /** Dev runs use {@code <loader>/runs/client} as the working directory; look a few levels up for the sources. */
    private static @Nullable Path findSourceAssets() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("common/src/main/resources/assets");
            if (Files.isDirectory(candidate)) return candidate;
        }
        return null;
    }

    private static void watch() {
        long last = fingerprint();
        while (true) {
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
            long now = fingerprint();
            if (now != last) {
                last = now;
                Minecraft.getInstance().execute(DocumentDriver::reloadAll);
            }
        }
    }

    /** Changes whenever a page, stylesheet or script is saved, added or removed. */
    private static long fingerprint() {
        try (Stream<Path> files = Files.walk(sourceAssets)) {
            return files.filter(p -> {
                String name = p.getFileName().toString();
                return name.endsWith(".html") || name.endsWith(".css") || name.endsWith(".js");
            }).mapToLong(p -> {
                try {
                    return Files.getLastModifiedTime(p).toMillis() * 31 + p.hashCode();
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }
}
