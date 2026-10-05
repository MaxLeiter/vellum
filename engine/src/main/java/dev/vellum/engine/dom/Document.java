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
import dev.vellum.engine.paint.Painter;
import dev.vellum.engine.script.ScriptRuntime;

import java.util.ArrayList;
import java.util.List;

/**
 * A document and its rendering pipeline. Hosts drive it with three calls:
 * <ol>
 *   <li>{@link #setViewport} when the screen size changes,</li>
 *   <li>{@link #frame} once per rendered frame (timers, animations, restyle, relayout),</li>
 *   <li>{@link #paint} to draw onto a {@link Canvas},</li>
 * </ol>
 * plus input forwarded to {@link #input()}. Everything runs on one thread (the render thread in Minecraft).
 */
public final class Document extends Node {
    private final Host host;
    private String url;
    private final Scheduler scheduler = new Scheduler(this);
    private final StyleEngine styleEngine;
    private final LayoutEngine layoutEngine;
    private final AnimationEngine animationEngine;
    private final Painter painter;
    private final InputHandler input;
    private ScriptRuntime scripts;
    private boolean loaded;
    private boolean closed;

    private float viewportWidth = 320, viewportHeight = 240;
    private float devicePixelRatio = 1;
    private boolean styleDirty = true, layoutDirty = true;
    private int version;
    private int domVersion;

    private Element focused;

    private Document(Host host, String url) {
        super(null);
        this.host = host;
        this.url = url == null ? "" : url;
        this.styleEngine = new StyleEngine(this);
        this.layoutEngine = new LayoutEngine(this);
        this.animationEngine = new AnimationEngine(this);
        this.painter = new Painter(this);
        this.input = new InputHandler(this);
    }

    /** Creates an empty document with {@code <html><head></head><body></body></html>}. */
    public static Document create(Host host, String url) {
        Document doc = new Document(host, url);
        Element html = doc.appendChild(doc.createElement("html"));
        html.appendChild(doc.createElement("head"));
        html.appendChild(doc.createElement("body"));
        return doc;
    }

    /** Parses HTML into a new document, then loads it (stylesheets, scripts, the load event). */
    public static Document parse(Host host, String url, String html) {
        Document doc = new Document(host, url);
        HtmlParser.parseInto(doc, html);
        doc.load();
        return doc;
    }

    @Override
    public String nodeName() { return "#document"; }

    public Host host() { return host; }
    public String url() { return url; }
    public void setUrl(String url) { this.url = url; }
    public Scheduler scheduler() { return scheduler; }
    public StyleEngine styleEngine() { return styleEngine; }
    public LayoutEngine layoutEngine() { return layoutEngine; }
    public AnimationEngine animations() { return animationEngine; }
    public Painter painter() { return painter; }
    public InputHandler input() { return input; }
    /** The script runtime, or null when scripting is disabled. */
    public ScriptRuntime scripts() { return scripts; }
    public boolean isClosed() { return closed; }

    /** Resolves a URL relative to this document. */
    public String resolveUrl(String relative) {
        return host.resolveUrl(url, relative);
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
        Element[] found = new Element[1];
        Element.walk(this, e -> { if (found[0] == null && id.equals(e.getAttribute("id"))) found[0] = e; });
        return found[0];
    }

    public Element querySelector(String selector) {
        return Selectors.querySelector(this, selector);
    }

    public List<Element> querySelectorAll(String selector) {
        return Selectors.querySelectorAll(this, selector);
    }

    // ---- Loading ----

    /**
     * Finishes loading after parsing: creates the script runtime, runs scripts in document order, then fires
     * {@code DOMContentLoaded} and {@code load}. Stylesheets are discovered by the style engine on the first restyle.
     */
    public void load() {
        if (loaded) return;
        loaded = true;
        scripts = host.createScriptRuntime(this);
        List<Element> scriptElements = new ArrayList<>();
        Element.walk(this, e -> { if (e.tagName().equals("script")) scriptElements.add(e); });
        for (Element s : scriptElements) runScriptElement(s);
        dispatchEvent(new Event("DOMContentLoaded", true, false));
        dispatchEvent(new Event("load", false, false));
    }

    private void runScriptElement(Element script) {
        // controlState TRUE marks the script "already started" (or inert, for fragment-parsed scripts).
        if (scripts == null || script.controlState == Boolean.TRUE) return;
        String type = script.getAttribute("type");
        if (type != null && !type.isBlank() && !type.equals("text/javascript") && !type.equals("module")
                && !type.equals("application/javascript")) return; // data blocks, templates...
        script.controlState = Boolean.TRUE; // "already started"
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
        styleDirty = true; // vw/vh and media queries
        layoutDirty = true;
        dispatchEvent(new Event("resize", false, false));
    }

