package dev.vellum.mod;

import dev.vellum.engine.Limits;
import dev.vellum.mod.net.MessagePayload;
import dev.vellum.mod.net.OpenPayload;
import dev.vellum.mod.platform.Services;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

/**
 * Vellum's settings, from {@code config/vellum.properties}, read on both sides at startup. The first start writes the
 * file with every key, its default and a comment; later starts append keys the file lacks. A missing, malformed or
 * out-of-range value falls back to its default with a warning in the log, and loading never fails.
 *
 * <p>Keys starting {@code server.} matter on a server (a dedicated one, or the integrated server of a singleplayer
 * world), {@code client.} keys on a client, {@code limits.} keys wherever pages run: one per field of the engine's
 * {@link Limits}, installed with {@link Limits#setCurrent} on every load.
 */
public final class VellumConfig {
    public static final String FILE_NAME = "vellum.properties";

    /** What a client does when a server opens a page on it. */
    public enum ServerPages { ALLOW, ASK, BLOCK }

    /** What a client does when a page links to a web page. */
    public enum WebLinks { ASK, BLOCK }

    private static final Map<String, Setting<?>> SETTINGS = new LinkedHashMap<>();

    // ---- Server ----

    public static final Setting<Integer> SERVER_MAX_INLINE_HTML = integer("server.maxInlineHtmlChars", OpenPayload.MAX_HTML, 1, OpenPayload.MAX_HTML,
            "Largest inline page (VellumServer.openInline) in characters. Larger ones throw. At most " + OpenPayload.MAX_HTML + ".");
    public static final Setting<Integer> SERVER_MAX_DATA = integer("server.maxDataChars", OpenPayload.MAX_DATA, 1, OpenPayload.MAX_DATA,
            "Largest vellum.data a server sends (open, push), as JSON characters. Larger ones throw. At most " + OpenPayload.MAX_DATA + ".");
    public static final Setting<Integer> SERVER_MAX_MESSAGE = integer("server.maxMessageChars", MessagePayload.MAX_JSON, 1, MessagePayload.MAX_JSON,
            "Largest message a page may send to the server, as JSON characters. Longer ones are dropped. At most " + MessagePayload.MAX_JSON + ".");
    public static final Setting<Integer> SERVER_MAX_MESSAGE_DEPTH = integer("server.maxMessageDepth", 32, 1, 255,
            "Deepest nesting of arrays and objects in a message from a page. Deeper ones are dropped.");
    public static final Setting<Integer> SERVER_MESSAGE_BURST = integer("server.messageBurst", 40, 1, 10_000,
            "Messages a page may send at once before the rate limit applies (per session).");
    public static final Setting<Double> SERVER_MESSAGES_PER_SECOND = decimal("server.messagesPerSecond", 20, 0.1, 10_000,
            "Messages per second a page may send after its burst (per session). Extra messages are dropped.");
    public static final Setting<Integer> SERVER_MAX_SESSIONS = integer("server.maxSessionsPerPlayer", 8, 1, 1024,
            "Open sessions a player may have. Opening one more ends the oldest, so a client that never reports closed screens cannot pile them up.");

    // ---- Client ----

