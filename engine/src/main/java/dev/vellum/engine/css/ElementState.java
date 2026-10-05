package dev.vellum.engine.css;

import dev.vellum.engine.css.RuleIndex.Entry;
import dev.vellum.engine.style.ComputedStyle;

import java.util.List;

/**
 * The style engine's per-element state, kept in {@code Element.parsedInlineStyle} (which the element clears when its
 * {@code style} attribute changes): the parsed inline declarations, and the inputs of the last computation. When the
 * inputs are unchanged the previous styles are reused as they are, which keeps a hover restyle down to selector
 * matching for most elements.
 */
final class ElementState {
    private static final Entry[] NONE = new Entry[0];

    /** The {@code style} attribute these declarations were parsed from. */
    final String inlineText;
    /** Inline declarations, or null without a style attribute. */
    final List<Decl> inline;

    private Entry[] matched = NONE;
    private ComputedStyle parent, container;
    private int generation = -1;
    private boolean readsAttributes;

    ElementState(String inlineText, List<Decl> inline) {
        this.inlineText = inlineText;
        this.inline = inline;
    }

    /**
     * Whether a computation from these inputs would give the same result as last time: same matched rules, same
     * parent and container styles (by identity: unchanged styles are reused), and nothing global changed
     * ({@code generation}: stylesheets, media, viewport, root font size).
     */
    boolean unchanged(List<Entry> matched, ComputedStyle parent, ComputedStyle container, int generation) {
        if (readsAttributes || generation != this.generation || parent != this.parent || container != this.container
                || matched.size() != this.matched.length) return false;
        for (int i = 0; i < this.matched.length; i++) if (matched.get(i) != this.matched[i]) return false;
        return true;
    }

    void remember(List<Entry> matched, ComputedStyle parent, ComputedStyle container, int generation,
                  boolean readsAttributes) {
        this.matched = matched.toArray(NONE);
        this.parent = parent;
        this.container = container;
        this.generation = generation;
        this.readsAttributes = readsAttributes;
    }
}
