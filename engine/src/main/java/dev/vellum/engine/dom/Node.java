package dev.vellum.engine.dom;

import dev.vellum.engine.event.Event;
import dev.vellum.engine.event.EventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A DOM node. The tree API follows the DOM (appendChild, insertBefore, removeChild...), and every mutation marks the
 * owning {@link Document} dirty so the next frame restyles and relayouts.
 */
public abstract class Node {
    Document ownerDocument;
    Node parent;
    final List<Node> children = new ArrayList<>(0);
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

    public List<Node> childNodes() { return Collections.unmodifiableList(children); }
    public int childCount() { return children.size(); }
    public Node childAt(int i) { return children.get(i); }
    public Node firstChild() { return children.isEmpty() ? null : children.get(0); }
    public Node lastChild() { return children.isEmpty() ? null : children.get(children.size() - 1); }

    public Node nextSibling() {
        if (parent == null) return null;
        int i = parent.children.indexOf(this);
        return i + 1 < parent.children.size() ? parent.children.get(i + 1) : null;
    }

    public Node previousSibling() {
        if (parent == null) return null;
        int i = parent.children.indexOf(this);
        return i > 0 ? parent.children.get(i - 1) : null;
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

    // ---- Mutation ----

    public <T extends Node> T appendChild(T child) {
        return insertBefore(child, null);
    }

    public <T extends Node> T insertBefore(T child, Node reference) {
        if (child instanceof Document) throw new IllegalArgumentException("Cannot insert a document");
        if (child.contains(this)) throw new IllegalArgumentException("Cannot insert a node into its own subtree");
        if (child instanceof DocumentFragment frag) {
            for (Node n : new ArrayList<>(frag.children)) insertBefore(n, reference);
            return child;
        }
        if (child.parent != null) child.parent.removeChild(child);
        int index = reference == null ? children.size() : children.indexOf(reference);
        if (index < 0) throw new IllegalArgumentException("Reference node is not a child of this node");
        children.add(index, child);
        child.parent = this;
        child.adopt(ownerDocument != null ? ownerDocument : (Document) this);
        treeChanged(child, true);
        return child;
    }

    public <T extends Node> T removeChild(T child) {
        if (child.parent != this) throw new IllegalArgumentException("Not a child of this node");
        Document doc = ownerDocument;
        if (doc != null) doc.nodeRemoving(child);
        children.remove(child);
        child.parent = null;
        treeChanged(child, false);
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

    void adopt(Document doc) {
        ownerDocument = doc;
        for (Node c : children) c.adopt(doc);
    }

    void treeChanged(Node child, boolean added) {
        Document doc = ownerDocument != null ? ownerDocument : (this instanceof Document d ? d : null);
        if (doc != null) doc.treeMutated(this, child, added);
    }

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
        if (text != null && !text.isEmpty()) appendChild(ownerDocument.createTextNode(text));
    }

    // ---- Events ----

    private record Listener(EventListener listener, boolean capture, boolean once) {}

    public void addEventListener(String type, EventListener listener) {
        addEventListener(type, listener, false, false);
    }

    public void addEventListener(String type, EventListener listener, boolean capture, boolean once) {
        if (listener == null) return;
        if (listeners == null) listeners = new HashMap<>();
        List<Listener> list = listeners.computeIfAbsent(type, k -> new ArrayList<>(2));
        for (Listener l : list) if (l.listener == listener && l.capture == capture) return;
        list.add(new Listener(listener, capture, once));
    }

    public void removeEventListener(String type, EventListener listener, boolean capture) {
        if (listeners == null) return;
        List<Listener> list = listeners.get(type);
        if (list != null) list.removeIf(l -> l.listener == listener && l.capture == capture);
    }

    public boolean hasListeners(String type) {
        if (listeners != null) {
            List<Listener> list = listeners.get(type);
            if (list != null && !list.isEmpty()) return true;
        }
        return this instanceof Element e && e.hasAttribute("on" + type);
    }

    /**
     * Dispatches an event with this node as the target. Returns false if a listener called preventDefault.
     * Listener exceptions are reported to the document's host and do not stop dispatch.
     */
    public boolean dispatchEvent(Event event) {
        List<Node> path = new ArrayList<>();
        for (Node n = this; n != null; n = n.parent) path.add(n);
        Event.Access.begin(event, this);
        try {
            for (int i = path.size() - 1; i >= 1 && !event.propagationStopped(); i--) {
                Event.Access.at(event, path.get(i), Event.Phase.CAPTURING);
                path.get(i).invoke(event, true);
            }
            if (!event.propagationStopped()) {
                Event.Access.at(event, this, Event.Phase.AT_TARGET);
                invoke(event, true);
                if (!event.immediatePropagationStopped()) invoke(event, false);
                invokeInlineHandler(event);
            }
            if (event.bubbles) {
                for (int i = 1; i < path.size() && !event.propagationStopped(); i++) {
                    Event.Access.at(event, path.get(i), Event.Phase.BUBBLING);
                    path.get(i).invoke(event, false);
                    path.get(i).invokeInlineHandler(event);
                }
            }
        } finally {
            Event.Access.end(event);
        }
        return !event.defaultPrevented();
    }

    private void invoke(Event event, boolean capturePhase) {
        if (listeners == null) return;
        List<Listener> list = listeners.get(event.type);
        if (list == null || list.isEmpty()) return;
        for (Listener l : new ArrayList<>(list)) {
            if (event.immediatePropagationStopped()) return;
            if (l.capture != capturePhase) continue;
            if (l.once) list.remove(l);
            try {
                l.listener.handleEvent(event);
            } catch (RuntimeException ex) {
                Document doc = ownerDocument != null ? ownerDocument : (this instanceof Document d ? d : null);
                if (doc != null) doc.reportError("Error in '" + event.type + "' listener", ex);
                else throw ex;
            }
        }
    }

    private void invokeInlineHandler(Event event) {
        if (!(this instanceof Element el) || event.immediatePropagationStopped()) return;
        String code = el.getAttribute("on" + event.type);
        if (code == null || ownerDocument == null || ownerDocument.scripts() == null) return;
        try {
            ownerDocument.scripts().runInlineHandler(el, code, event);
        } catch (RuntimeException ex) {
            ownerDocument.reportError("Error in on" + event.type + " handler", ex);
        }
    }
}
