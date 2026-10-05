package dev.vellum.engine.testing;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.host.FontMetrics;
import dev.vellum.engine.host.Host;
import dev.vellum.engine.script.ScriptRuntime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A deterministic in-memory host for tests: Minecraft-like font metrics, resources from a map, recorded logs,
 * errors, sounds and messages. Errors are recorded, and {@link #failOnError} makes them throw (the default) so
 * tests notice broken listeners and scripts.
 */
public class TestHost implements Host {
    public final Map<String, String> resources = new HashMap<>();
    public final List<String> logs = new ArrayList<>();
    public final List<String> errors = new ArrayList<>();
    public final List<String> sounds = new ArrayList<>();
    public final List<String[]> sent = new ArrayList<>();
    public final List<String> navigations = new ArrayList<>();
    public String clipboard = "";
    public boolean failOnError = true;
    public boolean closed;
    /** Factory for the script runtime; null disables scripting. Tests of the script package set this. */
    public Function<Document, ScriptRuntime> scripting;
    private final FontMetrics fonts = new TestFonts();

    public TestHost resource(String url, String text) {
        resources.put(url, text);
        return this;
    }

    /** Parses {@code html} as {@code test:page.html} and runs one frame at t=0 on a 320×240 viewport. */
    public Document load(String html) {
        return load(html, 320, 240);
    }

    public Document load(String html, float width, float height) {
        Document doc = Document.parse(this, "test:page.html", html);
        doc.setViewport(width, height, 2);
        doc.frame(0);
        return doc;
    }

    @Override
    public FontMetrics fonts() { return fonts; }

    @Override
    public String loadText(String url) { return resources.get(url); }

    @Override
    public ScriptRuntime createScriptRuntime(Document document) {
        return scripting == null ? null : scripting.apply(document);
    }

    @Override
    public void log(LogLevel level, String message) { logs.add(level + ": " + message); }

    @Override
    public void reportError(String message, Throwable error) {
        errors.add(message + ": " + error);
        if (failOnError) throw new AssertionError(message, error);
    }

    @Override
    public void playSound(String id, float volume, float pitch) { sounds.add(id); }

    @Override
    public String getClipboard() { return clipboard; }

    @Override
    public void setClipboard(String text) { clipboard = text; }

    @Override
    public void close() { closed = true; }

    @Override
    public void send(String channel, String json) { sent.add(new String[] {channel, json}); }

    @Override
    public void navigate(String url) { navigations.add(url); }
}
