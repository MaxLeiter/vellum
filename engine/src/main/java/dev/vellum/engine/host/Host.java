package dev.vellum.engine.host;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.script.ScriptRuntime;
import dev.vellum.engine.style.Cursor;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Everything the engine needs from its environment. Minecraft, the test harness and the standalone previewer each
 * provide one. Only {@link #fonts()} and {@link #loadText} are required; the rest have no-op defaults.
 */
public interface Host {
    FontMetrics fonts();

    /**
     * Loads a text resource (stylesheet, script, HTML). {@code url} is already resolved against the document URL.
     * Returns null when missing; the engine reports that through {@link #log}.
     */
    String loadText(String url);

    /** Resolves {@code relative} against {@code base}. The default handles {@code ns:path} ids and plain paths. */
    default String resolveUrl(String base, String relative) {
        return Urls.resolve(base, relative);
    }

    /**
     * The host's own replaced elements, by tag, each with the factory of its content (Minecraft: {@code item},
     * {@code slot}, {@code entity}, {@code player-head}). The engine provides {@code img}, {@code sprite} and
     * {@code canvas} itself; these tags cannot be overridden. Asked once per document.
     */
    default Map<String, Function<Element, ReplacedContent>> replacedElements() { return Map.of(); }

    /**
     * The natural size {width, height} in px of the texture at {@code url} (already resolved), or null when unknown.
     * Images use it, and {@code background-size}. The array may be shared: do not modify it.
     */
    default float[] imageSize(String url) { return null; }

    /** The natural size {width, height} in px of a GUI sprite, or null when unknown. As {@link #imageSize}. */
    default float[] spriteSize(String id) { return null; }

    /** The pixels for a {@code <canvas>} of this size (1 to 2048 px a side). By default kept in memory, undrawn. */
    default PixelSurface createSurface(int width, int height) { return new ArraySurface(width, height); }

    /**
     * Minecraft text for {@code <mc-text json>}: a chat component (JSON) as runs of text, each with the CSS that
     * styles it (empty for none), or null when the host cannot read it (the element then keeps its content).
     */
    default List<TextRun> formatText(String json) { return null; }

    /** A run of formatted text: its text, and CSS declarations for its style ("" for none). */
    record TextRun(String text, String css) {}

    /** Creates the script runtime for a document, or null to disable scripting. */
    default ScriptRuntime createScriptRuntime(Document document) { return null; }

    default void log(LogLevel level, String message) {
        (level == LogLevel.ERROR || level == LogLevel.WARN ? System.err : System.out).println("[vellum] " + message);
    }

    default void reportError(String message, Throwable error) {
        log(LogLevel.ERROR, message + ": " + error);
    }

    default void setCursor(Cursor cursor) {}

    default void playSound(String id, float volume, float pitch) {}

    default String getClipboard() { return ""; }

    default void setClipboard(String text) {}

    /** Called for {@code vellum.close()} / {@code window.close()}. */
    default void close() {}

    /** Called for {@code vellum.send(channel, data)}: forward a JSON message to the server or the owning mod. */
    default void send(String channel, String json) {}

    /** Translates a key (Minecraft language files in game); returns the key itself when unknown. */
    default String translate(String key, String... args) { return key; }

    /** Whether the user asked for reduced motion ({@code prefers-reduced-motion: reduce}). */
    default boolean prefersReducedMotion() { return false; }

    /** Called for {@code <a href>} activation and {@code location.href = ...}; hosts may navigate or ignore. */
    default void navigate(String url) {}

    enum LogLevel { DEBUG, INFO, WARN, ERROR }
}
