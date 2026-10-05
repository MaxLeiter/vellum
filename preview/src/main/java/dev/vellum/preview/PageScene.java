package dev.vellum.preview;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Viewport;
import dev.vellum.engine.host.Host;
import dev.vellum.engine.input.InputHandler;
import dev.vellum.engine.paint.Canvas;
import dev.vellum.preview.host.PreviewHost;

import java.util.function.Consumer;

/**
 * A page being previewed: loaded through the {@link PreviewHost}, then driven, painted and given input every
 * frame. When the engine fails the document stops ({@link Document#error()}), and the failure is shown in its place
 * until the next reload, so the previewer keeps running while the page or the engine is broken.
 */
final class PageScene implements Scene {
    private final PreviewHost host;
    /** JSON given to the page as {@code vellum.data} before its scripts run ({@code --data}), or null. */
    private final String data;
    private String url;
    private Document document;
    /** Why there is no document (the page was not found), or null. */
    private Throwable loadError;
    /** The viewport of the last frame (the window's size and scale), which a reload starts the page in. */
    private Viewport viewport;

    /** Loads the page shown in {@code viewport}, so its scripts see that viewport from the start. */
    PageScene(PreviewHost host, String url, String data, Viewport viewport) {
        this.host = host;
        this.url = url;
        this.data = data;
        this.viewport = viewport;
        reload();
    }

    String url() { return url; }

    /** The loaded document, or null when the page was not found. */
    Document document() { return document; }

    @Override
    public Throwable error() { return document != null ? document.error() : loadError; }

    /** Navigates to another page. */
    void open(String url) {
        this.url = url;
        reload();
    }

    void reload() {
        if (document != null) document.close();
        document = null;
        String html = host.loadText(url);
        if (html != null) {
            loadError = null;
            document = Document.parse(host, url, html, data, viewport);
        } else {
            loadError = new IllegalArgumentException("Page not found: " + url);
            host.log(Host.LogLevel.ERROR, loadError.getMessage());
        }
    }

    @Override
    public void frame(double nowMs, float width, float height, float scale) {
        viewport = new Viewport(width, height, scale);
        if (document == null) return;
        document.setViewport(width, height, scale);
        document.frame(nowMs);
    }

    @Override
    public void paint(Canvas canvas) {
        if (document != null) document.paint(canvas);
    }

    @Override
    public boolean needsFrame(double nowMs) {
        return document != null && document.needsFrame(nowMs);
    }

    /** Input for the page; the input handler ignores it once the page has stopped. */
    void input(Consumer<InputHandler> event) {
        if (document != null) event.accept(document.input());
    }

    /** True while a text field has focus, so the previewer leaves typing keys to the page. */
    boolean wantsKeyboard() {
        return document != null && document.input().wantsKeyboard();
    }
}
