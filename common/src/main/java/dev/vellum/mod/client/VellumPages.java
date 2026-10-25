package dev.vellum.mod.client;

import dev.vellum.mod.Constants;
import dev.vellum.mod.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pages that live in a folder the player can edit, such as one under {@code config/}, instead of in a resource pack.
 * A mod registers the folder under a namespace of its choosing; the page {@code ns:hud.html} is then the file
 * {@code hud.html} in that folder, and its relative stylesheets, scripts and images resolve next to it. Saving any
 * file a page read reloads the page.
 *
 * <pre>{@code
 * Path dir = configDir.resolve("hudsmith");
 * VellumPages.folder("hudsmith_hud", dir);
 * VellumHud.register(HUD, "hudsmith_hud:hud.html");
 * }</pre>
 *
 * <p>The folder owns its namespace outright: an id in it never falls back to resource packs, and a resource pack
 * that provides the same namespace is ignored for it (with a warning). Pick a namespace of its own, not the mod id,
 * so the mod's bundled pages keep coming from its jar.
 *
 * <p>Pages only read files inside the folder: a path that leaves it, by {@code ..} or by a symbolic link pointing
 * outside, is refused. The folder itself may be behind a link (launchers often link {@code .minecraft} or
 * {@code config}). Ids in other namespaces ({@code minecraft:} textures, sprites, items, fonts) still come from the
 * resource manager. Pages a server opens cannot read folder files.
 */
public final class VellumPages {
    private static final Map<String, Path> FOLDERS = new ConcurrentHashMap<>();

    private VellumPages() {}

    /**
     * Serves the namespace {@code namespace} from {@code dir}, which is created if missing. Call it once, at client
     * setup.
     *
     * @throws IllegalArgumentException if the namespace is not a valid identifier namespace, is already registered,
     *     or is {@code minecraft}, {@code vellum} or a loaded mod's id
     */
    public static void folder(String namespace, Path dir) {
        if (!Identifier.isValidNamespace(namespace) || namespace.isEmpty())
            throw new IllegalArgumentException("Not a valid namespace: '" + namespace + "'");
        if (namespace.equals("minecraft") || namespace.equals(Constants.MOD_ID) || Services.PLATFORM.isModLoaded(namespace))
            throw new IllegalArgumentException("The namespace '" + namespace + "' belongs to a mod; pick one of its own for the folder");
        Path root;
        try {
            Files.createDirectories(dir);
            root = dir.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot use " + dir + " for pages: " + e, e);
        }
        if (FOLDERS.putIfAbsent(namespace, root) != null)
            throw new IllegalArgumentException("The namespace '" + namespace + "' already has a folder");
        VellumResources.watchFolders();
        Constants.LOG.info("Vellum: pages in '{}:' come from {}", namespace, root);
    }

    /** Whether {@code url} names a file in a folder namespace. */
    public static boolean isFolderUrl(String url) {
        Identifier id = Identifier.tryParse(url);
        return id != null && isFolder(id.getNamespace());
    }

    /** Whether {@code namespace} is served from a folder. */
    public static boolean isFolder(String namespace) {
        return FOLDERS.containsKey(namespace);
    }

    /**
     * The file for {@code id} in its namespace's folder, or null when its namespace has no folder, the file is
     * missing, or the path leaves the folder.
     */
    static @Nullable Path file(Identifier id) {
        Path p = expected(id);
        if (p == null) return null;
        Path root = FOLDERS.get(id.getNamespace());
        try {
            Path real = p.toRealPath();
            return real.startsWith(root) && Files.isRegularFile(real) ? real : null;
        } catch (IOException e) {
            return null; // missing
        }
    }

    /** Where the file for {@code id} would be, whether or not it exists; null when that is outside its folder. */
    static @Nullable Path expected(Identifier id) {
        Path root = FOLDERS.get(id.getNamespace());
        if (root == null) return null;
        Path p = root.resolve(id.getPath()).normalize();
        return p.startsWith(root) ? p : null;
    }

    /** After a resource reload: resource packs that also provide a folder's namespace are ignored for it. */
    static void checkPacks() {
        var namespaces = Minecraft.getInstance().getResourceManager().getNamespaces();
        for (String ns : FOLDERS.keySet()) {
            if (namespaces.contains(ns))
                Constants.LOG.warn("Vellum: resource packs provide '{}:', which is served from {}; the folder wins", ns, FOLDERS.get(ns));
        }
    }
}