    // ---- Pipeline ----

    /**
     * Advances the document to {@code nowMs} (a monotonic clock in ms): runs timers and animation frames, advances
     * transitions and animations, then restyles and relayouts if anything changed.
     */
    public void frame(double nowMs) {
        if (closed) return;
        scheduler.run(nowMs);
        input.tick(nowMs);
        if (scripts != null) scripts.beforeRestyle();
        if (styleDirty) {
            styleDirty = false;
            styleEngine.restyle();
        }
        animationEngine.tick(nowMs);
        if (updateReplaced()) layoutDirty = true;
        if (layoutDirty) {
            layoutDirty = false;
            layoutEngine.layout();
            version++;
            input.afterLayout();
        }
    }

    private boolean updateReplaced() {
        boolean[] changed = {false};
        Element.walk(this, e -> {
            ReplacedContent r = e.replaced;
            if (r != null && r.update()) changed[0] = true;
        });
        return changed[0];
    }

    /** Paints the current layout. Call after {@link #frame}. */
    public void paint(Canvas canvas) {
        if (closed) return;
        painter.paint(canvas);
    }

    /** Incremented on every relayout; hosts use it to know when to reposition things (e.g. container slots). */
    public int layoutVersion() { return version; }

    /**
     * Incremented on every change to the tree, attributes, text or form state; not on hover, active or focus
     * changes. Lets subsystems skip work when only interaction state changed (or nothing did).
     */
    public int domVersion() { return domVersion; }

    public void invalidateStyle() { styleDirty = true; }
    public void invalidateLayout() { layoutDirty = true; }
    public boolean needsStyle() { return styleDirty; }
    public boolean needsLayout() { return layoutDirty; }

    /** Runs restyle and layout now if needed (for scripts reading geometry). */
    public void flushLayout() {
        if (styleDirty) {
            styleDirty = false;
            styleEngine.restyle();
            animationEngine.tick(scheduler.now());
        }
        if (layoutDirty) {
            layoutDirty = false;
            layoutEngine.layout();
            version++;
            input.afterLayout();
        }
    }

    // ---- Mutation hooks (called by nodes) ----

    void treeMutated(Node parent, Node child, boolean added) {
        domVersion++;
        styleDirty = true;
        layoutDirty = true;
        if (added && loaded && child.isConnected()) {
            if (child instanceof Element e) {
                if (e.tagName().equals("script")) runScriptElement(e);
                else Element.walk(e, d -> { if (d.tagName().equals("script")) runScriptElement(d); });
            }
        }
    }

    void nodeRemoving(Node node) {
        if (focused != null && node.contains(focused)) setFocus(null);
        input.nodeRemoving(node);
        disposeReplaced(node);
    }

    private void disposeReplaced(Node node) {
        if (node instanceof Element e && e.replaced != null) {
            e.replaced.dispose();
            e.replaced = null;
        }
        for (Node c : node.children) disposeReplaced(c);
    }

    void attributeChanged(Element element, String name, String oldValue) {
        domVersion++;
        styleDirty = true;
        layoutDirty = true;
        if (element.replaced != null) element.replaced.attributeChanged(name);
    }

    void textChanged(Text text) {
        domVersion++;
        layoutDirty = true;
        styleDirty = true; // :empty, and <style> contents
    }

    /** Interaction or form state changed; restyle (for :checked, :hover...) and repaint. */
    void stateChanged(Element element, boolean affectsSelectors) {
        domVersion++;
        styleDirty = true;
        layoutDirty = true;
    }

    void scrolled(Element element) {
        element.dispatchEvent(new Event("scroll", false, false));
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

    /** Runs the default action of a click that was not cancelled (toggle checkboxes, follow links...). */
    public void activationBehavior(Element target, Event event) {
        input.activationBehavior(target, event);
    }

    // ---- Host messaging ----

    /**
     * Delivers a message from the host: to scripts ({@code vellum.on(channel, fn)}) and as a
     * {@code CustomEvent("message")} on the document whose detail is {@code {channel, json}}.
     */
    public void receive(String channel, String json) {
        if (scripts != null) scripts.receive(channel, json);
        dispatchEvent(new CustomEvent("message", false, false, new String[] {channel, json}));
    }

    // ---- Errors and lifecycle ----

    public void reportError(String message, Throwable error) {
        host.reportError(message, error);
    }

    /** Disposes scripts, timers and replaced content. The document must not be used afterwards. */
    public void close() {
        if (closed) return;
        dispatchEvent(new Event("unload", false, false));
        closed = true;
        scheduler.clear();
        if (scripts != null) scripts.dispose();
        disposeReplaced(this);
    }
}
