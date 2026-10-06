package dev.vellum.preview.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * The previewer's only way to read resources. Like resource packs, resources come from a stack of roots searched in
 * order (directories or zips that contain {@code assets/}), with the Minecraft client jar last when one is found.
 * URLs are {@code ns:path} ids, meaning {@code assets/ns/path}, or file-system paths. Mojang's assets are read
 * straight from the jar, never copied.
 *
 * <p>Textures are decoded once and cached together with their {@code .mcmeta} (blur, GUI sprite scaling, the first
 * frame of animations).
 */
public final class MinecraftAssets implements AutoCloseable {
    /** System property and environment variable that name the Minecraft client jar explicitly. */
    public static final String JAR_PROPERTY = "vellum.mcJar", JAR_ENV = "VELLUM_MC_JAR";
    public static final String MINECRAFT_VERSION = "26.3";

    /** Minecraft's missing texture (magenta and black checks), drawn for textures and sprites that do not exist. */
    public static final Texture MISSING = missingTexture();

    private static final String PROBE = "assets/minecraft/textures/gui/sprites/widget/button.png";
    private static final Pattern ASSET_ID = Pattern.compile("([a-z0-9_.-]{2,}):(.+)");

    private final List<Path> roots;
    private final List<FileSystem> archives;
    private final boolean minecraft;
    private final Map<String, Optional<Texture>> textures = new ConcurrentHashMap<>();

    private MinecraftAssets(List<Path> roots, List<FileSystem> archives, boolean minecraft) {
        this.roots = roots;
        this.archives = archives;
        this.minecraft = minecraft;
    }

    /** Opens {@code packs} (directories or zips containing {@code assets/}), searched in order, then the client jar. */
    public static MinecraftAssets open(List<Path> packs, Optional<Path> clientJar) {
        List<FileSystem> archives = new ArrayList<>();
        List<Path> roots = new ArrayList<>();
        for (Path pack : packs) roots.add(rootOf(pack, archives));
        clientJar.ifPresent(jar -> roots.add(rootOf(jar, archives)));
        return new MinecraftAssets(List.copyOf(roots), archives, clientJar.isPresent());
    }

    private static Path rootOf(Path pack, List<FileSystem> archives) {
        if (Files.isDirectory(pack)) return pack;
        try {
            FileSystem zip = FileSystems.newFileSystem(pack);
            archives.add(zip);
            return zip.getPath("/");
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open " + pack, e);
        }
    }

    /**
     * Finds a Minecraft {@value #MINECRAFT_VERSION} client jar that contains assets: the one named by
     * {@value #JAR_PROPERTY} or {@value #JAR_ENV}, else the jars ModDevGradle builds in this repository,
     * else the launcher's copy.
     */
    public static Optional<Path> findClientJar() {
        String explicit = System.getProperty(JAR_PROPERTY, System.getenv(JAR_ENV));
        if (explicit != null && !explicit.isBlank()) return Optional.of(Path.of(explicit)).filter(MinecraftAssets::hasAssets);
        return candidateJars().filter(MinecraftAssets::hasAssets).findFirst();
    }

