package dev.vellum.engine.dom;

import dev.vellum.engine.css.Selectors;
import dev.vellum.engine.event.Modifiers;
import dev.vellum.engine.event.MouseEvent;
import dev.vellum.engine.host.ReplacedContent;
import dev.vellum.engine.html.HtmlParser;
import dev.vellum.engine.html.HtmlSerializer;
import dev.vellum.engine.layout.Box;
import dev.vellum.engine.paint.Affine;
import dev.vellum.engine.paint.Coordinates;
import dev.vellum.engine.style.ComputedStyle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An element. Holds the DOM state (tag, attributes, interaction and form state) plus per-element results of the
 * pipeline: computed styles and the layout box. It also owns its scroll state (the position and a smooth scroll's
 * destination): every scroll, by input, scripts, focus or layout, goes through {@link #scrollTo}.
 *
 * <p>Form controls follow HTML: an input's {@link #inputType() type} defaults to text (also for unknown types),
 * checkboxes and radios have a live checkedness, options a live selectedness from which a select's
 * {@link #selectedOption() selection} and {@link #value() value} derive, and checking a radio unchecks the rest of
 * its {@link #radioGroup() group}.
 */
public class Element extends Node {
    /** Input types with a text field. Unknown types are text, as in HTML. */
    private static final Set<String> TEXT_TYPES = Set.of("text", "password", "number", "search", "email", "url", "tel");
    private static final Set<String> OTHER_TYPES =
            Set.of("checkbox", "radio", "range", "submit", "reset", "button", "image", "hidden", "color", "file");

    private final String tagName;
    private final LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
    private Set<String> classCache;
    private String inputType;

    // ---- Pipeline results (written by the engine, read by everyone) ----

    /** Style from the cascade, before animations. Null until the first restyle or when not rendered. */
    public ComputedStyle baseStyle;
    /**
     * Style used for layout and paint: {@link #baseStyle} with running transitions and animations applied, and the
     * animated values it inherits or computes from.
     */
    public ComputedStyle style;
    /** The cascade's styles for the ::before and ::after pseudo-elements, or null when they have no content. */
    public ComputedStyle beforeBaseStyle, afterBaseStyle;
    /** The ::before and ::after styles used for layout and paint, as {@link #style} is for the element. */
    public ComputedStyle beforeStyle, afterStyle;
    /** Style of the ::placeholder pseudo-element for text controls, or null. */
    public ComputedStyle placeholderStyle;
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

    // ---- Interaction and form state ----
    boolean hovered, active, focused;
    /** Live value of form controls; null means "use the default" ({@link #value()}). */
    private String value;
    /** Live checkedness of checkboxes and radios; null means "use the checked attribute". */
    private Boolean checked;
    /** Live selectedness of an option; null means "use the selected attribute". */
    private Boolean selected;
    /** A script's "already started" flag: it has run, or must never run (scripts from fragment parsing). */
    boolean alreadyStarted;
    /** Bumped whenever the live value changes, so form controls know when to re-read it. */
    private int valueVersion;
    /** Scroll offsets of a scroll container, in px. */
    private float scrollLeft, scrollTop;
    /** Where a smooth scroll in progress is heading, or NaN. */
    private float scrollTargetLeft = Float.NaN, scrollTargetTop = Float.NaN;

    Element(Document ownerDocument, String tagName) {
        super(ownerDocument);
        this.tagName = tagName.toLowerCase(Locale.ROOT);
    }

    @Override
    public String nodeName() { return tagName.toUpperCase(Locale.ROOT); }

    /** Lower-case tag name. */
    public String tagName() { return tagName; }

    /** True for elements whose children are inert template contents ({@code <template>}). */
    public boolean hasInertContent() {
        return tagName.equals("template");
    }

    /**
     * The style used for layout and paint ({@link #style}), or the initial style before the first restyle and for
     * elements outside a document (content painted on its own, as the canvas tests do).
     */
    public ComputedStyle computedStyle() {
        return style != null ? style : ComputedStyle.INITIAL;
    }

    // ---- Attributes ----

    public String getAttribute(String name) {
        return attributes.get(name.toLowerCase(Locale.ROOT));
    }

    /** {@link #getAttribute} for a name that is already lower-case. */
    String attribute(String lowerName) {
        return attributes.get(lowerName);
    }

    public boolean hasAttribute(String name) {
        return attributes.containsKey(name.toLowerCase(Locale.ROOT));
    }

    public void setAttribute(String name, String value) {
        name = name.toLowerCase(Locale.ROOT);
        String v = value == null ? "" : value;
        String old = attributes.put(name, v);
        if (!v.equals(old)) attributeChanged(name, old, v);
    }

    public void removeAttribute(String name) {
        name = name.toLowerCase(Locale.ROOT);
        if (attributes.containsKey(name)) attributeChanged(name, attributes.remove(name), null);
    }

    public void toggleAttribute(String name, boolean on) {
        if (on) setAttribute(name, ""); else removeAttribute(name);
    }

    public Map<String, String> attributes() {
        return Collections.unmodifiableMap(attributes);
    }

    /**
     * A numeric attribute ({@code width="20"}, a {@code px} suffix allowed), or {@code fallback} when it is missing
     * or not a finite number.
     */
    public float numberAttribute(String name, float fallback) {
        String v = getAttribute(name);
        if (v == null) return fallback;
        v = v.strip();
        if (v.endsWith("px")) v = v.substring(0, v.length() - 2);
        try {
            float f = Float.parseFloat(v);
            return Float.isFinite(f) ? f : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void attributeChanged(String name, String old, String now) {
        switch (name) {
            case "class" -> classCache = null;
            case "style" -> parsedInlineStyle = null;
            case "type" -> inputType = null;
            default -> {
                if ((old == null) != (now == null)) countInlineHandler(ownerDocument, name, now == null ? -1 : 1);
            }
        }
        ownerDocument.attributeChanged(this, name);
    }

    /** Counts an {@code on<type>} attribute as a handler of {@code type} in {@code doc} (other attributes are not). */
    private static void countInlineHandler(Document doc, String attribute, int delta) {
        if (attribute.length() > 2 && attribute.startsWith("on")) doc.countHandlers(attribute.substring(2), delta);
    }

    @Override
    void adopted(Document from, Document to) {
        for (String name : attributes.keySet()) {
            countInlineHandler(from, name, -1);
            countInlineHandler(to, name, 1);
        }
    }

    public String id() {
        String id = getAttribute("id");
        return id == null ? "" : id;
    }

    // ---- Classes ----

    public Set<String> classes() {
        if (classCache == null) {
            List<String> tokens = tokens(attribute("class"));
            classCache = tokens.isEmpty() ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(tokens));
        }
        return classCache;
    }

    /** The tokens of a space-separated list (a class attribute), split on ASCII whitespace; empty for null. */
    static List<String> tokens(String list) {
        if (list == null) return List.of();
        List<String> out = new ArrayList<>(4);
        int start = -1;
        for (int i = 0, n = list.length(); i <= n; i++) {
            boolean space = i == n || isAsciiWhitespace(list.charAt(i));
            if (space && start >= 0) {
                out.add(list.substring(start, i));
                start = -1;
            } else if (!space && start < 0) {
                start = i;
            }
        }
        return out;
    }

    private static boolean isAsciiWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\f' || c == '\r';
    }

    public boolean hasClass(String c) { return classes().contains(c); }

    public void addClass(String c) {
        if (!hasClass(c)) setClasses(null, c);
    }

    public void removeClass(String c) {
        if (hasClass(c)) setClasses(c, null);
    }

    /** {@code classList.replace}: puts {@code replacement} where {@code token} was. False when it was not present. */
    public boolean replaceClass(String token, String replacement) {
        if (!hasClass(token)) return false;
        setClasses(token, hasClass(replacement) ? null : replacement);
        return true;
    }

    /** Rewrites the class attribute with {@code drop} replaced by {@code add} (or {@code add} appended). */
    private void setClasses(String drop, String add) {
        StringBuilder sb = new StringBuilder();
        for (String c : classes()) {
            String token = c.equals(drop) ? add : c;
            if (token != null) sb.append(sb.isEmpty() ? "" : " ").append(token);
            if (c.equals(drop)) add = null;
        }
        if (add != null) sb.append(sb.isEmpty() ? "" : " ").append(add);
        setAttribute("class", sb.toString());
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
        switch (position.toLowerCase(Locale.ROOT)) {
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

    /**
     * The text content with ASCII whitespace stripped and collapsed to single spaces, as HTML uses for an option's
     * text and the document title.
     */
    public String collapsedText() {
        String text = textContent();
        StringBuilder sb = new StringBuilder(text.length());
        boolean space = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isAsciiWhitespace(c)) {
                space = !sb.isEmpty();
            } else {
                if (space) sb.append(' ');
                sb.append(c);
                space = false;
            }
        }
        return sb.toString();
    }

    // ---- Cloning and scripts ----

    /** Copies the attributes, the live value and checkedness of controls, and a script's "already started" flag. */
    @Override
    Node cloneShallow() {
        Element copy = new Element(ownerDocument, tagName);
        copy.attributes.putAll(attributes);
        for (String name : attributes.keySet()) countInlineHandler(ownerDocument, name, 1);
        copy.value = value;
        copy.checked = checked;
        copy.alreadyStarted = alreadyStarted;
        return copy;
    }

    /** Marks a script as already started, so it never runs (HTML does this for scripts made by fragment parsing). */
    public void markAlreadyStarted() {
        alreadyStarted = true;
    }

    // ---- Interaction state ----

    public boolean isHovered() { return hovered; }
    public boolean isActive() { return active; }
    public boolean isFocused() { return focused; }

    /**
     * Disabled: the {@code disabled} attribute, an option in a disabled optgroup, or a control inside a disabled
     * fieldset.
     */
    public boolean isDisabled() {
        if (hasAttribute("disabled")) return true;
        if (tagName.equals("option") && parent instanceof Element g && g.tagName.equals("optgroup")
                && g.hasAttribute("disabled")) return true;
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
            case "button", "select", "textarea" -> true;
            case "input" -> !inputType().equals("hidden");
            case "a" -> hasAttribute("href");
            case "summary" -> parent instanceof Element p && p.tagName.equals("details");
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
        ownerDocument.setFocus(this);
    }

    public void blur() {
        if (ownerDocument.focusedElement() == this) ownerDocument.setFocus(null);
    }

    /** Fires a synthetic click and, unless cancelled, its default action, as {@code HTMLElement.click()} does. */
    public void click() {
        if (isDisabled()) return;
        float[] r = paintedRect();
        float cx = r[0] + r[2] / 2, cy = r[1] + r[3] / 2;
        MouseEvent e = new MouseEvent("click", true, true, cx, cy, 0, 0, Modifiers.NONE, 1, null);
        if (dispatchEvent(e)) ownerDocument.input().activate(this, e);
    }

    // ---- Form controls ----

    /**
     * An input's type, lower-case: "text" when missing or not a type Vellum knows (as HTML treats unknown types).
     * Empty for other elements.
     */
    public String inputType() {
        if (!tagName.equals("input")) return "";
        if (inputType == null) {
            String t = getAttribute("type");
            t = t == null ? "" : t.strip().toLowerCase(Locale.ROOT);
            inputType = TEXT_TYPES.contains(t) || OTHER_TYPES.contains(t) ? t : "text";
        }
        return inputType;
    }

    /** True for a textarea or a text-like input (text, password, number, search, email, url, tel, unknown types). */
    public boolean isTextControl() {
        return tagName.equals("textarea") || TEXT_TYPES.contains(inputType());
    }

    /** True for checkbox and radio inputs. */
    public boolean isCheckable() {
        String type = inputType();
        return type.equals("checkbox") || type.equals("radio");
    }

    /**
     * Whether {@link #value()} is live state (typed text, a slider position, the selection) rather than the
     * {@code value} attribute: textareas, selects, and inputs other than checkboxes, radios and buttons.
     */
    public boolean hasLiveValue() {
        return switch (tagName) {
            case "textarea", "select" -> true;
            case "input" -> switch (inputType()) {
                case "checkbox", "radio", "submit", "reset", "button", "image", "hidden" -> false;
                default -> true;
            };
            default -> false;
        };
    }

    /**
     * The value, as the DOM's {@code value} property: the live value once set (typed or by script); else a select's
     * selected option's value, an option's {@code value} attribute or else its collapsed text, a textarea's text, a
     * checkbox's or radio's {@code value} attribute or else "on", and otherwise the {@code value} attribute or "".
     */
    public String value() {
        if (tagName.equals("select")) {
            Element option = selectedOption();
            return option == null ? "" : option.value();
        }
        if (value != null) return value;
        String v = getAttribute("value");
        if (v != null) return v;
        return switch (tagName) {
            case "textarea" -> textContent();
            case "option" -> collapsedText();
            default -> isCheckable() ? "on" : "";
        };
    }

    /**
     * Sets the live value. A select instead selects its first option with that value (none when no option has it).
     * Restyles only when the value's emptiness flips ({@code :placeholder-shown}); painting reads the value directly.
     */
    public void setValue(String v) {
        if (tagName.equals("select")) {
            Element match = null;
            for (Element o : options()) {
                if (o.value().equals(v)) {
                    match = o;
                    break;
                }
            }
            select(match);
            return;
        }
        if (Objects.equals(value, v)) return;
        boolean wasEmpty = value().isEmpty();
        value = v;
        valueVersion++;
        if (wasEmpty != value().isEmpty()) ownerDocument.stateChanged(this);
    }

    /**
     * Incremented whenever the live value changes. With {@link Document#domVersion()} (attributes and children) it
     * tells form controls when what they derived from the value is stale.
     */
    public int valueVersion() {
        return valueVersion;
    }

    public boolean checked() {
        return checked != null ? checked : hasAttribute("checked");
    }

    /** Sets the checkedness; checking a radio unchecks the other radios of its group. */
    public void setChecked(boolean c) {
        if (c && inputType().equals("radio")) {
            for (Element other : radioGroup()) if (other != this) other.setCheckedness(false);
        }
        setCheckedness(c);
    }

    private void setCheckedness(boolean c) {
        if (checked != null && checked == c) return;
        boolean was = checked();
        checked = c;
        if (was != c) ownerDocument.stateChanged(this);
    }

    /** The form owner: the form named by the {@code form} attribute, else the nearest ancestor form, else null. */
    public Element form() {
        String id = getAttribute("form");
        if (id != null) {
            Element f = ownerDocument.getElementById(id);
            return f != null && f.tagName.equals("form") ? f : null;
        }
        for (Element p = parentElement(); p != null; p = p.parentElement()) if (p.tagName.equals("form")) return p;
        return null;
    }

    /** The radios sharing this radio's name and form owner, in tree order (just this radio when unnamed). */
    public List<Element> radioGroup() {
        String name = getAttribute("name");
        if (name == null || name.isEmpty()) return List.of(this);
        Element form = form();
        Node root = form;
        if (root == null) for (root = this; root.parent != null; ) root = root.parent;
        if (root == this) return List.of(this);
        return root.descendants(e -> e.inputType().equals("radio") && name.equals(e.getAttribute("name"))
                && e.form() == form);
    }

    // ---- Select and option ----

    /** A select's options: its option descendants, in tree order. */
    public List<Element> options() {
        return getElementsByTagName("option");
    }

    /**
     * A select's selected option: the last option that is selected (by the user, a script, or its {@code selected}
     * attribute); when none is, the first enabled option, unless a script cleared the selection
     * ({@code selectedIndex = -1}, or a value no option has). Null for an empty or cleared select.
     */
    public Element selectedOption() {
        Element chosen = null, firstEnabled = null;
        boolean cleared = true; // every option was explicitly deselected
        for (Element o : options()) {
            if (o.selectedness()) chosen = o;
            if (o.selected == null) cleared = false;
            if (firstEnabled == null && !o.isDisabled()) firstEnabled = o;
        }
        return chosen != null ? chosen : cleared ? null : firstEnabled;
    }

    public int selectedIndex() {
        Element option = selectedOption();
        return option == null ? -1 : options().indexOf(option);
    }

    /** Selects the option at {@code index}; any other index clears the selection. */
    public void setSelectedIndex(int index) {
        List<Element> options = options();
        select(index >= 0 && index < options.size() ? options.get(index) : null);
    }

    /** Whether this option is its select's selected option (for an option outside a select: its own selectedness). */
    public boolean selected() {
        Element select = ownerSelect();
        return select == null ? selectedness() : select.selectedOption() == this;
    }

    /** Selects this option (deselecting the others of its select), or deselects it. */
    public void setSelected(boolean on) {
        Element select = ownerSelect();
        if (select == null) {
            if (selectedness() != on) {
                selected = on;
                ownerDocument.stateChanged(this);
            }
        } else if (on) {
            select.select(this);
        } else if (select.selectedOption() == this) {
            selected = false;
            if (select.selectedOption() == null) select.select(select.firstEnabledOption()); // as HTML resets
            ownerDocument.stateChanged(select);
        } else {
            selected = false;
        }
    }

    /** Makes {@code option} the selected option of this select, or clears the selection for null. */
    private void select(Element option) {
        boolean changed = false;
        for (Element o : options()) {
            boolean on = o == option;
            changed |= o.selected == null || o.selected != on;
            o.selected = on;
        }
        if (changed) ownerDocument.stateChanged(this);
    }

    private boolean selectedness() {
        return selected != null ? selected : hasAttribute("selected");
    }

    private Element firstEnabledOption() {
        return firstDescendant(o -> o.tagName.equals("option") && !o.isDisabled());
    }

    private Element ownerSelect() {
        if (!tagName.equals("option")) return null;
        for (Element p = parentElement(); p != null; p = p.parentElement()) if (p.tagName.equals("select")) return p;
        return null;
    }

    /** An option's label: its {@code label} attribute, else its collapsed text. */
    public String label() {
        String label = getAttribute("label");
        return label != null ? label : collapsedText();
    }

    // ---- Geometry ----

    /**
     * {@code getBoundingClientRect()}: {x, y, width, height} in viewport px of the bounding box of the border box as
     * painted, transforms included; zeros without a box. Lays out first if the document has changed, as browsers do.
     */
    public float[] getBoundingClientRect() {
        ownerDocument.flushLayout();
        return paintedRect();
    }

    /** {@link #getBoundingClientRect()} of the current layout, without laying out: for paint and input. */
    private float[] paintedRect() {
        return box == null ? new float[4] : Coordinates.boundingRect(box);
    }

    /**
     * The part of the border box that can show, {x, y, width, height} in viewport px: {@link #getBoundingClientRect()}
     * cut to the viewport and to the scroll containers and other overflow clips it is in. Null without a box or when
     * none of it can show. Content painted over it still counts as showing. Lays out first, as
     * {@link #getBoundingClientRect()} does.
     */
    public float[] visibleRect() {
        ownerDocument.flushLayout();
        return box == null ? null
                : Coordinates.visibleRect(box, ownerDocument.viewportWidth(), ownerDocument.viewportHeight());
    }

    public float scrollWidth() { return box == null ? 0 : box.scrollWidth; }
    public float scrollHeight() { return box == null ? 0 : box.scrollHeight; }
    public float clientWidth() { return box == null ? 0 : box.paddingBoxWidth(); }
    public float clientHeight() { return box == null ? 0 : box.paddingBoxHeight(); }

    // ---- Scrolling ----

    /** How a scroll moves: {@code AUTO} follows the element's {@code scroll-behavior}. */
    public enum ScrollBehavior { AUTO, INSTANT, SMOOTH }

    /** Where {@link #scrollIntoView} puts the element in a scroller, per axis ({@code block}/{@code inline}). */
    public enum ScrollAlign { START, CENTER, END, NEAREST }

    /** The scroll offsets, in px (zero until the element is a scroll container that was scrolled). */
    public float scrollLeft() { return scrollLeft; }
    public float scrollTop() { return scrollTop; }

    /** Scrolls instantly to (left, top), clamped to the scrollable range. */
    public void scrollTo(float left, float top) {
        scrollTo(left, top, ScrollBehavior.INSTANT);
    }

    /**
     * Scrolls to (left, top), clamped to the scrollable range (nothing scrolls unless this is a scroll container):
     * instantly, or eased over the next frames when the behavior is smooth ({@code AUTO}: when the element's
     * {@code scroll-behavior} is; never when the host prefers reduced motion). Either replaces a smooth scroll in
     * progress. {@code scroll} fires on the next frame.
     */
    public void scrollTo(float left, float top, ScrollBehavior behavior) {
        float l = clampScroll(left, maxScrollLeft()), t = clampScroll(top, maxScrollTop());
        Scrolling scrolling = ownerDocument.scrolling();
        if (smooth(behavior) && (l != scrollLeft || t != scrollTop)) {
            scrollTargetLeft = l;
            scrollTargetTop = t;
            scrolling.animate(this);
        } else {
            scrollTargetLeft = scrollTargetTop = Float.NaN;
            scrolling.stop(this);
            moveScroll(l, t);
        }
    }

    /** Scrolls by (dx, dy) from where the element is heading: a smooth scroll's destination, else its position. */
    public void scrollBy(float dx, float dy, ScrollBehavior behavior) {
        scrollTo(scrollDestinationLeft() + dx, scrollDestinationTop() + dy, behavior);
    }

    /** Whether {@link #scrollBy} with this delta would move it (wheel scrolling chains outward when not). */
    public boolean canScrollBy(float dx, float dy) {
        return canMove(scrollDestinationLeft(), dx, maxScrollLeft()) || canMove(scrollDestinationTop(), dy, maxScrollTop());
    }

    /**
     * Clamps the position and a smooth scroll's destination to the scrollable range again, after layout changed
     * it. Moving fires {@code scroll} like any scroll.
     */
    public void clampScroll() {
        if (!Float.isNaN(scrollTargetLeft)) {
            scrollTargetLeft = clampScroll(scrollTargetLeft, maxScrollLeft());
            scrollTargetTop = clampScroll(scrollTargetTop, maxScrollTop());
        }
        moveScroll(clampScroll(scrollLeft, maxScrollLeft()), clampScroll(scrollTop, maxScrollTop()));
    }

    /**
     * Scrolls every scroll container this element's box is in, innermost first, so the element shows: aligned per
     * axis ({@code NEAREST}: the least scroll that shows it, none when it already shows), with the given behavior.
     */
    public void scrollIntoView(ScrollAlign block, ScrollAlign inline, ScrollBehavior behavior) {
        if (box == null) return;
        // Find every scroller's final position by scrolling instantly from the inside out (each scroll moves the
        // element as the next one sees it), then go there with the requested behavior.
        List<Element> scrollers = new ArrayList<>();
        List<float[]> starts = new ArrayList<>();
        Affine element = new Affine(), scroller = new Affine();
        float[] r = new float[4];
        for (Box c = box.contentParent(); c != null; c = c.contentParent()) {
            if (!c.isScrollContainer() || !Coordinates.fromViewport(c, scroller)) continue;
            scroller.multiply(Coordinates.toViewport(box, element)).mapBounds(0, 0, box.width, box.height, r);
            Element e = c.element;
            float dx = alignDelta(inline, r[0] - c.borderLeft, r[2], c.paddingBoxWidth());
            float dy = alignDelta(block, r[1] - c.borderTop, r[3], c.paddingBoxHeight());
            scrollers.add(e);
            starts.add(new float[] {e.scrollLeft, e.scrollTop});
            e.scrollLeft = clampScroll(e.scrollLeft + dx, e.maxScrollLeft());
            e.scrollTop = clampScroll(e.scrollTop + dy, e.maxScrollTop());
        }
        for (int i = 0; i < scrollers.size(); i++) {
            Element e = scrollers.get(i);
            float left = e.scrollLeft, top = e.scrollTop;
            e.scrollLeft = starts.get(i)[0];
            e.scrollTop = starts.get(i)[1];
            e.scrollTo(left, top, behavior);
        }
    }

    /**
     * How far to scroll an axis so [start, start + size), relative to a scrollport {@code view} long, lands as
     * {@code align} says. {@code NEAREST} aligns the nearer edge, or the start when it does not fit.
     */
    private static float alignDelta(ScrollAlign align, float start, float size, float view) {
        return switch (align) {
            case START -> start;
            case END -> start + size - view;
            case CENTER -> start + (size - view) / 2;
            case NEAREST -> start < 0 ? start : start + size > view ? Math.min(start + size - view, start) : 0;
        };
    }

    /** One step of a smooth scroll: a fraction {@code k} of the way to the destination. True when it arrived. */
    boolean stepSmoothScroll(float k) {
        if (Float.isNaN(scrollTargetLeft)) return true;
        float left = approach(scrollLeft, scrollTargetLeft, k), top = approach(scrollTop, scrollTargetTop, k);
        boolean arrived = left == scrollTargetLeft && top == scrollTargetTop;
        if (arrived) scrollTargetLeft = scrollTargetTop = Float.NaN;
        moveScroll(left, top);
        return arrived;
    }

    private void moveScroll(float left, float top) {
        if (left == scrollLeft && top == scrollTop) return;
        scrollLeft = left;
        scrollTop = top;
        ownerDocument.scrolling().moved(this);
        ownerDocument.invalidatePaint();
    }

    private boolean smooth(ScrollBehavior behavior) {
        if (ownerDocument.host().prefersReducedMotion()) return false;
        return switch (behavior) {
            case INSTANT -> false;
            case SMOOTH -> true;
            case AUTO -> style != null && style.scrollSmooth;
        };
    }

    private float scrollDestinationLeft() { return Float.isNaN(scrollTargetLeft) ? scrollLeft : scrollTargetLeft; }
    private float scrollDestinationTop() { return Float.isNaN(scrollTargetTop) ? scrollTop : scrollTargetTop; }
    private float maxScrollLeft() { return box != null && box.isScrollContainer() ? box.maxScrollLeft() : 0; }
    private float maxScrollTop() { return box != null && box.isScrollContainer() ? box.maxScrollTop() : 0; }

    private static float clampScroll(float v, float max) {
        return Math.max(0, Math.min(max, v));
    }

    /** True when a position can still move by {@code delta} within [0, max]. */
    private static boolean canMove(float position, float delta, float max) {
        return delta > 0 ? position < max : delta < 0 && position > 0;
    }

    private static float approach(float from, float to, float k) {
        float v = from + (to - from) * k;
        return Math.abs(to - v) < 0.5f ? to : v;
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
