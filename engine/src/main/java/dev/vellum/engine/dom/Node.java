package dev.vellum.engine.dom;

import dev.vellum.engine.event.Event;
import dev.vellum.engine.event.EventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A DOM node. The tree API follows the DOM (appendChild, insertBefore, removeChild...); mutations of the document's
 * tree tell the owning {@link Document}, which invalidates what they affect.
 *
 * <p>Template contents are inert: the descendants of a {@code <template>} are not visited by tree queries
 * ({@link #forEachElement}, {@link #descendants}, {@code getElementById}, {@code querySelector}), their scripts never
 * run, and they are never styled or laid out as part of the page.
 */
public abstract class Node {
    Document ownerDocument;
    Node parent;
    /** Position in {@code parent.children}, kept up to date by {@link #link} and {@link #unlink}. */
    private int index;
    final List<Node> children = new ArrayList<>(0);
    /** Listeners by type. Each list is replaced, never modified, so dispatch iterates it without copying. */
    private Map<String, List<Listener>> listeners;

    /** Slot for the script runtime's wrapper object, so each node maps to one script object. */
    public Object scriptWrapper;

    Node(Document ownerDocument) {
        this.ownerDocument = ownerDocument;
    }

    public abstract String nodeName();

    public Document ownerDocument() { return ownerDocument; }
    public Node parentNode() { return parent; }

    public Element parentElement() {
        return parent instanceof Element e ? e : null;
    }

    /** The document this node belongs to: its owner, or itself for a document. */
    private Document doc() {
        return ownerDocument != null ? ownerDocument : (Document) this;
    }

    public List<Node> childNodes() { return Collections.unmodifiableList(children); }
    public int childCount() { return children.size(); }
    public Node childAt(int i) { return children.get(i); }
    public Node firstChild() { return children.isEmpty() ? null : children.get(0); }
    public Node lastChild() { return children.isEmpty() ? null : children.get(children.size() - 1); }

    public Node nextSibling() {
        return parent != null && index + 1 < parent.children.size() ? parent.children.get(index + 1) : null;
    }

    public Node previousSibling() {
        return parent != null && index > 0 ? parent.children.get(index - 1) : null;
    }

    /** Child elements only. */
    public List<Element> children() {
        List<Element> out = new ArrayList<>();
        for (Node n : children) if (n instanceof Element e) out.add(e);
        return out;
    }

    public boolean isConnected() {
        Node n = this;
        while (n.parent != null) n = n.parent;
        return n instanceof Document;
    }

    public boolean contains(Node other) {
        for (Node n = other; n != null; n = n.parent) if (n == this) return true;
        return false;
    }

    /** True inside template contents (below a {@code <template>}), which scripts and queries do not reach. */
    public boolean isInert() {
        for (Element p = parentElement(); p != null; p = p.parentElement()) if (p.hasInertContent()) return true;
        return false;
    }

    // ---- Tree queries (template contents excluded) ----

    /**
     * Calls {@code action} for every element below this node, in tree order. Should the action change the tree, the
     * walk stays safe but may miss or repeat elements: use {@link #descendants} to work on a snapshot.
     */
    public void forEachElement(Consumer<Element> action) {
        find(this, e -> {
            action.accept(e);
            return false;
        });
    }

    /** The elements below this node that pass {@code filter}, in tree order. */
    public List<Element> descendants(Predicate<Element> filter) {
        List<Element> out = new ArrayList<>();
        forEachElement(e -> {
            if (filter.test(e)) out.add(e);
        });
        return out;
    }

    /** The first element below this node, in tree order, that passes {@code filter}; null if none does. */
    public Element firstDescendant(Predicate<Element> filter) {
        return find(this, filter);
    }

    /** Descendant elements with this tag name (any case), or all of them for {@code "*"}. */
    public List<Element> getElementsByTagName(String tag) {
        String t = tag.toLowerCase(Locale.ROOT);
        return descendants(e -> t.equals("*") || e.tagName().equals(t));
    }

    /** Descendant elements that have every class in the space-separated {@code names}. */
    public List<Element> getElementsByClassName(String names) {
        List<String> wanted = Element.tokens(names);
        return wanted.isEmpty() ? List.of() : descendants(e -> e.classes().containsAll(wanted));
    }

    private static Element find(Node root, Predicate<Element> test) {
        if (root instanceof Element e && e.hasInertContent()) return null;
        for (int i = 0; i < root.children.size(); i++) { // re-read: a visitor's listener may change the tree
            if (!(root.children.get(i) instanceof Element e)) continue;
            if (test.test(e)) return e;
            Element found = find(e, test);
            if (found != null) return found;
        }
        return null;
    }

    // ---- Mutation ----

    public <T extends Node> T appendChild(T child) {
        return insertBefore(child, null);
    }

    /**
     * Inserts {@code child} before {@code reference} (at the end for null). A fragment inserts its children instead.
     * Moving a node within the document keeps its state (focus, hover, replaced content such as canvases): only
     * nodes that leave the document lose it.
     */
    public <T extends Node> T insertBefore(T child, Node reference) {
        if (child instanceof Document) throw new IllegalArgumentException("Cannot insert a document");
        if (child.contains(this)) throw new IllegalArgumentException("Cannot insert a node into its own subtree");
        if (reference != null && reference.parent != this) {
            throw new IllegalArgumentException("Reference node is not a child of this node");
        }
        if (child instanceof DocumentFragment fragment) {
            for (Node first; (first = fragment.firstChild()) != null; ) insertBefore(first, reference);
            return child;
        }
        if (reference == child) reference = child.nextSibling();
        Document doc = doc();
        boolean wasConnected = child.isConnected(), connected = isConnected();
        boolean move = wasConnected && connected && child.ownerDocument == doc;
        if (child.parent != null) {
            child.parent.unlink(child, !move);
            if (child.parent != null) return child; // a blur listener put it somewhere else: leave it there
        }
        link(child, reference == null || reference.parent != this ? children.size() : reference.index);
        child.adopt(doc);
        if (connected) doc.inserted(child, !move);
        return child;
    }

    public <T extends Node> T removeChild(T child) {
        if (child.parent != this) throw new IllegalArgumentException("Not a child of this node");
        unlink(child, true);
        return child;
    }

    public Node replaceChild(Node newChild, Node oldChild) {
        insertBefore(newChild, oldChild);
        return removeChild(oldChild);
    }

    /** Removes this node from its parent, if any. */
    public void remove() {
        if (parent != null) parent.removeChild(this);
    }

    public void removeAllChildren() {
        while (!children.isEmpty()) removeChild(children.get(children.size() - 1));
    }

    private void link(Node child, int at) {
        children.add(at, child);
        child.parent = this;
        renumber(at);
    }

    /** Takes {@code child} out of this node; {@code leaving} when it is not about to be re-inserted in the document. */
    private void unlink(Node child, boolean leaving) {
        boolean connected = isConnected();
        Document doc = doc();
        if (leaving && connected) {
            doc.nodeRemoving(child); // may fire blur, whose listeners may already have moved the child
            if (child.parent != this) return;
        }
        int at = child.index;
        children.remove(at);
        child.parent = null;
        renumber(at);
        if (connected) doc.invalidate(true);
    }

    private void renumber(int from) {
        for (int i = from, n = children.size(); i < n; i++) children.get(i).index = i;
    }

    /** Moves this subtree to {@code doc} (a no-op within one document: a subtree always shares its owner). */
    void adopt(Document doc) {
        if (ownerDocument == doc) return;
        if (listeners != null) {
            listeners.forEach((type, list) -> {
                ownerDocument.countHandlers(type, -list.size());
                doc.countHandlers(type, list.size());
            });
        }
        adopted(ownerDocument, doc);
        ownerDocument = doc;
        for (Node c : children) c.adopt(doc);
    }

    /** Hook for subclasses moving per-document state when the node changes documents. */
    void adopted(Document from, Document to) {}

    // ---- Text ----

    /** Concatenated text of all descendant text nodes. */
    public String textContent() {
        StringBuilder sb = new StringBuilder();
        collectText(sb);
        return sb.toString();
    }

    void collectText(StringBuilder sb) {
        for (Node c : children) c.collectText(sb);
    }

    /** Replaces all children with a single text node (or nothing for an empty string). */
    public void setTextContent(String text) {
        removeAllChildren();
        if (text != null && !text.isEmpty()) appendChild(doc().createTextNode(text));
    }

    // ---- Cloning ----

    /**
     * A copy of this node (with copies of its descendants when {@code deep}), owned by the same document and not yet
     * inserted. Elements copy their attributes, as in the DOM; listeners are not copied.
     */
    public Node cloneNode(boolean deep) {
        Node copy = cloneShallow();
        if (deep) for (Node child : children) copy.appendChild(child.cloneNode(true));
        return copy;
    }

    abstract Node cloneShallow();

    // ---- Events ----

    private static final class Listener {
        final EventListener listener;
        final boolean capture, once;
        /** Set when removed, so a dispatch already iterating the old list skips it. */
        boolean removed;

        Listener(EventListener listener, boolean capture, boolean once) {
            this.listener = listener;
            this.capture = capture;
            this.once = once;
        }
    }

    public void addEventListener(String type, EventListener listener) {
        addEventListener(type, listener, false, false);
    }

    public void addEventListener(String type, EventListener listener, boolean capture, boolean once) {
        if (listener == null) return;
        if (listeners == null) listeners = new HashMap<>();
        List<Listener> list = listeners.getOrDefault(type, List.of());
        for (Listener l : list) if (l.listener == listener && l.capture == capture) return;
        List<Listener> added = new ArrayList<>(list.size() + 1);
        added.addAll(list);
        added.add(new Listener(listener, capture, once));
        listeners.put(type, added);
        doc().countHandlers(type, 1);
    }

    public void removeEventListener(String type, EventListener listener, boolean capture) {
        List<Listener> list = listeners == null ? null : listeners.get(type);
        if (list == null) return;
        for (Listener l : list) {
            if (l.listener == listener && l.capture == capture) {
                remove(type, l);
                return;
            }
        }
    }

    private void remove(String type, Listener l) {
        List<Listener> list = new ArrayList<>(listeners.get(type));
        list.remove(l);
        if (list.isEmpty()) listeners.remove(type);
        else listeners.put(type, list);
        l.removed = true;
        doc().countHandlers(type, -1);
    }

    /**
     * Dispatches an event with this node as the target. Returns false if a listener called preventDefault.
     * Listener exceptions are reported to the document's host and do not stop dispatch. When nothing in the document
     * handles the event's type (no listener, no inline handler), no path is walked at all.
     */
    public boolean dispatchEvent(Event event) {
        Event.Access.begin(event, this);
        try {
            Document doc = doc();
            if (doc.handles(event.type)) propagate(event, doc.scripts() != null ? inlineHandlerName(event.type) : null);
        } finally {
            Event.Access.end(event);
        }
        return !event.defaultPrevented();
    }

    /** The attribute holding inline handlers for {@code type}, as {@code getAttribute} would look it up. */
    private static String inlineHandlerName(String type) {
        return ("on" + type).toLowerCase(Locale.ROOT);
    }

    private void propagate(Event event, String inlineHandler) {
        List<Node> path = new ArrayList<>();
        for (Node n = this; n != null; n = n.parent) path.add(n);
        for (int i = path.size() - 1; i >= 1 && !event.propagationStopped(); i--) {
            Event.Access.at(event, path.get(i), Event.Phase.CAPTURING);
            path.get(i).invoke(event, true);
        }
        if (!event.propagationStopped()) {
            Event.Access.at(event, this, Event.Phase.AT_TARGET);
            invoke(event, true);
            if (!event.immediatePropagationStopped()) invoke(event, false);
            invokeInlineHandler(event, inlineHandler);
        }
        if (event.bubbles) {
            for (int i = 1; i < path.size() && !event.propagationStopped(); i++) {
                Event.Access.at(event, path.get(i), Event.Phase.BUBBLING);
                path.get(i).invoke(event, false);
                path.get(i).invokeInlineHandler(event, inlineHandler);
            }
        }
    }

    private void invoke(Event event, boolean capturePhase) {
        List<Listener> list = listeners == null ? null : listeners.get(event.type);
        if (list == null) return;
        for (Listener l : list) {
            if (event.immediatePropagationStopped()) return;
            if (l.capture != capturePhase || l.removed) continue;
            if (l.once) remove(event.type, l);
            try {
                l.listener.handleEvent(event);
            } catch (RuntimeException ex) {
                doc().reportError("Error in '" + event.type + "' listener", ex);
            }
        }
    }

    private void invokeInlineHandler(Event event, String attribute) {
        if (attribute == null || !(this instanceof Element el) || event.immediatePropagationStopped()) return;
        String code = el.attribute(attribute);
        if (code == null) return;
        try {
            ownerDocument.scripts().runInlineHandler(el, code, event);
        } catch (RuntimeException ex) {
            ownerDocument.reportError("Error in " + attribute + " handler", ex);
        }
    }
}
