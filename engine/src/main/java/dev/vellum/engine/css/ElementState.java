package dev.vellum.engine.css;

import dev.vellum.engine.css.RuleIndex.Entry;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.style.ComputedStyle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The style engine's per-element state, kept in {@code Element.parsedInlineStyle}: the parsed inline style, and the
 * inputs of the last computation. When the inputs are unchanged the previous styles are reused as they are, which
 * keeps a hover restyle down to selector matching (or less) for most elements.
 */
final class ElementState {
    /** The {@code style} attribute the inline declarations were parsed from. */
    final String inlineText;
    /** The inline declarations as written, for {@code element.style} (read-only). */
    final List<InlineStyle.Entry> inlineEntries;
    /** The inline declarations expanded to longhands, or null without any. */
    final List<Decl> inline;

    /** The rules that matched last time; null before the first match. */
    private Entry[] matched;
    /** Whether that matching read hover, active or focus state. */
    private boolean matchReadInteraction;
    private ComputedStyle parent, container;
    private int generation = -1;
    private boolean readsAttributes, inheritsExplicitly;

    ElementState(String inlineText, List<InlineStyle.Entry> inlineEntries) {
        this.inlineText = inlineText;
        this.inlineEntries = List.copyOf(inlineEntries);
        List<Decl> decls = new ArrayList<>();
        for (InlineStyle.Entry e : inlineEntries) decls.addAll(e.decls());
        this.inline = decls.isEmpty() ? null : decls;
    }

    /** The element's state; its style attribute is parsed again when it changed since the state was made. */
    static ElementState of(Element element) {
        String style = element.getAttribute("style");
        if (element.parsedInlineStyle instanceof ElementState s && Objects.equals(s.inlineText, style)) return s;
        ElementState s = new ElementState(style, style == null ? List.of() : InlineStyle.parse(element, style));
        element.parsedInlineStyle = s;
        return s;
    }

    // ---- Matching ----

    /** Whether the last matches still hold when only hover, active and focus state changed since. */
    boolean matchesSurviveInteraction() {
        return matched != null && !matchReadInteraction;
    }

    /** Records this pass's matches; returns whether they are the rules that matched last time. */
    boolean updateMatches(List<Entry> now, boolean readInteraction) {
        matchReadInteraction = readInteraction;
        if (matched != null && matched.length == now.size()) {
            int i = 0;
            while (i < matched.length && matched[i] == now.get(i)) i++;
            if (i == matched.length) return true;
        }
        matched = now.toArray(Entry[]::new);
        return false;
    }

    /** Appends the last matches to {@code out}. */
    void matchesInto(List<Entry> out) {
        Collections.addAll(out, matched);
    }

    // ---- Computation inputs ----

    /**
     * Whether, with the same matched rules, a computation from these inputs would give the same result as last time.
     * The parent counts as unchanged when its inherited properties are (unless this element inherits a
     * non-inherited property explicitly), and the container when its display is: that is all the cascade reads of
     * them. Nothing global may have changed ({@code generation}: stylesheets, media, viewport, root font size), and
     * styles reading attributes ({@code attr()}) are always recomputed.
     */
    boolean sameInputs(ComputedStyle parent, ComputedStyle container, int generation) {
        if (readsAttributes || generation != this.generation) return false;
        if (container != this.container) {
            if (container == null || this.container == null || container.display != this.container.display) return false;
        }
        if (parent != this.parent) {
            if (inheritsExplicitly || parent == null || this.parent == null || !parent.sameInherited(this.parent)) {
                return false;
            }
        }
        this.parent = parent; // equivalent: later passes compare identities again
        this.container = container;
        return true;
    }

    void remember(ComputedStyle parent, ComputedStyle container, int generation, boolean readsAttributes,
                  boolean inheritsExplicitly) {
        this.parent = parent;
        this.container = container;
        this.generation = generation;
        this.readsAttributes = readsAttributes;
        this.inheritsExplicitly = inheritsExplicitly;
    }
}
