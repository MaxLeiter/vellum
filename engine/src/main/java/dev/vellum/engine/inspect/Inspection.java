package dev.vellum.engine.inspect;

import dev.vellum.engine.css.StyleEngine;
import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Node;
import dev.vellum.engine.dom.Text;
import dev.vellum.engine.layout.Box;
import dev.vellum.engine.paint.Affine;
import dev.vellum.engine.paint.Coordinates;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Prop;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One page inspecting another: the inspected document's elements by number, as plain maps and lists an inspector page
 * reads through its {@code inspector} global, and the edits it makes. Which element is hovered and selected, and
 * whether the player is picking one with the pointer, is kept here for the host to highlight.
 */
public final class Inspection {
    /** Text shown in the tree for an element holding only text, cut to this many characters. */
    private static final int TEXT_PREVIEW = 40;

    private Document document;
    private final Map<Element, Integer> ids = new IdentityHashMap<>();
    private final List<Element> elements = new ArrayList<>();
    private Element hovered, selected;
    private boolean picking;
    /** Changes when the inspected page, the selection or picking does; the tree also follows the DOM version. */
    private int changes;

    /** Inspects {@code document} (null for none): a reloaded page starts over, with nothing selected. */
    public void retarget(Document document) {
        if (document == this.document) return;
        this.document = document;
        ids.clear();
        elements.clear();
        hovered = selected = null;
        changes++;
    }

    public Document document() { return document; }

    /** The element to highlight: the hovered one, else the selected one; null for none. */
    public Element highlighted() {
        Element e = hovered != null ? hovered : selected;
        return e != null && e.isConnected() && e.ownerDocument() == document ? e : null;
    }

    public boolean picking() { return picking; }

    public void pick(boolean on) {
        picking = on;
        if (!on) hovered = null;
        changes++;
    }

    /** The pointer is over {@code element} while picking, or the inspector page hovers one (null for none). */
    public void hover(Element element) {
        hovered = element;
    }

    /** Selects {@code element}, from the inspector page or a pick, which ends. */
    public void select(Element element) {
        selected = element;
        hovered = null;
        picking = false;
        changes++;
    }

    // ---- For the inspector page ----

    /** {stamp, selected, picking, url}: the stamp changes whenever the tree or the selection may have. */
    public Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stamp", document == null ? -1 : Objects.hash(changes, document.domVersion()));
        m.put("selected", selected == null || !selected.isConnected() ? -1 : id(selected));
        m.put("picking", picking);
        m.put("url", document == null ? "" : document.url());
        return m;
    }

    /** The inspected page's elements from {@code <html>} down: {id, label, text?, kids}. Null without a page. */
    public Map<String, Object> tree() {
        Element root = document == null ? null : document.documentElement();
        return root == null ? null : node(root);
    }

    private Map<String, Object> node(Element e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id(e));
        m.put("label", e.toString());
        List<Element> children = e.children();
        if (children.isEmpty()) {
            String text = e.textContent().strip().replaceAll("\\s+", " ");
            if (!text.isEmpty()) m.put("text", text.length() > TEXT_PREVIEW ? text.substring(0, TEXT_PREVIEW) + "…" : text);
        }
        List<Object> kids = new ArrayList<>(children.size());
        for (Element child : children) kids.add(node(child));
        m.put("kids", kids);
        return m;
    }

    /**
     * Everything the details pane shows about element {@code id}: its attributes, inline style, text (for elements
     * holding only text), ancestors, box (viewport bounds and the margin, border and padding widths) and the computed
     * values that differ from the initial ones. Null for an element no longer in the page.
     */
    public Map<String, Object> details(int id) {
        Element e = element(id);
        if (e == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", e.toString());
        m.put("tag", e.tagName());
        List<Object> attributes = new ArrayList<>();
        e.attributes().forEach((name, value) -> {
            if (!name.equals("style")) attributes.add(List.of(name, value));
        });
        m.put("attributes", attributes);
        m.put("style", Objects.requireNonNullElse(e.getAttribute("style"), ""));
        if (onlyText(e)) m.put("text", e.textContent());
        List<Object> path = new ArrayList<>();
        for (Element a = e.parentElement(); a != null; a = a.parentElement()) path.addFirst(List.of(id(a), a.toString()));
        m.put("path", path);
        Box box = e.box;
        if (box != null) {
            float[] r = Coordinates.toViewport(box, new Affine()).mapBounds(0, 0, box.width, box.height, new float[4]);
            m.put("box", Map.of(
                    "x", r[0], "y", r[1], "width", r[2], "height", r[3],
                    "margin", List.of(box.marginTop, box.marginRight, box.marginBottom, box.marginLeft),
                    "border", List.of(box.borderTop, box.borderRight, box.borderBottom, box.borderLeft),
                    "padding", List.of(box.paddingTop, box.paddingRight, box.paddingBottom, box.paddingLeft)));
        }
        ComputedStyle style = e.style;
        List<Object> computed = new ArrayList<>();
        if (style != null) {
            for (Prop p : Prop.values()) {
                if (Objects.equals(p.get(style), p.get(ComputedStyle.INITIAL))) continue;
                String value = StyleEngine.computedValue(style, p.cssName);
                if (!value.isEmpty()) computed.add(List.of(p.cssName, value));
            }
        }
        m.put("computed", computed);
        return m;
    }

    public void select(int id) { select(element(id)); }

    public void hover(int id) { hover(element(id)); }

    /** Sets attribute {@code name} of element {@code id}, or removes it for a null value; false for a bad name. */
    public boolean setAttribute(int id, String name, String value) {
        Element e = element(id);
        name = name.strip();
        if (e == null || name.isEmpty() || !name.chars().allMatch(c -> c > ' ' && c != '"' && c != '\'' && c != '>' && c != '/' && c != '=')) {
            return false;
        }
        if (value == null) e.removeAttribute(name);
        else e.setAttribute(name, value);
        return true;
    }

    /** Replaces the text of element {@code id}, if it holds only text. */
    public boolean setText(int id, String text) {
        Element e = element(id);
        if (e == null || !onlyText(e)) return false;
        e.setTextContent(text);
        return true;
    }

    /** Removes element {@code id} from the page (not {@code <html>}, {@code <head>} or {@code <body>}). */
    public boolean remove(int id) {
        Element e = element(id);
        if (e == null || e.parentElement() == null || e.parentElement() == document.documentElement()) return false;
        if (e == selected) selected = e.parentElement();
        e.remove();
        changes++;
        return true;
    }

    // ---- Ids ----

    private int id(Element e) {
        Integer id = ids.get(e);
        if (id != null) return id;
        ids.put(e, elements.size());
        elements.add(e);
        return elements.size() - 1;
    }

    /** Element {@code id} while it is in the inspected page, else null. */
    private Element element(int id) {
        if (id < 0 || id >= elements.size()) return null;
        Element e = elements.get(id);
        return e.isConnected() && e.ownerDocument() == document ? e : null;
    }

    private static boolean onlyText(Element e) {
        for (Node n : e.childNodes()) if (!(n instanceof Text)) return false;
        return true;
    }
}