    private static Stream<Path> candidateJars() {
        Path home = Path.of(System.getProperty("user.home"));
        List<Path> artifactDirs = new ArrayList<>();
        // Walk up from the working directory so this repository's build is found from any module or worktree.
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            artifactDirs.add(dir.resolve("common/versions/" + MINECRAFT_VERSION + "/build/moddev/artifacts")); // Stonecutter's
            artifactDirs.add(dir.resolve("common/build/moddev/artifacts"));
        }
        String launcherJar = "versions/" + MINECRAFT_VERSION + "/" + MINECRAFT_VERSION + ".jar";
        List<Path> launcher = new ArrayList<>(List.of(
                home.resolve("Library/Application Support/minecraft").resolve(launcherJar),
                home.resolve(".minecraft").resolve(launcherJar)));
        String appData = System.getenv("APPDATA");
        if (appData != null) launcher.add(Path.of(appData, ".minecraft").resolve(launcherJar));
        return Stream.concat(artifactDirs.stream().flatMap(MinecraftAssets::vanillaJars), launcher.stream());
    }

    /** ModDevGradle's {@code vanilla-26.3-*.jar} artifacts (merged or client), not the sources jar. */
    private static Stream<Path> vanillaJars(Path dir) {
        if (!Files.isDirectory(dir)) return Stream.empty();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> {
                String name = p.getFileName().toString();
                return name.startsWith("vanilla-" + MINECRAFT_VERSION) && name.endsWith(".jar") && !name.endsWith("-sources.jar");
            }).sorted().toList().stream();
        } catch (IOException e) {
            return Stream.empty();
        }
    }

    private static boolean hasAssets(Path jar) {
        if (!Files.isRegularFile(jar)) return false;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.getEntry(PROBE) != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** Whether the Minecraft client jar is in the stack, so fonts, sprites and textures are the real ones. */
    public boolean hasMinecraft() { return minecraft; }

    /** Whether {@code url} is an {@code ns:path} id rather than a file path. */
    public static boolean isAssetId(String url) {
        return ASSET_ID.matcher(url).matches();
    }

    /**
     * Turns an id into a resource URL: {@code assetUrl("minecraft:widget/button", "textures/gui/sprites/", ".png")}
     * is {@code minecraft:textures/gui/sprites/widget/button.png}. Ids without a namespace are {@code minecraft:}.
     */
    public static String assetUrl(String id, String prefix, String suffix) {
        Matcher m = ASSET_ID.matcher(id);
        return m.matches() ? m.group(1) + ":" + prefix + m.group(2) + suffix : "minecraft:" + prefix + id + suffix;
    }

    /**
     * Turns an id into the name Minecraft gives its translation key: {@code descriptionId("item", "diamond_sword")} is
     * {@code item.minecraft.diamond_sword}, with any {@code /} in the path a {@code .}. Ids without a namespace are
     * {@code minecraft:}.
     */
    public static String descriptionId(String kind, String id) {
        Matcher m = ASSET_ID.matcher(id);
        String namespace = m.matches() ? m.group(1) : "minecraft", path = m.matches() ? m.group(2) : id;
        return kind + "." + namespace + "." + path.replace('/', '.');
    }

    /** Finds a resource: an {@code ns:path} id in the first root that has it, or an existing file. */
    public Optional<Path> locate(String url) {
        Matcher id = ASSET_ID.matcher(url);
        if (id.matches()) {
            String path = "assets/" + id.group(1) + "/" + id.group(2);
            for (Path root : roots) {
                Path file = root.resolve(path);
                if (Files.isRegularFile(file)) return Optional.of(file);
            }
            return Optional.empty();
        }
        try {
            Path file = Path.of(url);
            return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    public Optional<byte[]> read(String url) {
        return locate(url).map(file -> {
            try {
                return Files.readAllBytes(file);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read " + url, e);
            }
        });
    }

    public Optional<String> readText(String url) {
        return read(url).map(bytes -> new String(bytes, StandardCharsets.UTF_8));
    }

    /** Reads a JSON object; malformed JSON is reported and treated as missing. */
    public Optional<JsonObject> json(String url) {
        return readText(url).flatMap(text -> {
            try {
                return Optional.of(JsonParser.parseString(text).getAsJsonObject());
            } catch (RuntimeException e) {
                warn("Malformed JSON in " + url + ": " + e.getMessage());
                return Optional.empty();
            }
        });
    }

    /** A texture by URL, e.g. {@code minecraft:textures/item/diamond.png}. */
    public Optional<Texture> texture(String url) {
        return textures.computeIfAbsent(url, this::loadTexture);
    }

    /** A GUI sprite by id: {@code ns:path} is {@code ns:textures/gui/sprites/path.png}, with its scaling metadata. */
    public Optional<Texture> sprite(String id) {
        return texture(assetUrl(id, "textures/gui/sprites/", ".png"));
    }

    /** Makes {@code texture} the image at {@code url} until {@link #release}, as Minecraft registers dynamic textures. */
    public void register(String url, Texture texture) {
        textures.put(url, Optional.of(texture));
    }

    public void release(String url) {
        textures.remove(url);
    }

    /**
     * The language file {@code lang/<language>.json} of every namespace on the stack, merged; earlier roots win, as
     * resource packs do.
     */
    public Map<String, String> translations(String language) {
        Map<String, String> merged = new HashMap<>();
        for (Path root : roots.reversed()) {
            Path assets = root.resolve("assets");
            if (!Files.isDirectory(assets)) continue;
            try (Stream<Path> namespaces = Files.list(assets)) {
                for (Path namespace : namespaces.toList()) {
                    Path file = namespace.resolve("lang").resolve(language + ".json");
                    if (!Files.isRegularFile(file)) continue;
                    JsonParser.parseString(Files.readString(file)).getAsJsonObject().entrySet().forEach(e -> {
                        if (e.getValue().isJsonPrimitive()) merged.put(e.getKey(), e.getValue().getAsString());
                    });
                }
            } catch (IOException | RuntimeException e) {
                warn("Cannot read the " + language + " language files in " + root + ": " + e.getMessage());
            }
        }
        return merged;
    }

    private Optional<Texture> loadTexture(String url) {
        Optional<byte[]> bytes = read(url);
        if (bytes.isEmpty()) return Optional.empty();
        try {
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes.get()));
            if (decoded == null) throw new IOException("not an image");
            JsonObject meta = json(url + ".mcmeta").orElseGet(JsonObject::new);
            BufferedImage image = firstFrame(toArgb(decoded), object(meta, "animation"));
            JsonObject texture = object(meta, "texture");
            boolean blur = texture != null && texture.has("blur") && texture.get("blur").getAsBoolean();
            return Optional.of(new Texture(image, blur, scaling(object(object(meta, "gui"), "scaling"))));
        } catch (IOException | RuntimeException e) {
            warn("Cannot load texture " + url + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    private static SpriteScaling scaling(JsonObject s) {
        if (s == null) return SpriteScaling.STRETCH;
        return switch (s.get("type").getAsString()) {
            case "tile" -> new SpriteScaling.Tile(s.get("width").getAsInt(), s.get("height").getAsInt());
            case "nine_slice" -> {
                // "border" is one size for all sides or {left, top, right, bottom}.
                JsonElement border = s.get("border");
                JsonObject sides = border.isJsonObject() ? border.getAsJsonObject() : null;
                int all = sides == null ? border.getAsInt() : 0;
                yield new SpriteScaling.NineSlice(s.get("width").getAsInt(), s.get("height").getAsInt(),
                        sides == null ? all : sides.get("left").getAsInt(),
                        sides == null ? all : sides.get("top").getAsInt(),
                        sides == null ? all : sides.get("right").getAsInt(),
                        sides == null ? all : sides.get("bottom").getAsInt(),
                        s.has("stretch_inner") && s.get("stretch_inner").getAsBoolean());
            }
            default -> SpriteScaling.STRETCH;
        };
    }

    /** Animated textures are vertical strips of frames; the preview shows the first, sized by vanilla's rule. */
    private static BufferedImage firstFrame(BufferedImage image, JsonObject animation) {
        if (animation == null) return image;
        int w = image.getWidth(), h = image.getHeight(), min = Math.min(w, h);
        boolean hasWidth = animation.has("width"), hasHeight = animation.has("height");
        int frameWidth = hasWidth ? animation.get("width").getAsInt() : hasHeight ? w : min;
        int frameHeight = hasHeight ? animation.get("height").getAsInt() : hasWidth ? h : min;
        return image.getSubimage(0, 0, Math.min(frameWidth, w), Math.min(frameHeight, h));
    }

    private static JsonObject object(JsonObject parent, String key) {
        return parent != null && parent.get(key) instanceof JsonObject o ? o : null;
    }

    private static BufferedImage toArgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_ARGB) return image;
        BufferedImage argb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = argb.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return argb;
    }

    private static Texture missingTexture() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) image.setRGB(x, y, (y < 8) ^ (x < 8) ? 0xFFF800F8 : 0xFF000000);
        }
        return new Texture(image, false, SpriteScaling.STRETCH);
    }

    static void warn(String message) {
        System.err.println("[vellum-preview] " + message);
    }

    @Override
    public void close() throws IOException {
        for (FileSystem archive : archives) archive.close();
    }
}
