package dev.vellum.engine.dom;

/** A lightweight container whose children move into the target on insertion. */
public final class DocumentFragment extends Node {
    DocumentFragment(Document ownerDocument) {
        super(ownerDocument);
    }

    @Override
    public String nodeName() { return "#document-fragment"; }

    @Override
    Node cloneShallow() {
        return new DocumentFragment(ownerDocument);
    }
}
