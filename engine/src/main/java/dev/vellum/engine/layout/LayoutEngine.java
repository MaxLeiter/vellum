package dev.vellum.engine.layout;

import dev.vellum.engine.dom.Document;

/**
 * Builds the box tree from computed styles and lays it out: block, inline, flex, grid, absolute and fixed
 * positioning, scroll containers. Sets {@code element.box} on every rendered element (null otherwise).
 * STUB: implemented by the layout workstream.
 */
public final class LayoutEngine {
    private final Document document;

    public LayoutEngine(Document document) {
        this.document = document;
    }

    /** The root box (for the html element), or null before the first layout. */
    public Box root() {
        return null;
    }

    /** Lays out the whole document against the current viewport. */
    public void layout() {
        throw new UnsupportedOperationException("TODO");
    }
}
