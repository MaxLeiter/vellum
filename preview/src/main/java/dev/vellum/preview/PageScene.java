package dev.vellum.preview;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Viewport;
import dev.vellum.engine.host.Host;
import dev.vellum.engine.input.Accessible;
import dev.vellum.engine.input.InputHandler;
import dev.vellum.engine.input.Narration;
import dev.vellum.engine.input.Tooltip;
import dev.vellum.engine.paint.Canvas;
import dev.vellum.preview.host.PreviewHost;

import java.io.PrintStream;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A page being previewed: loaded through the {@link PreviewHost}, then driven, painted (with its tooltips) and given
 * input every frame. When the engine fails the document stops ({@link Document#error()}), and the failure is shown
 * in its place until the next reload, so the previewer keeps running while the page or the engine is broken.
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
    /** Where {@code --narrate} prints what a narrator would be given, or null. */
    private PrintStream narration;
    /** Whether the page's title was printed, and the focus and pointer narration last printed. */
    private boolean titleNarrated;
    private String focusNarrated, pointerNarrated;

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

    /**
     * Prints what a narrator would be given to {@code out} after every frame ({@code --narrate}): the page's title once
     * it loads, each live region announcement, and the focused and hovered elements when what they read changes, as
     * {@link Accessible#describe()} says them. In game the pointer's is read once it rests; here at once.
     */
    void narrateTo(PrintStream out) {
        narration = out;
    }

    void reload() {
        if (document != null) document.close();
        document = null;
        titleNarrated = false;
        focusNarrated = pointerNarrated = null;
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
        if (narration != null && document.error() == null) narrate();
    }

    private void narrate() {
        Narration n = document.input().narration();
        if (!titleNarrated) {
            titleNarrated = true;
            if (!document.title().isEmpty()) narration.println("[narrate] title: " + document.title());
        }
        for (Narration.Announcement a : n.announcements()) {
            narration.println("[narrate] " + (a.interrupt() ? "at once: " : "live: ") + a.text());
        }
        focusNarrated = narrate("focus", n.focused(), focusNarrated);
        pointerNarrated = narrate("pointer", n.hovered(), pointerNarrated);
    }

    /** Prints what {@code a} reads as when it is not what was printed last; returns it. */
    private String narrate(String what, Accessible a, String last) {
        String text = a == null ? null : a.describe();
        if (text != null && !Objects.equals(text, last)) narration.println("[narrate] " + what + ": " + text);
        return text;
    }

    /** The page, then its tooltip if one is up (drawn as in game). */
    @Override
    public void paint(Canvas canvas) {
        if (document == null) return;
        document.paint(canvas);
        Tooltip tooltip = document.error() == null ? document.input().tooltip() : null;
        if (tooltip != null) TooltipPainter.paint(canvas, host, tooltip, document.viewportWidth(), document.viewportHeight());
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
