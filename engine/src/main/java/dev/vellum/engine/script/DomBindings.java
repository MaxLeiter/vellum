package dev.vellum.engine.script;

import dev.vellum.engine.css.InlineStyle;
import dev.vellum.engine.css.Selectors;
import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.DocumentFragment;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Node;
import dev.vellum.engine.dom.Text;
import dev.vellum.engine.event.Event;
import dev.vellum.engine.event.EventListener;
import dev.vellum.engine.html.HtmlParser;
import dev.vellum.shadow.rhino.Callable;
import dev.vellum.shadow.rhino.Scriptable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The DOM for scripts: Node, Element, Text, Document and DocumentFragment, with one wrapper per node cached in
 * {@link Node#scriptWrapper}. Collections are returned as JS array snapshots. Geometry reads flush layout first.
 */
final class DomBindings {
    /** Events with an {@code on<type>} property ({@code button.onclick = fn}). */
    private static final List<String> HANDLER_EVENTS = List.of("click", "dblclick", "contextmenu", "mousedown",
            "mouseup", "mousemove", "mouseover", "mouseout", "mouseenter", "mouseleave", "wheel", "keydown", "keyup",
            "beforeinput", "input", "change", "focus", "blur", "focusin", "focusout", "scroll", "load", "resize",
            "message", "transitionend", "animationend");
    /** innerHTML, outerHTML, insertAdjacentHTML and v-html take at most this many characters. */
    static final int MAX_MARKUP = 1 << 20;

    private final RhinoScriptRuntime rt;
    private final HostClass<Node> node;
    private final HostClass<Element> element;
    private final HostClass<Text> text;
    private final HostClass<Document> document;
    private final HostClass<DocumentFragment> fragment;

    DomBindings(RhinoScriptRuntime rt) {
        this.rt = rt;
        node = new HostClass<>(rt, "Node", Node.class, null, null).expose("Node");
        defineNode(node.members());
        element = new HostClass<>(rt, "Element", Element.class, node, null).expose("Element", "HTMLElement");
        defineElement(element.members());
        text = new HostClass<>(rt, "Text", Text.class, node, null).expose("Text");
        text.members()
                .prop("data", Text::data, (t, v) -> t.setData(Js.str(v)))
                .prop("nodeValue", Text::data, (t, v) -> t.setData(Js.str(v)))
                .get("length", t -> t.data().length());
        document = new HostClass<>(rt, "Document", Document.class, node, null).expose("Document");
        defineDocument(document.members());
        fragment = new HostClass<>(rt, "DocumentFragment", DocumentFragment.class, node, null).expose("DocumentFragment");
    }

    /** The node's wrapper, created once. */
    HostObject wrap(Node n) {
        if (n.scriptWrapper instanceof HostObject w) return w;
        HostObject w = switch (n) {
            case Element e -> element.wrap(e);
            case Text t -> text.wrap(t);
            case Document d -> document.wrap(d);
            case DocumentFragment f -> fragment.wrap(f);
            default -> node.wrap(n);
        };
        n.scriptWrapper = w;
        return w;
    }

    private void defineNode(Members<Node> m) {
        m.get("nodeType", DomBindings::nodeType)
                .get("nodeName", Node::nodeName)
                .get("ownerDocument", Node::ownerDocument)
                .get("isConnected", Node::isConnected)
                .get("parentNode", Node::parentNode)
                .get("parentElement", Node::parentElement)
                .get("childNodes", Node::childNodes)
                .get("firstChild", Node::firstChild)
                .get("lastChild", Node::lastChild)
                .get("previousSibling", Node::previousSibling)
                .get("nextSibling", Node::nextSibling)
                .get("children", Node::children)
                .get("childElementCount", n -> n.children().size())
                .get("firstElementChild", n -> n.children().stream().findFirst().orElse(null))
                .get("lastElementChild", n -> n.children().isEmpty() ? null : n.children().getLast())
                .get("previousElementSibling", n -> elementSibling(n, false))
                .get("nextElementSibling", n -> elementSibling(n, true))
                .prop("textContent", Node::textContent, DomBindings::setText)
                .method("hasChildNodes", (n, a) -> n.childCount() > 0)
                .method("contains", (n, a) -> !a.isNullish(0) && n.contains(a.node(0)))
                .method("appendChild", (n, a) -> n.appendChild(a.node(0)))
                .method("insertBefore", (n, a) -> n.insertBefore(a.node(0), a.nodeOrNull(1)))
                .method("removeChild", (n, a) -> n.removeChild(a.node(0)))
                .method("replaceChild", (n, a) -> n.replaceChild(a.node(0), a.node(1)))
                .method("cloneNode", (n, a) -> clone(n, a.bool(0)))
                .action("remove", (n, a) -> n.remove())
                // ParentNode / ChildNode: arguments are nodes or strings, inserted together as one fragment.
                .action("append", (n, a) -> n.appendChild(fragment(a)))
                .action("prepend", (n, a) -> n.insertBefore(fragment(a), n.firstChild()))
                .action("replaceChildren", (n, a) -> {
                    DocumentFragment nodes = fragment(a);
                    n.removeAllChildren();
                    n.appendChild(nodes);
                })
                .action("before", (n, a) -> insertAround(n, a, false))
                .action("after", (n, a) -> insertAround(n, a, true))
                .action("replaceWith", (n, a) -> {
                    Node parent = n.parentNode();
                    if (parent != null) parent.replaceChild(fragment(a), n);
                })
                .method("querySelector", (n, a) -> syntax(() -> Selectors.querySelector(n, a.str(0))))
                .method("querySelectorAll", (n, a) -> syntax(() -> Selectors.querySelectorAll(n, a.str(0))))
                .method("getElementsByTagName", (n, a) -> {
                    String tag = a.str(0).toLowerCase(Locale.ROOT);
                    return descendants(n, e -> tag.equals("*") || e.tagName().equals(tag));
                })
                .method("getElementsByClassName", (n, a) -> {
                    Set<String> wanted = Set.copyOf(List.of(a.str(0).trim().split("\\s+")));
                    return descendants(n, e -> e.classes().containsAll(wanted));
                })
                .action("addEventListener", (n, a) -> rt.events.addListener(n, a))
                .action("removeEventListener", (n, a) -> rt.events.removeListener(n, a))
                .method("dispatchEvent", (n, a) -> rt.events.dispatch(n, a));
        for (String type : HANDLER_EVENTS) {
            m.prop("on" + type, n -> wrap(n).getAssociatedValue("on" + type) instanceof HandlerProperty h ? h.fn : null,
                    (n, v) -> handler(n, type).fn = v instanceof Callable fn ? fn : null);
        }
    }

    private void defineElement(Members<Element> m) {
        m.get("tagName", Element::nodeName)
                .get("localName", Element::tagName)
                .prop("id", Element::id, (e, v) -> e.setAttribute("id", Js.str(v)))
                .get("classList", e -> companion(e, "classList", rt.styles::classList))
                .get("dataset", e -> companion(e, "dataset", rt.styles::dataset))
                .prop("style", e -> companion(e, "style", rt.styles::style), (e, v) -> InlineStyle.setCssText(e, Js.str(v)))
                .get("attributes", e -> e.attributes().entrySet().stream()
                        .map(en -> Map.of("name", en.getKey(), "value", en.getValue())).toList())
                .method("getAttribute", (e, a) -> e.getAttribute(a.str(0)))
                .action("setAttribute", (e, a) -> e.setAttribute(a.str(0), a.str(1)))
                .action("removeAttribute", (e, a) -> e.removeAttribute(a.str(0)))
                .method("hasAttribute", (e, a) -> e.hasAttribute(a.str(0)))
                .method("hasAttributes", (e, a) -> !e.attributes().isEmpty())
                .method("getAttributeNames", (e, a) -> List.copyOf(e.attributes().keySet()))
                .method("toggleAttribute", (e, a) -> {
                    boolean on = a.has(1) ? a.bool(1) : !e.hasAttribute(a.str(0));
                    e.toggleAttribute(a.str(0), on);
                    return on;
                })
                .prop("innerHTML", Element::innerHTML, (e, v) -> e.setInnerHTML(markup(v)))
                .prop("outerHTML", Element::outerHTML, (e, v) -> setOuterHTML(e, markup(v)))
                .action("insertAdjacentHTML", (e, a) -> syntax(() -> {
                    e.insertAdjacentHTML(a.str(0), markup(a.get(1)));
                    return null;
                }))
                .prop("innerText", Node::textContent, DomBindings::setText)
                .method("matches", (e, a) -> syntax(() -> e.matches(a.str(0))))
                .method("closest", (e, a) -> syntax(() -> e.closest(a.str(0))))
                // Form state.
                .prop("value", Forms::value, (e, v) -> e.setValue(Js.isNullish(v) ? "" : Js.str(v)))
                .prop("checked", Element::checked, (e, v) -> e.setChecked(Js.bool(v)))
                .prop("selected", Forms::selected, (e, v) -> Forms.setSelected(e, Js.bool(v)))
                .prop("selectedIndex", Forms::selectedIndex, (e, v) -> Forms.setSelectedIndex(e, (int) Js.num(v)))
                .get("options", Forms::options)
                .prop("type", e -> e.tagName().equals("input") ? Forms.type(e) : Objects.requireNonNullElse(e.getAttribute("type"), ""),
                        (e, v) -> e.setAttribute("type", Js.str(v)))
                .prop("tabIndex", Element::tabIndex, (e, v) -> e.setAttribute("tabindex", String.valueOf((int) Js.num(v))))
                // Geometry, in GUI px after a layout flush.
                .method("getBoundingClientRect", (e, a) -> rect(laidOut(e).getBoundingClientRect()))
                .get("offsetParent", e -> null)
                .get("offsetLeft", e -> Math.round(laidOut(e).getBoundingClientRect()[0]))
                .get("offsetTop", e -> Math.round(laidOut(e).getBoundingClientRect()[1]))
                .get("offsetWidth", e -> Math.round(laidOut(e).getBoundingClientRect()[2]))
                .get("offsetHeight", e -> Math.round(laidOut(e).getBoundingClientRect()[3]))
                .get("clientWidth", e -> Math.round(laidOut(e).clientWidth()))
                .get("clientHeight", e -> Math.round(laidOut(e).clientHeight()))
                .get("scrollWidth", e -> Math.round(laidOut(e).scrollWidth()))
                .get("scrollHeight", e -> Math.round(laidOut(e).scrollHeight()))
                .prop("scrollLeft", e -> laidOut(e).scrollLeft, (e, v) -> laidOut(e).scrollTo((float) Js.num(v), e.scrollTop))
                .prop("scrollTop", e -> laidOut(e).scrollTop, (e, v) -> laidOut(e).scrollTo(e.scrollLeft, (float) Js.num(v)))
                .action("scrollTo", (e, a) -> scroll(laidOut(e), a, false))
                .action("scrollBy", (e, a) -> scroll(laidOut(e), a, true))
                .action("scrollIntoView", (e, a) -> scrollIntoView(laidOut(e)))
                .action("focus", (e, a) -> e.focus())
                .action("blur", (e, a) -> e.blur())
                .action("click", (e, a) -> e.click())
                .method("animate", (e, a) -> rt.animations.animate(e, a));
        for (String attribute : List.of("name", "placeholder", "href", "src", "title")) reflect(m, attribute, attribute);
        reflect(m, "className", "class");
        for (String flag : List.of("disabled", "hidden", "readOnly", "required", "autofocus")) {
            String attribute = flag.toLowerCase(Locale.ROOT);
            m.prop(flag, e -> e.hasAttribute(attribute), (e, v) -> e.toggleAttribute(attribute, Js.bool(v)));
        }
    }

    private void defineDocument(Members<Document> m) {
        m.get("documentElement", Document::documentElement)
                .get("head", Document::head)
                .get("body", Document::body)
                .prop("title", DomBindings::title, (d, v) -> setTitle(d, Js.str(v)))
                .get("activeElement", d -> d.focusedElement() != null ? d.focusedElement() : d.body())
                .get("defaultView", d -> rt.global)
                .get("location", d -> Js.property(rt.global, "location"))
                .get("URL", Document::url)
                .get("readyState", d -> rt.templates.installed() ? "complete" : "interactive")
                .method("getElementById", (d, a) -> d.getElementById(a.str(0)))
                .method("createElement", (d, a) -> d.createElement(a.str(0)))
                .method("createTextNode", (d, a) -> d.createTextNode(a.str(0)))
                .method("createDocumentFragment", (d, a) -> d.createDocumentFragment());
    }

    // ---- Tree helpers ----

    private static int nodeType(Node n) {
        return n instanceof Element ? 1 : n instanceof Text ? 3 : n instanceof Document ? 9 : 11;
    }

    private static Element elementSibling(Node n, boolean next) {
        for (Node s = next ? n.nextSibling() : n.previousSibling(); s != null; s = next ? s.nextSibling() : s.previousSibling()) {
            if (s instanceof Element e) return e;
        }
        return null;
    }

    private static void setText(Node n, Object value) {
        if (!(n instanceof Document)) n.setTextContent(Js.isNullish(value) ? "" : Js.str(value));
    }

    /** {@code cloneNode}: elements copy their attributes (not listeners or live form state), as in browsers. */
    static Node clone(Node node, boolean deep) {
        Node copy = switch (node) {
            case Element e -> {
                Element c = e.ownerDocument().createElement(e.tagName());
                e.attributes().forEach(c::setAttribute);
                yield c;
            }
            case Text t -> t.ownerDocument().createTextNode(t.data());
            case DocumentFragment f -> f.ownerDocument().createDocumentFragment();
            default -> throw new IllegalArgumentException("Cannot clone " + node.nodeName());
        };
        if (deep) for (Node child : node.childNodes()) copy.appendChild(clone(child, true));
        return copy;
    }

    /** The nodes (strings become text) of {@code append(...)}, {@code before(...)} and friends, as one fragment. */
    private DocumentFragment fragment(Args a) {
        DocumentFragment f = rt.document.createDocumentFragment();
        for (Object v : a.values()) {
            f.appendChild(v instanceof HostObject h && h.target instanceof Node n ? n : rt.document.createTextNode(Js.str(v)));
        }
        return f;
    }

    private void insertAround(Node n, Args a, boolean after) {
        Node parent = n.parentNode();
        if (parent == null) return;
        DocumentFragment nodes = fragment(a); // first: it may move n's siblings
        parent.insertBefore(nodes, after ? n.nextSibling() : n);
    }

    private static List<Element> descendants(Node root, Predicate<Element> filter) {
        List<Element> out = new ArrayList<>();
        collect(root, filter, out);
        return out;
    }

    private static void collect(Node n, Predicate<Element> filter, List<Element> out) {
        for (Node c : n.childNodes()) {
            if (c instanceof Element e && filter.test(e)) out.add(e);
            collect(c, filter, out);
        }
    }

    /** Runs a selector call; an invalid selector (or position) becomes a SyntaxError, as in browsers. */
    private static Object syntax(Supplier<Object> call) {
        try {
            return call.get();
        } catch (IllegalArgumentException e) {
            throw Js.error("SyntaxError", e.getMessage());
        }
    }

    // ---- Element helpers ----

    private static void reflect(Members<Element> m, String property, String attribute) {
        m.prop(property, e -> Objects.requireNonNullElse(e.getAttribute(attribute), ""),
                (e, v) -> e.setAttribute(attribute, Js.str(v)));
    }

    /** A per-element companion object ({@code classList}, {@code dataset}, {@code style}) created once, so identity holds. */
    private Scriptable companion(Element e, String key, Function<Element, Scriptable> create) {
        HostObject w = wrap(e);
        if (w.getAssociatedValue(key) instanceof Scriptable s) return s;
        return (Scriptable) w.associateValue(key, create.apply(e));
    }

    /** Markup set by scripts, capped because there are no memory limits otherwise. */
    static String markup(Object value) {
        String html = Js.isNullish(value) ? "" : Js.str(value);
        if (html.length() > MAX_MARKUP) throw Js.error("RangeError", "Markup longer than " + MAX_MARKUP + " characters");
        return html;
    }

    private void setOuterHTML(Element e, String html) {
        Node parent = e.parentNode();
        if (parent == null) return;
        for (Node n : HtmlParser.parseFragment(rt.document, html)) parent.insertBefore(n, e);
        e.remove();
    }

    private Element laidOut(Element e) {
        rt.document.flushLayout();
        return e;
    }

    private static Map<String, Float> rect(float[] r) {
        Map<String, Float> rect = new LinkedHashMap<>();
        rect.put("x", r[0]);
        rect.put("y", r[1]);
        rect.put("width", r[2]);
        rect.put("height", r[3]);
        rect.put("top", r[1]);
        rect.put("right", r[0] + r[2]);
        rect.put("bottom", r[1] + r[3]);
        rect.put("left", r[0]);
        return rect;
    }

    /** {@code scrollTo(x, y)} / {@code scrollTo({left, top})}, or the scrollBy forms when {@code relative}. */
    private static void scroll(Element e, Args a, boolean relative) {
        float left, top;
        if (a.get(0) instanceof Scriptable options) {
            Object l = Js.property(options, "left"), t = Js.property(options, "top");
            left = Js.isNullish(l) ? (relative ? 0 : e.scrollLeft) : (float) Js.num(l);
            top = Js.isNullish(t) ? (relative ? 0 : e.scrollTop) : (float) Js.num(t);
        } else {
            left = (float) a.num(0, 0);
            top = (float) a.num(1, 0);
        }
        if (relative) {
            left += e.scrollLeft;
            top += e.scrollTop;
        }
        e.scrollTo(left, top);
    }

    /** Scrolls each scrollable ancestor so the element's top edge is at its top, and it is horizontally in view. */
    private static void scrollIntoView(Element e) {
        if (e.box == null) return;
        for (Element p = e.parentElement(); p != null; p = p.parentElement()) {
            if (p.box == null || !p.box.isScrollContainer()) continue;
            float[] r = e.getBoundingClientRect(), pr = p.getBoundingClientRect();
            float top = r[1] - (pr[1] + p.box.borderTop);
            float left = r[0] - (pr[0] + p.box.borderLeft);
            float dx = left < 0 ? left : Math.max(0, left + r[2] - p.clientWidth());
            p.scrollTo(p.scrollLeft + dx, p.scrollTop + top);
        }
    }

    private static String title(Document d) {
        Element title = d.head() == null ? null : d.head().getElementsByTagName("title").stream().findFirst().orElse(null);
        return title == null ? "" : title.textContent().strip().replaceAll("\\s+", " ");
    }

    private static void setTitle(Document d, String value) {
        Element head = d.head();
        if (head == null) return;
        Element title = head.getElementsByTagName("title").stream().findFirst()
                .orElseGet(() -> head.appendChild(d.createElement("title")));
        title.setTextContent(value);
    }

    // ---- on<event> properties ----

    /** The listener behind an {@code on<type>} property: added once, it calls whatever function the property holds. */
    private final class HandlerProperty implements EventListener {
        Callable fn;

        @Override
        public void handleEvent(Event e) {
            if (fn == null) return;
            Object result = rt.call("Error in on" + e.type + " handler", fn, wrap(e.currentTarget()), e);
            if (Boolean.FALSE.equals(result)) e.preventDefault();
        }
    }

    /** The property's listener, added on first assignment. */
    private HandlerProperty handler(Node n, String type) {
        HostObject w = wrap(n);
        if (w.getAssociatedValue("on" + type) instanceof HandlerProperty h) return h;
        HandlerProperty h = new HandlerProperty();
        n.addEventListener(type, h);
        return (HandlerProperty) w.associateValue("on" + type, h);
    }
}
