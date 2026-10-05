package dev.vellum.engine.dom;

import dev.vellum.engine.css.Selectors;
import dev.vellum.engine.event.MouseEvent;
import dev.vellum.engine.event.Modifiers;
import dev.vellum.engine.host.ReplacedContent;
import dev.vellum.engine.html.HtmlParser;
import dev.vellum.engine.html.HtmlSerializer;
import dev.vellum.engine.layout.Box;
import dev.vellum.engine.style.ComputedStyle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An element. Holds the DOM state (tag, attributes, interaction state) plus per-element results of the pipeline:
 * computed styles, the layout box and the scroll position.
 */
public class Element extends Node {
    private final String tagName;
    private final LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
    private Set<String> classCache;

    // ---- Pipeline results (written by the engine, read by everyone) ----

    /** Style from the cascade, before animations. Null until the first restyle or when not rendered. */
    public ComputedStyle baseStyle;
    /** Style used for layout and paint: {@link #baseStyle} with running transitions and animations applied. */
    public ComputedStyle style;
    /** Styles for the ::before and ::after pseudo-elements, or null when they have no content. */
    public ComputedStyle beforeStyle, afterStyle;
    /** The principal layout box, or null when the element generates no box (display: none, detached). */
    public Box box;
    /** Host-provided content for replaced elements (img, item, slot, entity, canvas...). */
    public ReplacedContent replaced;
    /** Cache slot for the parsed inline {@code style} attribute, owned by the style engine. */
    public Object parsedInlineStyle;
    /** Per-element state owned by the animation engine (running transitions and animations). */
    public Object animationState;
    /** Per-element state owned by form controls (caret, selection, drag state...). */
    public Object controlState;

    // ---- Interaction state ----
    boolean hovered, active, focused;
    /** Live value of form controls; null means "use the value attribute". */
    String value;
    /** Live checkedness of checkboxes and radios; null means "use the checked attribute". */
    Boolean checked;
    /** Scroll offsets of a scroll container, in px. */
    public float scrollLeft, scrollTop;

    Element(Document ownerDocument, String tagName) {
        super(ownerDocument);
        this.tagName = tagName.toLowerCase();
    }

    @Override
    public String nodeName() { return tagName.toUpperCase(); }

    /** Lower-case tag name. */
    public String tagName() { return tagName; }

    // ---- Attributes ----

    public String getAttribute(String name) {
        return attributes.get(name.toLowerCase());
    }

    public boolean hasAttribute(String name) {
        return attributes.containsKey(name.toLowerCase());
    }

    public void setAttribute(String name, String value) {
        name = name.toLowerCase();
        String old = attributes.put(name, value == null ? "" : value);
        if (!java.util.Objects.equals(old, value)) attributeChanged(name, old);
    }

    public void removeAttribute(String name) {
        name = name.toLowerCase();
        if (attributes.containsKey(name)) {
            String old = attributes.remove(name);
            attributeChanged(name, old);
        }
    }

    public void toggleAttribute(String name, boolean on) {
        if (on) setAttribute(name, ""); else removeAttribute(name);
    }

    public Map<String, String> attributes() {
        return Collections.unmodifiableMap(attributes);
    }

    private void attributeChanged(String name, String old) {
        if (name.equals("class")) classCache = null;
        if (name.equals("style")) parsedInlineStyle = null;
        if (ownerDocument != null) ownerDocument.attributeChanged(this, name, old);
    }

    public String id() {
        String id = getAttribute("id");
        return id == null ? "" : id;
    }

    // ---- Classes ----

    public Set<String> classes() {
        if (classCache == null) {
            String c = getAttribute("class");
            if (c == null || c.isBlank()) classCache = Set.of();
            else {
                LinkedHashSet<String> set = new LinkedHashSet<>();
                for (String s : c.trim().split("\\s+")) set.add(s);
                classCache = Collections.unmodifiableSet(set);
            }
        }
        return classCache;
    }

    public boolean hasClass(String c) { return classes().contains(c); }

    public void addClass(String c) {
        if (hasClass(c)) return;
        LinkedHashSet<String> set = new LinkedHashSet<>(classes());
        set.add(c);
        setAttribute("class", String.join(" ", set));
    }

    public void removeClass(String c) {
        if (!hasClass(c)) return;
        LinkedHashSet<String> set = new LinkedHashSet<>(classes());
        set.remove(c);
        setAttribute("class", String.join(" ", set));
    }

    /** Toggles a class; returns whether it is now present. */
    public boolean toggleClass(String c) {
        if (hasClass(c)) { removeClass(c); return false; }
        addClass(c);
        return true;
    }

    public void toggleClass(String c, boolean on) {
        if (on) addClass(c); else removeClass(c);
    }

    // ---- Selectors ----

    public boolean matches(String selector) {
        return Selectors.matches(this, selector);
    }

    public Element closest(String selector) {
        for (Element e = this; e != null; e = e.parentElement()) if (e.matches(selector)) return e;
        return null;
    }

    public Element querySelector(String selector) {
        return Selectors.querySelector(this, selector);
    }

    public List<Element> querySelectorAll(String selector) {
        return Selectors.querySelectorAll(this, selector);
    }

    public List<Element> getElementsByTagName(String tag) {
        List<Element> out = new ArrayList<>();
        String t = tag.toLowerCase();
        walk(this, e -> { if (e != this && (t.equals("*") || e.tagName.equals(t))) out.add(e); });
        return out;
    }

