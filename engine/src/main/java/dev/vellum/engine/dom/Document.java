package dev.vellum.engine.dom;

import dev.vellum.engine.anim.AnimationEngine;
import dev.vellum.engine.css.Selectors;
import dev.vellum.engine.css.StyleEngine;
import dev.vellum.engine.event.CustomEvent;
import dev.vellum.engine.event.Event;
import dev.vellum.engine.event.FocusEvent;
import dev.vellum.engine.host.Host;
import dev.vellum.engine.host.ReplacedContent;
import dev.vellum.engine.html.HtmlParser;
import dev.vellum.engine.input.InputHandler;
import dev.vellum.engine.layout.LayoutEngine;
import dev.vellum.engine.paint.Canvas;
import dev.vellum.engine.paint.HitResult;
import dev.vellum.engine.paint.Painter;
import dev.vellum.engine.replaced.ReplacedElements;
import dev.vellum.engine.script.ScriptRuntime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A document and its rendering pipeline. Hosts drive it with three calls:
 * <ol>
 *   <li>{@link #setViewport} when the screen size changes,</li>
 *   <li>{@link #frame} once per rendered frame (timers, animations, restyle, relayout),</li>
 *   <li>{@link #paint} to draw onto a {@link Canvas},</li>
 * </ol>
 * plus input forwarded to {@link #input()}. Everything runs on one thread (the render thread in Minecraft).
 *
 * <p>The document is its own error boundary: if the engine throws during a host call (loading, a frame, painting,
 * input, a message), the document stops. The exception is reported once through {@link Host#reportError} and kept
 * as {@link #error()}, and later host calls do nothing, so hosts only check {@link #error()} to show it. Errors in
 * scripts and listeners are reported without stopping anything.
 */
public final class Document extends Node {
    private final Host host;
    private final String url;
    /** JSON delivered to scripts as {@code vellum.data} before any script runs, or null. */
    private final String initialData;
    private final Scheduler scheduler = new Scheduler(this);
    private final Scrolling scrolling = new Scrolling();
    private final StyleEngine styleEngine;
    private final LayoutEngine layoutEngine;
    private final AnimationEngine animationEngine;
    private final Painter painter;
    private final InputHandler input;
    private final ReplacedElements replacedElements;
    /** The content of every replaced element in the document (and of canvases drawn on before insertion). */
    private final List<ReplacedContent> replaced = new ArrayList<>();
    private ScriptRuntime scripts;
    private String readyState = "loading";
    private boolean closed;
    private Throwable error;

    private float viewportWidth, viewportHeight, devicePixelRatio;
    boolean styleDirty = true, layoutDirty = true;
    /** Something that is painted changed without needing a restyle or relayout (a scroll offset, canvas pixels). */
    private boolean repaint = true;
    /** A layout ran (perhaps in a script's flush) whose follow-up work waits for the next frame. */
    private boolean laidOut;
    private int stackingVersion;
    /** Listeners plus inline {@code on*} handlers in this document, by lower-case event type. */
    private final Map<String, int[]> handlers = new HashMap<>();
    private int domVersion;

    private Element focused;

    private Document(Host host, String url, String initialData, Viewport viewport) {
        super(null);
        this.host = host;
        this.url = url == null ? "" : url;
        this.initialData = initialData;
        this.viewportWidth = viewport.width();
        this.viewportHeight = viewport.height();
        this.devicePixelRatio = viewport.devicePixelRatio();
        this.styleEngine = new StyleEngine(this);
        this.layoutEngine = new LayoutEngine(this);
        this.animationEngine = new AnimationEngine(this);
        this.painter = new Painter(this);
        this.input = new InputHandler(this);
        this.replacedElements = new ReplacedElements(host);
    }

    /**
     * Creates an empty document with {@code <html><head></head><body></body></html>}, without loading it, on the
     * {@link Viewport#DEFAULT default viewport}.
     */
    public static Document create(Host host, String url) {
        Document doc = new Document(host, url, null, Viewport.DEFAULT);
        Element html = doc.appendChild(doc.createElement("html"));
        html.appendChild(doc.createElement("head"));
        html.appendChild(doc.createElement("body"));
        return doc;
    }

    /** {@link #parse(Host, String, String, String, Viewport)} without initial data, on the default viewport. */
    public static Document parse(Host host, String url, String html) {
        return parse(host, url, html, null, Viewport.DEFAULT);
    }

    /**
     * Parses HTML into a new document shown in {@code viewport}, then loads it: runs its scripts (after delivering
     * {@code initialData}, a JSON value or null, as {@code vellum.data}), then fires {@code DOMContentLoaded} and
     * {@code load}. Stylesheets are found on the first restyle. Never throws for engine errors: the document comes
     * back stopped ({@link #error()}).
     */
    public static Document parse(Host host, String url, String html, String initialData, Viewport viewport) {
        Document doc = new Document(host, url, initialData, viewport);
        doc.run(() -> {
            HtmlParser.parseInto(doc, html);
            doc.load();
        });
        return doc;
    }

    @Override
    public String nodeName() { return "#document"; }

    public Host host() { return host; }
    public String url() { return url; }
    public Scheduler scheduler() { return scheduler; }
    public Scrolling scrolling() { return scrolling; }
    public StyleEngine styleEngine() { return styleEngine; }
    public LayoutEngine layoutEngine() { return layoutEngine; }
    public AnimationEngine animations() { return animationEngine; }
    public Painter painter() { return painter; }
    public InputHandler input() { return input; }
    /** The script runtime, or null when scripting is disabled. */
    public ScriptRuntime scripts() { return scripts; }
    /** {@code document.readyState}: "loading" while parsing, "interactive" while scripts run, then "complete". */
    public String readyState() { return readyState; }

    /** Resolves a URL relative to this document. */
    public String resolveUrl(String relative) {
        return host.resolveUrl(url, relative);
    }

    @Override
    Node cloneShallow() {
        throw new UnsupportedOperationException("Documents cannot be cloned");
    }

    // ---- Tree helpers ----

    public Element documentElement() {
        for (Node n : children) if (n instanceof Element e) return e;
        return null;
    }

    public Element head() { return childOfRoot("head"); }
    public Element body() { return childOfRoot("body"); }

    private Element childOfRoot(String tag) {
        Element root = documentElement();
        if (root == null) return null;
        for (Node n : root.children) if (n instanceof Element e && e.tagName().equals(tag)) return e;
        return null;
    }

    public Element createElement(String tag) {
        return new Element(this, tag);
    }

    public Text createTextNode(String data) {
        return new Text(this, data);
    }

    public DocumentFragment createDocumentFragment() {
        return new DocumentFragment(this);
    }

    public Element getElementById(String id) {
        return firstDescendant(e -> id.equals(e.attribute("id")));
    }

    public Element querySelector(String selector) {
        return Selectors.querySelector(this, selector);
    }

    public List<Element> querySelectorAll(String selector) {
        return Selectors.querySelectorAll(this, selector);
    }

    // ---- Loading ----

    /**
     * After parsing, {@code <mc-text>} elements are expanded, then scripts run in document order (like {@code defer}),
     * then templates bind, then the events.
     */
    private void load() {
        readyState = "interactive";
        for (Element e : getElementsByTagName(MinecraftText.TAG)) MinecraftText.expand(this, e);
        scripts = host.createScriptRuntime(this);
        if (scripts != null && initialData != null) scripts.receive("data", initialData);
        for (Element script : getElementsByTagName("script")) runScript(script);
        if (scripts != null) scripts.documentLoaded();
        dispatchEvent(new Event("DOMContentLoaded", true, false));
        readyState = "complete";
        dispatchEvent(new Event("load", false, false));
    }

    /** Runs a script element, unless it already started (or must never run) or is a data block. */
    private void runScript(Element script) {
        if (scripts == null || !script.tagName().equals("script") || script.alreadyStarted) return;
        String type = script.getAttribute("type");
        if (type != null && !type.isBlank() && !type.equals("text/javascript") && !type.equals("module")
                && !type.equals("application/javascript")) return; // data blocks, templates...
        script.alreadyStarted = true;
        String src = script.getAttribute("src");
        String code;
        String name;
        if (src != null) {
            name = resolveUrl(src);
            code = host.loadText(name);
            if (code == null) {
                host.log(Host.LogLevel.WARN, "Script not found: " + name);
                return;
            }
        } else {
            name = url + "#script";
            code = script.textContent();
        }
        scripts.evaluate(code, name);
    }

    // ---- Viewport ----

    public float viewportWidth() { return viewportWidth; }
    public float viewportHeight() { return viewportHeight; }
    /** Device pixels per GUI px (Minecraft's GUI scale). */
    public float devicePixelRatio() { return devicePixelRatio; }

    public void setViewport(float width, float height, float devicePixelRatio) {
        if (width == viewportWidth && height == viewportHeight && devicePixelRatio == this.devicePixelRatio) return;
        this.viewportWidth = width;
        this.viewportHeight = height;
        this.devicePixelRatio = devicePixelRatio;
        styleDirty = layoutDirty = true; // vw/vh and media queries
        run(() -> dispatchEvent(new Event("resize", false, false)));
    }

    // ---- Pipeline ----

    /**
     * Advances the document to {@code nowMs} (a monotonic clock in ms): runs timers and animation frames, advances
     * transitions and animations, restyles and relayouts if anything changed, then does the work that follows a
     * layout (scroll clamping, autofocus, re-targeting hover), which may fire events.
     */
    public void frame(double nowMs) {
        run(() -> {
            scheduler.run(nowMs);
            input.tick(nowMs);
            if (scripts != null) scripts.beforeRestyle();
            updateStyle();
            animationEngine.tick(nowMs);
            if (updateReplaced()) layoutDirty = true;
            updateLayout();
            if (laidOut) {
                laidOut = false;
                input.afterLayout();
            }
        });
    }

    /** Restyles now if needed, for scripts reading computed styles. Fires no events. */
    public void flushStyle() {
        updateStyle();
    }

    /** Restyles and relayouts now if needed, for scripts reading geometry. Fires no events. */
    public void flushLayout() {
        updateStyle();
        updateLayout();
    }

    private void updateStyle() {
        if (!styleDirty) return;
        styleDirty = false;
        styleEngine.restyle();
    }

    private void updateLayout() {
        if (!layoutDirty) return;
        layoutDirty = false;
        layoutEngine.layout();
        laidOut = true;
    }

    /** Lets replaced content catch up (canvas uploads, images that resized); true if a natural size changed. */
    private boolean updateReplaced() {
        boolean changed = false;
        for (int i = 0; i < replaced.size(); i++) changed |= replaced.get(i).update();
        return changed;
    }

    /** Paints the current layout. Call after {@link #frame}. */
    public void paint(Canvas canvas) {
        repaint = false;
        run(() -> painter.paint(canvas));
    }

    /**
     * Whether a frame at {@code nowMs} would change what is painted: {@link #dirty pending work}, due timers or
     * animation-frame callbacks, running animations, drags, a blinking caret, or a tooltip coming up. Hosts that can
     * idle (the previewer) skip frames otherwise, and render after their own input.
     */
    public boolean needsFrame(double nowMs) {
        if (error != null || closed) return false;
        return dirty() || scheduler.hasWork(nowMs) || animationEngine.isAnimating() || input.isActive()
                || input.tooltipDue(nowMs);
    }

    /**
     * Whether the page has stopped changing by itself, for automation that waits for it before looking: no
     * {@link #dirty pending work}, no transition or finite animation running (or its events waiting), no drag or
     * spinning turntable, and no tooltip waiting out its delay. What never ends does not count: infinite animations,
     * timers ({@code setTimeout}, {@code setInterval}), animation-frame callbacks and the caret's blink. A stopped or
     * closed document is settled.
     */
    public boolean settled() {
        if (error != null || closed) return true;
        return !(dirty() || animationEngine.isSettling() || input.isSettling());
    }

    /**
     * Work that both {@link #needsFrame} and {@link #settled} wait for: a pending restyle, relayout or repaint, the
     * work after a layout, a smooth scroll or {@code scroll} event, a template update or {@code vellum.nextTick}
     * callback, or replaced content still loading ({@link ReplacedContent#loading}).
     */
    private boolean dirty() {
        return styleDirty || layoutDirty || laidOut || repaint || scrolling.isActive()
                || scripts != null && scripts.needsFrame() || loading();
    }

    private boolean loading() {
        for (int i = 0; i < replaced.size(); i++) if (replaced.get(i).loading()) return true;
        return false;
    }

    /** Something painted changed that restyle and relayout do not track (a scroll offset, canvas pixels). */
    public void invalidatePaint() {
        repaint = true;
    }

    /** The topmost element (and box) at a viewport point, as painted; null when nothing is there. */
    public HitResult hitTest(float x, float y) {
        return guarded(() -> painter.hitTest(x, y), null);
    }

    /**
     * Where pointer input reaches {@code element}, {x, y} in viewport px: the centre of its
     * {@link Element#visibleRect() visible part}. When none of it shows, it is first scrolled into view (instantly,
     * by the least scroll). Null when it is not in this document, has no box or still does not show, or when the
     * topmost element painted at that point is neither it nor inside it (something covers it, or it has
     * {@code pointer-events: none}). Lays out first. For automation that sends real input to an element.
     */
    public float[] pointerTarget(Element element) {
        return guarded(() -> {
            if (element.ownerDocument() != this || !element.isConnected()) return null;
            flushLayout();
            float[] r = element.visibleRect();
            if (r == null && element.box != null) {
                // Scroll offsets apply when geometry is read, so no layout is needed before reading it again.
                element.scrollIntoView(Element.ScrollAlign.NEAREST, Element.ScrollAlign.NEAREST,
                        Element.ScrollBehavior.INSTANT);
                r = element.visibleRect();
            }
            if (r == null) return null;
            float x = r[0] + r[2] / 2, y = r[1] + r[3] / 2;
            HitResult hit = painter.hitTest(x, y);
            return hit != null && element.contains(hit.element()) ? new float[] {x, y} : null;
        }, null);
    }

    /**
     * Incremented when an element's style changes paint order without a relayout (z-index, or opacity starting or
     * ending a stacking context); the painter keeps its stacking-context lists until this or the layout changes.
     */
    public int stackingVersion() { return stackingVersion; }

    /**
     * Incremented on every change to the tree, attributes, text or form state; not on hover, active or focus
     * changes. Lets subsystems skip work when only interaction state changed (or nothing did).
     */
    public int domVersion() { return domVersion; }

    public void invalidateStyle() { styleDirty = true; }
    public void invalidateLayout() { layoutDirty = true; }
    public void invalidateStacking() { stackingVersion++; }
    public boolean needsLayout() { return layoutDirty; }

    /** Marks style dirty, and layout too when {@code layout}. */
    void invalidate(boolean layout) {
        invalidate(true, layout);
    }

    /** A tree, attribute or text change: bumps {@link #domVersion} and marks style and/or layout dirty. */
    private void invalidate(boolean style, boolean layout) {
        domVersion++;
        styleDirty |= style;
        layoutDirty |= layout;
    }

    // ---- Error boundary ----

    /** The engine failure that stopped this document, or null while it runs. */
    public Throwable error() { return error; }

    /**
     * Runs host-driven work inside the document's error boundary. Returns the work's result, or false when the
     * document is stopped or closed (the work does not run) or the work failed (the document stops).
     */
    public boolean guard(BooleanSupplier work) {
        return guarded(work::getAsBoolean, false);
    }

    private void run(Runnable work) {
        guarded(() -> {
            work.run();
            return null;
        }, null);
    }

    /** The boundary itself: the work's result, or {@code otherwise} when it did not run or failed. */
    private <T> T guarded(Supplier<T> work, T otherwise) {
        if (error != null || closed) return otherwise;
        try {
            return work.get();
        } catch (RuntimeException | StackOverflowError e) {
            if (error == null) {
                error = e;
                host.reportError("The page stopped after an engine error", e);
            }
            return otherwise;
        }
    }

    // ---- Mutation hooks (called by nodes) ----

    /** {@code child} was inserted into this connected document; {@code arrived} unless it only moved within it. */
    void inserted(Node child, boolean arrived) {
        invalidate(true);
        if (!arrived || child.isInert()) return;
        boolean loaded = !readyState.equals("loading");
        connect(child, loaded);
        if (!loaded || scripts == null) return;
        if (child instanceof Element e) runScript(e);
        for (Element script : child.getElementsByTagName("script")) runScript(script);
    }

    /**
     * What arriving in the document brings an element: replaced elements get their content, and once the page has
     * loaded (the parser's are expanded by {@link #load}) {@code <mc-text>} is expanded.
     */
    private void connect(Node node, boolean loaded) {
        if (node instanceof Element e) {
            if (e.replaced == null) addReplaced(e);
            // An expanded mc-text's new children were connected as they were inserted.
            if (loaded && e.tagName().equals(MinecraftText.TAG) && MinecraftText.expand(this, e)) return;
            if (e.hasInertContent()) return;
        }
        for (int i = 0; i < node.children.size(); i++) connect(node.children.get(i), loaded);
    }

    private void addReplaced(Element e) {
        e.replaced = replacedElements.create(e);
        if (e.replaced != null) replaced.add(e.replaced);
    }

    /**
     * The element's replaced content, created now if it has none: a canvas that a script draws on before inserting
     * it. Content made for an element outside the document lives until the document closes. Null for elements that
     * are not replaced.
     */
    public ReplacedContent replacedContent(Element element) {
        if (element.replaced == null && element.ownerDocument() == this && !closed) addReplaced(element);
        return element.replaced;
    }

    /** The content of the replaced elements, for lookups such as {@code canvas:id} images. */
    public List<ReplacedContent> replacedContents() {
        return Collections.unmodifiableList(replaced);
    }

    /** {@code node} is about to leave the document (not just move within it). */
    void nodeRemoving(Node node) {
        if (focused != null && node.contains(focused)) setFocus(null);
        scrolling.forget(node);
        input.nodeRemoving(node);
        disposeReplaced(node);
    }

    /** Disposes the replaced content in a subtree. */
    private void disposeReplaced(Node node) {
        if (node instanceof Element e && e.replaced != null) {
            for (int i = 0; i < replaced.size(); i++) {
                if (replaced.get(i) == e.replaced) { // by identity: contents may be equal records
                    replaced.remove(i);
                    break;
                }
            }
            dispose(e.replaced, e);
            e.replaced = null;
        }
        for (Node c : node.children) disposeReplaced(c);
    }

    /** A failing dispose is reported, and the rest still disposed. */
    private void dispose(ReplacedContent content, Object owner) {
        try {
            content.dispose();
        } catch (RuntimeException ex) {
            reportError("Error disposing " + owner, ex);
        }
    }

    /**
     * Restyles for any attribute (selectors read them). Relayouts only for what layout reads directly: the size and
     * source of replaced elements, an input's type and whether a details element is open (its loose text shows only
     * then); other layout changes come from the restyle.
     */
    void attributeChanged(Element element, String name) {
        if (element.replaced != null) element.replaced.attributeChanged(name); // also a canvas outside the document
        if (!element.isConnected()) return;
        boolean layout = switch (name) {
            case "width", "height", "src" -> element.replaced != null;
            case "type" -> element.tagName().equals("input");
            case "open" -> element.tagName().equals("details");
            default -> false;
        };
        // Restyle only for attributes a style can read; repaint for any (replaced content and controls draw them).
        invalidate(styleEngine.readsAttribute(name), layout);
        repaint = true;
        if (MinecraftText.expandsOn(element, name) && !readyState.equals("loading")) MinecraftText.expand(this, element);
    }

    /**
     * Text changed: relayout; restyle too when it is a stylesheet's or the parent's emptiness flipped ({@code :empty},
     * {@code :placeholder-shown}).
     */
    void textChanged(Text text, boolean emptinessFlipped) {
        if (!text.isConnected()) return;
        Element parent = text.parentElement();
        invalidate(emptinessFlipped || parent != null && parent.tagName().equals("style"), true);
    }

    /** Form state that selectors read changed ({@code :checked}, {@code :placeholder-shown}): restyle. */
    void stateChanged(Element element) {
        if (!element.isConnected()) return;
        domVersion++;
        styleDirty = true;
    }

    /** Adds {@code delta} to the count of handlers (listeners or inline attributes) for an event type. */
    void countHandlers(String type, int delta) {
        handlers.computeIfAbsent(type.toLowerCase(Locale.ROOT), t -> new int[1])[0] += delta;
    }

    /** Whether anything in the document may handle {@code type} events, so dispatch can skip the rest. */
    boolean handles(String type) {
        int[] count = handlers.get(type.toLowerCase(Locale.ROOT));
        return count != null && count[0] > 0;
    }

    // ---- Interaction state ----

    public Element focusedElement() { return focused; }

    /** Moves focus, firing blur/focusout on the old element and focus/focusin on the new one. */
    public void setFocus(Element element) {
        if (element == focused) return;
        if (element != null && (!element.isConnected() || element.ownerDocument() != this)) return;
        Element old = focused;
        focused = element;
        input.focusChanged(old, element);
        if (old != null) {
            old.focused = false;
            old.dispatchEvent(new FocusEvent("blur", element));
            old.dispatchEvent(new FocusEvent("focusout", element));
        }
        if (element != null) {
            element.focused = true;
            element.dispatchEvent(new FocusEvent("focus", old));
            element.dispatchEvent(new FocusEvent("focusin", old));
        }
        styleDirty = true;
    }

    /** Sets the hover flag on an element (the input handler maintains the hover chain). */
    public void setHovered(Element element, boolean hovered) {
        if (element.hovered != hovered) {
            element.hovered = hovered;
            styleDirty = true;
        }
    }

    /** Sets the active (pressed) flag on an element. */
    public void setActive(Element element, boolean active) {
        if (element.active != active) {
            element.active = active;
            styleDirty = true;
        }
    }

    // ---- Host messaging ----

    /**
     * Delivers a message from the host: to scripts ({@code vellum.on(channel, fn)}) and as a
     * {@code CustomEvent("message")} on the document whose detail is {@code {channel, json}}.
     */
    public void receive(String channel, String json) {
        run(() -> {
            if (scripts != null) scripts.receive(channel, json);
            dispatchEvent(new CustomEvent("message", false, false, new String[] {channel, json}));
        });
    }

    // ---- Errors and lifecycle ----

    /** Reports an error in page code (a listener, a script); the document keeps running. */
    public void reportError(String message, Throwable error) {
        host.reportError(message, error);
    }

    /**
     * Fires {@code pagehide} then {@code unload} at the document (where {@code window.addEventListener} listens), with
     * scripts still running, so a page can save state or {@code vellum.send} a last message; then disposes scripts,
     * timers and replaced content. The document is unusable afterwards.
     */
    public void close() {
        if (closed) return;
        run(() -> {
            dispatchEvent(new Event("pagehide", false, false));
            dispatchEvent(new Event("unload", false, false));
        });
        closed = true;
        scheduler.clear();
        if (scripts != null) scripts.dispose();
        for (ReplacedContent content : replaced) dispose(content, "replaced content");
        replaced.clear();
        forEachElement(e -> e.replaced = null);
    }
}
