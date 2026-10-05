package dev.vellum.mod;

import dev.vellum.mod.net.MessagePayload;
import dev.vellum.mod.net.OpenPayload;
import dev.vellum.mod.platform.Services;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
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
 * world), {@code client.} keys on a client, {@code limits.} keys wherever pages run (the engine's caps).
 */
public final class VellumConfig {
    public static final String FILE_NAME = "vellum.properties";

    /** What a client does when a server opens a page on it. */
    public enum ServerPages { ALLOW, ASK, BLOCK }

    /** What a client does when a page links to a web page. */
    public enum WebLinks { ASK, BLOCK }

    private static final Map<String, Setting<?>> SETTINGS = new LinkedHashMap<>();
    private static final List<Section<?>> SECTIONS = new ArrayList<>();

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
            "Messages a server's page may send at once (per screen, across reloads and links).");
    public static final Setting<Double> CLIENT_MESSAGES_PER_SECOND = decimal("client.messagesPerSecond", 20, 0.1, 10_000,
            "Messages per second a server's page may send after its burst. Extra messages are dropped.");
    public static final Setting<String> CLIENT_FORCE_CLOSE_KEY = text("client.forceCloseKey", "key.keyboard.escape",
            "With Shift held, this key always closes a Vellum screen; pages never see it. A key name as in options.txt.");
    public static final Setting<Integer> CLIENT_FORCE_CLOSE_PRESSES = integer("client.forceClosePresses", 3, 2, 10,
            "Escape pressed this many times within 1.5 seconds closes a Vellum screen, even when the page keeps Escape.");
    public static final Setting<Integer> CLIENT_REOPEN_STRIKES = integer("client.reopenStrikes", 3, 1, 100,
            "A server that reopens its page within a second of you closing it with Escape, this many times in a row within 15 seconds, is stopped from opening pages for a while.");
    public static final Setting<Integer> CLIENT_REOPEN_BLOCK_SECONDS = integer("client.reopenBlockSeconds", 30, 1, 3600,
            "How long, in seconds, a server stopped for reopening pages stays stopped.");
    public static final Setting<Double> CLIENT_SOUNDS_PER_SECOND = decimal("client.soundsPerSecond", 8, 0, 1000,
            "Sounds per second a page may play with vellum.playSound (a burst of as many, then this rate). 0 mutes pages.");
    public static final Setting<Double> CLIENT_MAX_SOUND_VOLUME = decimal("client.maxSoundVolume", 1, 0, 1,
            "Loudest volume a page may play a sound at, 0 to 1 (then scaled by your sound settings).");
    public static final Setting<WebLinks> CLIENT_WEB_LINKS = choice("client.webLinks", WebLinks.ASK,
            "Links to web pages: ask (confirm before opening, and only right after a click or key press) or block.");
    public static final Setting<Boolean> CLIENT_TYPING_NOTICE = bool("client.typingNotice", true,
            "Show a notice while you type into a page that a server opened.");
    public static final Setting<Boolean> CLIENT_REDUCED_MOTION = bool("client.reducedMotion", false,
            "Pages see prefers-reduced-motion: reduce and should skip or shorten animations.");

    private static final Map<String, String> unknown = new LinkedHashMap<>();

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
            String text = (fresh ? "# Vellum settings. Delete a line to get its default back.\n" : "\n") + missing;
            Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            Constants.LOG.warn("Vellum: cannot write {}: {}", file, e.toString());
        }
    }

    /**
     * Sets every setting from {@code p}, its default where {@code p} lacks it or has a bad value, and returns a
     * warning for each bad value and unknown key.
     */
    public static synchronized List<String> apply(Properties p) {
        List<String> warnings = new ArrayList<>();
        unknown.clear();
        for (Setting<?> s : SETTINGS.values()) {
            String raw = p.getProperty(s.key);
            String error = s.set(raw);
            if (error != null) warnings.add(s.key + "=" + raw + " " + error + "; using " + s.defaultText());
        }
        for (String key : p.stringPropertyNames()) {
            if (SETTINGS.containsKey(key)) continue;
            unknown.put(key, p.getProperty(key));
            if (!key.startsWith("limits.")) warnings.add("unknown key " + key);
        }
        for (Section<?> section : SECTIONS) section.rebuild();
        return warnings;
    }

    /** The lines to add for the settings {@code p} lacks: a comment and {@code key=default} each. */
    private static String missing(Properties p) {
        StringBuilder out = new StringBuilder();
        for (Setting<?> s : SETTINGS.values()) {
            if (p.containsKey(s.key)) continue;
            if (!s.comment.isEmpty()) out.append("# ").append(s.comment).append('\n');
            out.append(s.key).append('=').append(s.defaultText()).append("\n\n");
        }
        return out.toString();
    }

    /** {@code limits.*} keys the file has that no section reads, for engine caps not wired in yet. */
    public static synchronized Map<String, String> unreadLimits() {
        Map<String, String> limits = new LinkedHashMap<>();
        unknown.forEach((k, v) -> {
            if (k.startsWith("limits.")) limits.put(k, v);
        });
        return Collections.unmodifiableMap(limits);
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

    public static Setting<Long> longInteger(String key, long def, long min, long max, String comment) {
        return add(new Setting<>(key, def, comment, raw -> {
            long v;
            try {
                v = Long.parseLong(raw);
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

    private static String fmt(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
    }

    // ---- Record sections ----

    /**
     * A record whose components are settings {@code prefix.<component>}, with {@code defaults} as their defaults:
     * for a block of caps another module defines (the engine's {@code Limits}). Components may be int, long, double
     * or boolean, and numbers must not be negative. Register sections before {@link #load()} (in a static field here).
     */
    public static <R extends Record> Section<R> section(String prefix, R defaults, Map<String, String> comments) {
        Section<R> section = new Section<>(prefix, defaults, comments);
        SECTIONS.add(section);
        return section;
    }

    /** The settings of a record, and the record they currently make. */
    public static final class Section<R extends Record> {
        private final R defaults;
        private final List<Setting<?>> settings = new ArrayList<>();
        private final RecordComponent[] components;
        private volatile R value;

        private Section(String prefix, R defaults, Map<String, String> comments) {
            this.defaults = defaults;
            this.value = defaults;
            this.components = defaults.getClass().getRecordComponents();
            for (RecordComponent c : components) {
                Object def = read(c, defaults);
                String key = prefix + "." + c.getName(), comment = comments.getOrDefault(c.getName(), "");
                settings.add(switch (def) {
                    case Integer i -> integer(key, i, 0, Integer.MAX_VALUE, comment);
                    case Long l -> longInteger(key, l, 0, Long.MAX_VALUE, comment);
                    case Double d -> decimal(key, d, 0, Double.MAX_VALUE, comment);
                    case Boolean b -> bool(key, b, comment);
                    default -> throw new IllegalArgumentException("Unsupported setting type " + c.getType() + " for " + key);
                });
            }
        }

        public R get() {
            return value;
        }

        @SuppressWarnings("unchecked")
        private void rebuild() {
            Object[] args = new Object[components.length];
            Class<?>[] types = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                args[i] = settings.get(i).get();
                types[i] = components[i].getType();
            }
            try {
                value = (R) defaults.getClass().getDeclaredConstructor(types).newInstance(args);
            } catch (ReflectiveOperationException | RuntimeException e) {
                Constants.LOG.warn("Vellum: settings for {} were refused ({}); using defaults", defaults.getClass().getSimpleName(), e.toString());
                value = defaults;
            }
        }

        private static Object read(RecordComponent c, Record r) {
            try {
                return c.getAccessor().invoke(r);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