    public static final Setting<ServerPages> CLIENT_SERVER_PAGES = choice("client.serverPages", ServerPages.ALLOW,
            "Pages a server opens: allow, ask (once per server visit) or block.");
    public static final Setting<Integer> CLIENT_MAX_INLINE_HTML = integer("client.maxInlineHtmlChars", OpenPayload.MAX_HTML, 1, OpenPayload.MAX_HTML,
            "Largest inline page this client shows, in characters. Larger ones are refused.");
    public static final Setting<Integer> CLIENT_MAX_DATA = integer("client.maxDataChars", OpenPayload.MAX_DATA, 1, OpenPayload.MAX_DATA,
            "Largest vellum.data this client accepts from a server, as JSON characters. Larger data is dropped.");
    public static final Setting<Integer> CLIENT_MAX_DATA_DEPTH = integer("client.maxDataDepth", 64, 1, 512,
            "Deepest nesting of arrays and objects in data from a server. Deeper data is dropped.");
    public static final Setting<Integer> CLIENT_OPEN_BURST = integer("client.openBurst", 5, 1, 1000,
            "Pages a server may open at once before the rate limit applies. Later ones wait; only the newest is kept.");
    public static final Setting<Double> CLIENT_OPENS_PER_SECOND = decimal("client.opensPerSecond", 1, 0.01, 1000,
            "Pages per second a server may open after its burst.");
    public static final Setting<Integer> CLIENT_MESSAGE_BURST = integer("client.messageBurst", 20, 1, 10_000,
            "Messages a page may send with vellum.send at once (per screen, across reloads and links).");
    public static final Setting<Double> CLIENT_MESSAGES_PER_SECOND = decimal("client.messagesPerSecond", 20, 0.1, 10_000,
            "Messages per second a page may send after its burst. Extra messages are dropped and vellum.send returns false.");
    public static final Setting<String> CLIENT_FORCE_CLOSE_KEY = text("client.forceCloseKey", "key.keyboard.escape",
            "With Shift held, this key always closes a Vellum screen; pages never see it. A key name as in options.txt.");
    public static final Setting<Integer> CLIENT_FORCE_CLOSE_PRESSES = integer("client.forceClosePresses", 3, 2, 10,
            "Escape pressed this many times within 1.5 seconds closes a Vellum screen, even when the page keeps Escape.");
    public static final Setting<Integer> CLIENT_REOPEN_STRIKES = integer("client.reopenStrikes", 3, 1, 100,
            "A server that reopens its page within a second of you closing it with Escape, this many times in a row within 15 seconds, is stopped from opening pages for a while.");
    public static final Setting<Integer> CLIENT_REOPEN_BLOCK_SECONDS = integer("client.reopenBlockSeconds", 30, 1, 3600,
            "How long, in seconds, a server stopped for reopening pages stays stopped.");
    public static final Setting<Double> CLIENT_SOUNDS_PER_SECOND = decimal("client.soundsPerSecond", 8, 0, 1000,
            "Sounds per second a page may play with vellum.playSound (per screen, a burst of as many, then this rate). 0 mutes pages.");
    public static final Setting<Double> CLIENT_MAX_SOUND_VOLUME = decimal("client.maxSoundVolume", 1, 0, 1,
            "Loudest volume a page may play a sound at, 0 to 1 (then scaled by your sound settings).");
    public static final Setting<WebLinks> CLIENT_WEB_LINKS = choice("client.webLinks", WebLinks.ASK,
            "Links to web pages: ask (confirm before opening, and only right after a click or key press) or block.");
    public static final Setting<Boolean> CLIENT_TYPING_NOTICE = bool("client.typingNotice", true,
            "Show a notice while you type into a page that a server opened.");
    public static final Setting<Boolean> CLIENT_REDUCED_MOTION = bool("client.reducedMotion", false,
            "Pages see prefers-reduced-motion: reduce and should skip or shorten animations.");

    // ---- Limits ----

    static {
        for (String name : Limits.names()) limit(name);
    }

    /** The heading written above each group of keys, by key prefix. */
    private static final Map<String, String> GROUPS = Map.of(
            "server", "What this server sends to clients and accepts from them. Read by dedicated servers and by the server inside a singleplayer world.",
            "client", "What this client lets pages do, and which of a server's pages it shows.",
            "limits", "The engine's caps on every page, a mod's or a server's. Each must be at least 1; set too low, ordinary pages stop working.");

    private VellumConfig() {}

    // ---- Loading ----

    /** Reads the file in the loader's config directory, writing defaults for what it lacks. */
    public static void load() {
        load(Services.PLATFORM.configDir().resolve(FILE_NAME));
    }

