package dev.vellum.mod.client;

import dev.vellum.engine.host.FileStamps;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where pages, stylesheets and scripts come from. A URL {@code ns:path/file.css} is the resource
 * {@code assets/ns/path/file.css}, read through the resource manager (so resource packs can restyle UIs).
 *
 * <p>In a dev environment the source trees are read first: every {@code src/main/resources/assets} and
 * {@code common/src/main/resources/assets} between the run directory and the project root (the directory with
 * {@code settings.gradle}). The source files pages were read from are watched: saving one reloads open documents
 * without a rebuild.
 */
public final class VellumResources {
    private static final long POLL_MS = 200;
    private static List<Path> sourceAssets = List.of();
    /** Source files read for pages, which the watcher polls. */
    private static final Set<Path> SOURCES_READ = ConcurrentHashMap.newKeySet();

    private VellumResources() {}

    static void init() {
        if (!Services.PLATFORM.isDevelopmentEnvironment()) return;
        sourceAssets = findSourceAssets();
        if (sourceAssets.isEmpty()) return;
        Constants.LOG.info("Vellum: dev mode, reading UI sources from {} and reloading pages when they are saved", sourceAssets);
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
            if (source != null) {
                SOURCES_READ.add(source);
                return Files.readString(source, StandardCharsets.UTF_8);
            }
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
        for (Path root : sourceAssets) {
            Path p = root.resolve(id.getNamespace()).resolve(id.getPath()).normalize();
            if (p.startsWith(root) && Files.isRegularFile(p)) return p;
        }
        return null;
    }

    /**
     * Dev runs use a directory such as {@code runs/client} or {@code <loader>/runs/client} as the working directory:
     * the asset source trees from there up to the project root, nearest first.
     */
    private static List<Path> findSourceAssets() {
        List<Path> roots = new ArrayList<>();
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            for (String tree : List.of("src/main/resources/assets", "common/src/main/resources/assets")) {
                Path candidate = dir.resolve(tree);
                if (Files.isDirectory(candidate)) roots.add(candidate);
            }
            if (Files.isRegularFile(dir.resolve("settings.gradle")) || Files.isRegularFile(dir.resolve("settings.gradle.kts"))) break;
        }
        return List.copyOf(roots);
    }

    private static void watch() {
        FileStamps stamps = new FileStamps();
        while (true) {
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (stamps.changed(SOURCES_READ)) Minecraft.getInstance().execute(DocumentDriver::reloadAll);
        }
    }
}
