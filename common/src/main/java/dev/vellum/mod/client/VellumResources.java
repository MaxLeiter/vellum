package dev.vellum.mod.client;

import dev.vellum.engine.host.FileStamps;
import dev.vellum.mod.Constants;
import dev.vellum.mod.client.render.FolderTextures;
import dev.vellum.mod.client.render.McImages;
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
 * {@code assets/ns/path/file.css}, read through the resource manager (so resource packs can restyle UIs), or the file
 * {@code path/file.css} when a mod gave the namespace a folder ({@link VellumPages}).
 *
 * <p>In a dev environment the source trees are read first: every {@code src/main/resources/assets} and
 * {@code common/src/main/resources/assets} between the run directory and the project root (the directory with
 * {@code settings.gradle}). The source and folder files pages were read from are watched: saving one reloads open
 * documents without a rebuild.
 */
public final class VellumResources {
    private static final long POLL_MS = 200;
    private static List<Path> sourceAssets = List.of();
    /** Source and folder files read for pages, which the watcher polls. */
    private static final Set<Path> FILES_READ = ConcurrentHashMap.newKeySet();
    private static boolean watching;

    private VellumResources() {}

    static void init() {
        if (!Services.PLATFORM.isDevelopmentEnvironment()) return;
        sourceAssets = findSourceAssets();
        if (sourceAssets.isEmpty()) return;
        Constants.LOG.info("Vellum: dev mode, reading UI sources from {} and reloading pages when they are saved", sourceAssets);
        startWatching();
    }

    /** A folder was registered: its files are watched in every environment. */
    static void watchFolders() {
        startWatching();
    }

    private static synchronized void startWatching() {
        if (watching) return;
        watching = true;
        Thread watcher = new Thread(VellumResources::watch, "Vellum file watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    /** The text of a resource, or null when missing. */
    public static @Nullable String loadText(String url) {
        byte[] bytes = loadBytes(url);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    /** The bytes of a resource (a {@code <scene>}'s structure file), or null when missing. */
    public static byte @Nullable [] loadBytes(String url) {
        Identifier id = Identifier.tryParse(url);
        if (id == null) return null;
        try {
            if (VellumPages.isFolder(id.getNamespace())) return loadFolderBytes(id);
            Path source = sourceFile(id);
            if (source != null) {
                FILES_READ.add(source);
                return Files.readAllBytes(source);
            }
            var resource = Minecraft.getInstance().getResourceManager().getResource(id);
            if (resource.isEmpty()) return null;
            try (InputStream in = resource.get().open()) {
                return in.readAllBytes();
            }
        } catch (IOException e) {
            Constants.LOG.warn("Vellum: cannot read {}: {}", url, e.toString());
            return null;
        }
    }

    /**
     * The bytes of a file in a folder namespace ({@link VellumPages}), watched like the pages that use it; null when
     * the file is missing or outside the folder. A missing file is watched too, so creating it reloads the page.
     */
    public static byte @Nullable [] loadFolderBytes(Identifier id) {
        Path file = VellumPages.file(id);
        if (file == null) {
            Path expected = VellumPages.expected(id);
            if (expected != null) FILES_READ.add(expected);
            return null;
        }
        try {
            FILES_READ.add(file);
            return Files.readAllBytes(file);
        } catch (IOException e) {
            Constants.LOG.warn("Vellum: cannot read {}: {}", file, e.toString());
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

    /**
     * Polls modification times, so a file deleted and written again, or saved by an editor that writes a temporary
     * file and renames it over the old one, still counts as changed.
     */
    private static void watch() {
        FileStamps stamps = new FileStamps();
        while (true) {
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (stamps.changed(FILES_READ)) Minecraft.getInstance().execute(VellumResources::reload);
        }
    }

    /** Files changed: folder images are read again, and every open page reloads. */
    private static void reload() {
        FolderTextures.clear();
        McImages.clearCaches();
        DocumentDriver.reloadAll();
    }
}
