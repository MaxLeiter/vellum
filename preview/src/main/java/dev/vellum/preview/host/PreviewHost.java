package dev.vellum.preview.host;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.Host;
import dev.vellum.engine.host.ReplacedContent;
import dev.vellum.engine.script.ScriptRuntime;
import dev.vellum.preview.render.MinecraftAssets;
import dev.vellum.preview.render.MinecraftFont;

import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The previewer's {@link Host}. Text and images come from the {@link MinecraftAssets} stack. A page that lives under
 * {@code <root>/assets/<ns>/} is addressed as {@code ns:path} with {@code <root>} on the stack, so its {@code ns:}
 * references resolve as in game; other pages are addressed by file path and resolve relative paths on disk.
 */
public final class PreviewHost implements Host {
    private final MinecraftAssets assets;
    private final MinecraftFont fonts;
    private final Set<Path> loadedFiles = ConcurrentHashMap.newKeySet();
    private Consumer<String> navigator = url -> {};
    /** The clipboard when there is no system clipboard (headless). */
    private String clipboard = "";

    public PreviewHost(MinecraftAssets assets, MinecraftFont fonts) {
        this.assets = assets;
        this.fonts = fonts;
    }

    public MinecraftAssets assets() { return assets; }

    /** Files read through {@link #loadText}, so the previewer can watch them. */
    public Set<Path> loadedFiles() { return loadedFiles; }

    /** Where {@code <a href>} and {@code location.href} navigation goes. */
    public void onNavigate(Consumer<String> navigator) {
        this.navigator = navigator;
    }

    // ---- Page addressing ----

    /** The URL of a page file: {@code ns:path} when it lives under {@code <root>/assets/<ns>/}, else its path. */
    public static String pageUrl(Path page) {
        Path file = page.toAbsolutePath().normalize();
        int assetsDir = assetsDirIndex(file);
        if (assetsDir < 0) return file.toString();
        String path = file.subpath(assetsDir + 2, file.getNameCount()).toString();
        return file.getName(assetsDir + 1) + ":" + path.replace(file.getFileSystem().getSeparator(), "/");
    }

    /** The resource root a page under {@code <root>/assets/<ns>/} needs on the asset stack. */
    public static Optional<Path> packRoot(Path page) {
        Path file = page.toAbsolutePath().normalize();
        int assetsDir = assetsDirIndex(file);
        if (assetsDir < 0) return Optional.empty();
        return Optional.of(assetsDir == 0 ? file.getRoot() : file.getRoot().resolve(file.subpath(0, assetsDir)));
    }

    /** Index of the innermost {@code assets} directory followed by a valid namespace directory, or -1. */
    private static int assetsDirIndex(Path file) {
        for (int i = file.getNameCount() - 3; i >= 0; i--) {
            if (file.getName(i).toString().equals("assets") && MinecraftAssets.isAssetId(file.getName(i + 1) + ":_")) return i;
        }
        return -1;
    }

    // ---- Host ----

    @Override
    public MinecraftFont fonts() { return fonts; }

    @Override
    public String loadText(String url) {
        return assets.locate(url).map(file -> {
            if (file.getFileSystem() == FileSystems.getDefault()) loadedFiles.add(file);
            try {
                return Files.readString(file);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).orElse(null);
    }

    /** {@code ns:} ids resolve as in game; references from a file-path page resolve on the file system. */
    @Override
    public String resolveUrl(String base, String relative) {
        if (base == null || relative == null || relative.isEmpty()
                || MinecraftAssets.isAssetId(base) || MinecraftAssets.isAssetId(relative)) {
            return Host.super.resolveUrl(base, relative);
        }
        return Path.of(base).resolveSibling(relative).normalize().toString();
    }

    @Override
    public ReplacedContent createReplaced(Element element) {
        return ReplacedElements.create(element, assets, fonts);
    }

    @Override
    public boolean isReplacedTag(String tag) {
        return ReplacedElements.TAGS.contains(tag);
    }

    @Override
    public ScriptRuntime createScriptRuntime(Document document) {
        // INTEGRATION: return dev.vellum.engine.script.Scripting.rhino().apply(document) once the scripting
        // workstream lands. Until then pages render without scripts.
        return null;
    }

    @Override
    public void log(LogLevel level, String message) {
        System.out.println("[vellum " + level.name().toLowerCase() + "] " + message);
    }

    @Override
    public void reportError(String message, Throwable error) {
        log(LogLevel.ERROR, message);
        error.printStackTrace(System.out);
    }

    @Override
    public void playSound(String id, float volume, float pitch) {
        log(LogLevel.DEBUG, "sound " + id);
    }

    @Override
    public String getClipboard() {
        try {
            return Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor) instanceof String s ? s : "";
        } catch (HeadlessException | IllegalStateException | UnsupportedFlavorException | IOException e) {
            return clipboard;
        }
    }

    @Override
    public void setClipboard(String text) {
        clipboard = text;
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        } catch (HeadlessException | IllegalStateException e) {
            // keep the in-process copy
        }
    }

    @Override
    public void close() {
        log(LogLevel.INFO, "close() requested");
    }

    @Override
    public void send(String channel, String json) {
        log(LogLevel.INFO, "send " + channel + " " + json);
    }

    @Override
    public void navigate(String url) {
        navigator.accept(url);
    }
}