    /** Reads {@code file}, then appends every key it lacks with its default and comment. Never throws. */
    public static void load(Path file) {
        Properties p = new Properties();
        try {
            if (Files.exists(file)) {
                try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    p.load(in);
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            Constants.LOG.warn("Vellum: cannot read {}, using defaults: {}", file, e.toString());
        }
        for (String warning : apply(p)) Constants.LOG.warn("Vellum: {}: {}", file.getFileName(), warning);
        try {
            String missing = missing(p);
            if (missing.isEmpty()) return;
            Files.createDirectories(file.toAbsolutePath().getParent());
            boolean fresh = !Files.exists(file);
            String text = (fresh ? HEADER : "\n") + missing;
            Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            Constants.LOG.warn("Vellum: cannot write {}: {}", file, e.toString());
        }
    }

    private static final String HEADER = comment("Vellum settings, read when the game starts. A missing or invalid value "
            + "falls back to its default with a warning in the log. Delete a line to get its default back.") + "\n";

    /**
     * Sets every setting from {@code p}, its default where {@code p} lacks it or has a bad value, installs the
     * engine's limits they make, and returns a warning for each bad value and unknown key.
     */
    public static synchronized List<String> apply(Properties p) {
        List<String> warnings = new ArrayList<>();
        for (Setting<?> s : SETTINGS.values()) {
            String raw = p.getProperty(s.key);
            String error = s.set(raw);
            if (error != null) warnings.add(s.key + "=" + raw + " " + error + "; using " + s.defaultText());
        }
        for (String key : p.stringPropertyNames()) if (!SETTINGS.containsKey(key)) warnings.add("unknown key " + key);
        Limits limits = Limits.DEFAULTS;
        for (String name : Limits.names()) limits = limits.with(name, (Long) SETTINGS.get(LIMITS + name).get());
        Limits.setCurrent(limits);
        return warnings;
    }

    /**
     * The lines to add for the settings {@code p} lacks: a comment and {@code key=default} each, under the heading of
     * their group when {@code p} has no key of that group yet.
     */
    private static String missing(Properties p) {
        StringBuilder out = new StringBuilder();
        String group = null;
        for (Setting<?> s : SETTINGS.values()) {
            if (p.containsKey(s.key)) continue;
            String g = s.key.substring(0, s.key.indexOf('.'));
            if (!g.equals(group)) {
                group = g;
                boolean started = p.stringPropertyNames().stream().anyMatch(k -> k.startsWith(g + "."));
                if (!started && GROUPS.containsKey(g)) {
                    out.append("# ==== ").append(g.substring(0, 1).toUpperCase(Locale.ROOT)).append(g.substring(1)).append(" ====\n")
                            .append(comment(GROUPS.get(g))).append('\n');
                }
            }
            if (!s.comment.isEmpty()) out.append(comment(s.comment));
            out.append(s.key).append('=').append(s.defaultText()).append("\n\n");
        }
        return out.toString();
    }

    /** {@code text} as comment lines of at most 100 characters. */
    private static String comment(String text) {
        StringBuilder out = new StringBuilder();
        StringBuilder line = new StringBuilder("#");
        for (String word : text.split(" ")) {
            if (line.length() > 1 && line.length() + 1 + word.length() > 100) {
                out.append(line).append('\n');
                line.setLength(1);
            }
            line.append(' ').append(word);
        }
        return out.append(line).append('\n').toString();
    }

    // ---- Settings ----

    /** One key: its default, its current value, and how it is read. */
    public static final class Setting<T> {
        final String key;
        final T def;
        final String comment;
        private final Function<String, T> parse;
        private final Function<T, String> format;
        private volatile T value;

        private Setting(String key, T def, String comment, Function<String, T> parse, Function<T, String> format) {
            this.key = key;
            this.def = def;
            this.comment = comment;
            this.parse = parse;
            this.format = format;
            this.value = def;
        }

        public T get() {
            return value;
        }

        public String key() {
            return key;
        }

        public T defaultValue() {
            return def;
        }

        /** Sets the value from text (the default for null); returns why the text was refused, or null. */
        private @Nullable String set(@Nullable String raw) {
            if (raw == null) {
                value = def;
                return null;
            }
            try {
                value = parse.apply(raw.strip());
                return null;
            } catch (RuntimeException e) {
                value = def;
                return e.getMessage() == null ? "is not valid" : e.getMessage();
            }
        }

        String defaultText() {
            return format.apply(def);
        }
    }

    private static <T> Setting<T> add(Setting<T> s) {
        if (SETTINGS.put(s.key, s) != null) throw new IllegalStateException("Duplicate setting " + s.key);
        return s;
    }

    public static Setting<Integer> integer(String key, int def, int min, int max, String comment) {
        return add(new Setting<>(key, def, comment, raw -> {
            int v;
            try {
                v = Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("is not a whole number");
            }
            if (v < min || v > max) throw new IllegalArgumentException("is outside " + min + ".." + max);
            return v;
        }, String::valueOf));
    }

    public static Setting<Double> decimal(String key, double def, double min, double max, String comment) {
        return add(new Setting<>(key, def, comment, raw -> {
            double v;
            try {
                v = Double.parseDouble(raw);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("is not a number");
            }
            if (!(v >= min && v <= max)) throw new IllegalArgumentException("is outside " + fmt(min) + ".." + fmt(max));
            return v;
        }, VellumConfig::fmt));
    }

    public static Setting<Boolean> bool(String key, boolean def, String comment) {
        return add(new Setting<>(key, def, comment, raw -> switch (raw.toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("is not true or false");
        }, String::valueOf));
    }

    public static Setting<String> text(String key, String def, String comment) {
        return add(new Setting<>(key, def, comment, raw -> {
            if (raw.isEmpty()) throw new IllegalArgumentException("is empty");
            return raw;
        }, v -> v));
    }

    public static <E extends Enum<E>> Setting<E> choice(String key, E def, String comment) {
        E[] values = def.getDeclaringClass().getEnumConstants();
        StringBuilder names = new StringBuilder();
        for (E e : values) names.append(names.isEmpty() ? "" : ", ").append(e.name().toLowerCase(Locale.ROOT));
        return add(new Setting<>(key, def, comment, raw -> {
            for (E e : values) if (e.name().equalsIgnoreCase(raw)) return e;
            throw new IllegalArgumentException("is not one of " + names);
        }, e -> e.name().toLowerCase(Locale.ROOT)));
    }

    private static final String LIMITS = "limits.";

    /** {@code limits.<name>}: a whole number that {@link Limits#with} accepts for {@code name}. */
    private static Setting<Long> limit(String name) {
        return add(new Setting<>(LIMITS + name, Limits.DEFAULTS.get(name), Limits.describe(name), raw -> {
            long v;
            try {
                v = Long.parseLong(raw);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("is not a whole number");
            }
            try {
                Limits.DEFAULTS.with(name, v);
            } catch (IllegalArgumentException e) {
                String why = String.valueOf(e.getMessage());
                throw new IllegalArgumentException(why.startsWith(name + " ") ? why.substring(name.length() + 1) : why);
            }
            return v;
        }, String::valueOf));
    }

    private static String fmt(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
    }
}