    static void walk(Node root, java.util.function.Consumer<Element> visitor) {
        for (Node c : root.children) {
            if (c instanceof Element e) {
                visitor.accept(e);
                walk(e, visitor);
            }
        }
    }

    // ---- Markup ----

    public String innerHTML() {
        return HtmlSerializer.innerHTML(this);
    }

    public String outerHTML() {
        return HtmlSerializer.outerHTML(this);
    }

    public void setInnerHTML(String html) {
        removeAllChildren();
        for (Node n : HtmlParser.parseFragment(ownerDocument, html)) appendChild(n);
    }

    public void insertAdjacentHTML(String position, String html) {
        List<Node> nodes = HtmlParser.parseFragment(ownerDocument, html);
        switch (position.toLowerCase()) {
            case "beforebegin" -> { if (parent != null) for (Node n : nodes) parent.insertBefore(n, this); }
            case "afterbegin" -> { Node first = firstChild(); for (Node n : nodes) insertBefore(n, first); }
            case "beforeend" -> { for (Node n : nodes) appendChild(n); }
            case "afterend" -> {
                if (parent != null) {
                    Node next = nextSibling();
                    for (Node n : nodes) parent.insertBefore(n, next);
                }
            }
            default -> throw new IllegalArgumentException("Bad position: " + position);
        }
    }

    // ---- Interaction state ----

    public boolean isHovered() { return hovered; }
    public boolean isActive() { return active; }
    public boolean isFocused() { return focused; }

    public boolean isDisabled() {
        if (hasAttribute("disabled")) return true;
        // Controls inside a disabled fieldset are disabled too.
        for (Element p = parentElement(); p != null; p = p.parentElement()) {
            if (p.tagName.equals("fieldset") && p.hasAttribute("disabled")) return true;
        }
        return false;
    }

    /** Whether this element can take keyboard focus. */
    public boolean isFocusable() {
        if (isDisabled()) return false;
        if (hasAttribute("tabindex")) return true;
        return switch (tagName) {
            case "button", "input", "select", "textarea" -> !"hidden".equals(getAttribute("type"));
            case "a" -> hasAttribute("href");
            default -> hasAttribute("contenteditable");
        };
    }

    public int tabIndex() {
        String t = getAttribute("tabindex");
        if (t != null) {
            try { return Integer.parseInt(t.trim()); } catch (NumberFormatException ignored) { }
        }
        return isFocusable() ? 0 : -1;
    }

    public void focus() {
        if (ownerDocument != null) ownerDocument.setFocus(this);
    }

    public void blur() {
        if (ownerDocument != null && ownerDocument.focusedElement() == this) ownerDocument.setFocus(null);
    }

    /** Fires a synthetic click, as {@code HTMLElement.click()} does. */
    public void click() {
        if (isDisabled()) return;
        float cx = 0, cy = 0;
        if (box != null) {
            float[] r = box.clientRect();
            cx = r[0] + r[2] / 2;
            cy = r[1] + r[3] / 2;
        }
        MouseEvent e = new MouseEvent("click", true, true, cx, cy, 0, 0, Modifiers.NONE, 1, null);
        if (dispatchEvent(e) && ownerDocument != null) ownerDocument.activationBehavior(this, e);
    }

    // ---- Form values ----

    /** The live value of a form control: the typed value, else the value attribute, else the text for textareas. */
    public String value() {
        if (value != null) return value;
        if (tagName.equals("textarea")) return textContent();
        String v = getAttribute("value");
        return v == null ? "" : v;
    }

    public void setValue(String v) {
        if (java.util.Objects.equals(value, v)) return;
        value = v;
        if (ownerDocument != null) ownerDocument.stateChanged(this, false);
    }

    public boolean checked() {
        return checked != null ? checked : hasAttribute("checked");
    }

    public void setChecked(boolean c) {
        if (checked != null && checked == c) return;
        checked = c;
        if (ownerDocument != null) ownerDocument.stateChanged(this, true);
    }

    // ---- Geometry ----

    /** {@code getBoundingClientRect()}: {x, y, width, height} of the border box in viewport px, or zeros. */
    public float[] getBoundingClientRect() {
        return box == null ? new float[4] : box.clientRect();
    }

    public float scrollWidth() { return box == null ? 0 : box.scrollWidth; }
    public float scrollHeight() { return box == null ? 0 : box.scrollHeight; }
    public float clientWidth() { return box == null ? 0 : box.paddingBoxWidth(); }
    public float clientHeight() { return box == null ? 0 : box.paddingBoxHeight(); }

    /** Sets the scroll position, clamped to the scrollable range. Smooth scrolling is the input handler's job. */
    public void scrollTo(float left, float top) {
        float maxX = Math.max(0, scrollWidth() - clientWidth());
        float maxY = Math.max(0, scrollHeight() - clientHeight());
        float nl = Math.max(0, Math.min(maxX, left));
        float nt = Math.max(0, Math.min(maxY, top));
        if (nl != scrollLeft || nt != scrollTop) {
            scrollLeft = nl;
            scrollTop = nt;
            if (ownerDocument != null) ownerDocument.scrolled(this);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("<").append(tagName);
        String id = getAttribute("id");
        if (id != null) sb.append('#').append(id);
        for (String c : classes()) sb.append('.').append(c);
        return sb.append('>').toString();
    }
}
