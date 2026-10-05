package dev.vellum.preview;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.input.InputHandler;
import dev.vellum.engine.paint.Canvas;
import dev.vellum.preview.host.PreviewHost;

import java.util.function.Consumer;

/**
 * A page being previewed: loaded through the {@link PreviewHost}, then driven, painted and given input every
 * frame. Whatever the engine throws stops the page and becomes its {@link #error()}, shown in its place until the
 * next reload, so the previewer keeps running while the page or the engine is broken.
 */
final class PageScene implements Scene {
    private final PreviewHost host;
    /** JSON pushed to the page after loading ({@code --data}), or null. */
    private final String data;
    private String url;
    private Document document;
    private Throwable error;

    PageScene(PreviewHost host, String url, String data) {
        this.host = host;
        this.url = url;
        this.data = data;
        reload();
    }

    String url() { return url; }

    /** The loaded document, or null when loading failed. */
    Document document() { return document; }

    @Override
    public Throwable error() { return error; }

    /** Navigates to another page. */
    void open(String url) {
        this.url = url;
        reload();
    }

    void reload() {
        if (document != null) run(document::close);
        document = null;
        error = null;
        run(() -> {
            String html = host.loadText(url);
            if (html == null) throw new IllegalArgumentException("Page not found: " + url);
            document = Document.parse(host, url, html);
            // INTEGRATION: assumes the scripting runtime exposes the "data" channel as vellum.data.
            if (data != null) document.receive("data", data);
        });
    }

    @Override
    public void frame(double nowMs, float width, float height, float scale) {
        ifRunning(() -> {
            document.setViewport(width, height, scale);
            document.frame(nowMs);
        });
    }

    @Override
    public void paint(Canvas canvas) {
        ifRunning(() -> document.paint(canvas));
    }

    void input(Consumer<InputHandler> event) {
        ifRunning(() -> event.accept(document.input()));
    }

    /** True while a text field has focus, so the previewer leaves typing keys to the page. */
    boolean wantsKeyboard() {
        return document != null && error == null && document.input().wantsKeyboard();
    }

    private void ifRunning(Runnable step) {
        if (document != null && error == null) run(step);
    }

    private void run(Runnable step) {
        try {
            step.run();
        } catch (RuntimeException | StackOverflowError e) {
            error = e;
            host.reportError("Preview of " + url + " stopped", e);
        }
    }
}
