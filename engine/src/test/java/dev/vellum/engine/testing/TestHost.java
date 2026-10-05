package dev.vellum.engine.testing;

import dev.vellum.engine.Limits;
import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Viewport;
import dev.vellum.engine.host.ArraySurface;
import dev.vellum.engine.host.FontMetrics;
import dev.vellum.engine.host.Host;
import dev.vellum.engine.host.PixelSurface;
import dev.vellum.engine.host.ReplacedContent;
import dev.vellum.engine.script.ScriptRuntime;
import dev.vellum.engine.script.Scripting;
import dev.vellum.engine.style.Cursor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A deterministic in-memory host for tests: Minecraft-like font metrics ({@link TestFonts}), the Rhino script
 * runtime, resources and image sizes from maps, in-memory canvases, and recorded logs, errors, sounds, cursors and
 * messages. Errors are recorded and, unless {@link #recordErrors} was called, thrown, so tests notice broken
 * listeners and scripts. {@link #load} gives a {@link Page} to drive.
 */
public class TestHost implements Host {
    public final Map<String, String> resources = new HashMap<>();
    public final List<String> logs = new ArrayList<>();
    public final List<String> errors = new ArrayList<>();
    public final List<String> sounds = new ArrayList<>();
    public final List<Cursor> cursors = new ArrayList<>();
    public final List<String[]> sent = new ArrayList<>();
    public final List<String> navigations = new ArrayList<>();
    /** The host's replaced elements by tag (none unless a test adds them). */
    public final Map<String, Function<Element, ReplacedContent>> replaced = new HashMap<>();
    /** Natural sizes of textures by URL, for {@link #imageSize}. */
    public final Map<String, float[]> imageSizes = new HashMap<>();
    public String clipboard = "";
    public boolean failOnError = true;
    public boolean closed;
    /** Factory for the script runtime, as in game; null disables scripting. */
    public Function<Document, ScriptRuntime> scripting = Scripting.rhino();
    /** The limits of pages this host loads. */
    public Limits limits = Limits.DEFAULTS;
    private final FontMetrics fonts = new TestFonts();

    public TestHost resource(String url, String text) {
        resources.put(url, text);
        return this;
    }

    /** Keeps errors in {@link #errors} instead of failing the test with the first one. */
    public TestHost recordErrors() {
        failOnError = false;
        return this;
    }

    /** Parses {@code html} as {@code test:page.html} on a 320×240 viewport at GUI scale 2 and runs a frame at t=0. */
    public Page load(String html) {
        return load(html, 320, 240);
    }

    public Page load(String html, float width, float height) {
        return new Page(this, Document.parse(this, "test:page.html", html, null, new Viewport(width, height, 2))).frame(0);
    }

    /** Loads pages with {@code limits} instead of the defaults. */
    public TestHost limits(Limits limits) {
        this.limits = limits;
        return this;
    }

    @Override
    public FontMetrics fonts() { return fonts; }

    @Override
    public Limits limits() { return limits; }

    @Override
    public Map<String, Function<Element, ReplacedContent>> replacedElements() { return replaced; }

    @Override
    public String loadText(String url) { return resources.get(url); }

    @Override
    public float[] imageSize(String url) { return imageSizes.get(url); }

    /** Canvas pixels in memory, drawn as the texture {@code surface:WxH}. */
    @Override
    public PixelSurface createSurface(int width, int height) {
        return new ArraySurface(width, height) {
            @Override
            public String url() {
                return "surface:" + width + "x" + height;
            }
        };
    }

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
    public void setCursor(Cursor cursor) { cursors.add(cursor); }

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
