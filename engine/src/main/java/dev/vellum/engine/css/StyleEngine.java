package dev.vellum.engine.css;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.style.ComputedStyle;

import java.util.List;

/**
 * The cascade. Finds the document's stylesheets (the user-agent sheet, {@code <style>} elements and
 * {@code <link rel="stylesheet">}), matches rules, and computes {@code baseStyle} (plus ::before/::after styles)
 * for every element. STUB: implemented by the CSS workstream.
 */
public final class StyleEngine {
    private final Document document;

    public StyleEngine(Document document) {
        this.document = document;
    }

    /**
     * Recomputes styles for the whole document. For each element: sets {@code baseStyle}, calls
     * {@code document.animations().styleChanged(element, oldBase, newBase)} which sets {@code element.style},
     * and invalidates layout when a layout-affecting property changed.
     */
    public void restyle() {
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * The keyframes of the {@code @keyframes} rule named {@code name}, computed for {@code element} on top of
     * {@code base}, sorted by offset. Missing 0% / 100% keyframes are NOT synthesised (the animation engine uses the
     * base value there). Returns an empty list when no such rule exists.
     */
    public List<ResolvedKeyframe> resolveKeyframes(Element element, String name, ComputedStyle base) {
        return List.of();
    }

    /**
     * Computes the style that results from applying CSS declarations ({@code "opacity: 0; transform: scale(2)"})
     * to {@code element} on top of {@code base}: used by {@code element.animate()} keyframes from scripts.
     * Returns the new style and the set of properties the declarations set, as a keyframe at offset 0.
     */
    /**
     * The computed value of {@code property} (CSS name, longhand or common shorthand) serialised as CSS text, for
     * {@code getComputedStyle}. Returns "" for unknown properties.
     */
    public static String computedValue(ComputedStyle style, String property) {
        return "";
    }

    public ResolvedKeyframe computeDeclarations(Element element, String declarations, ComputedStyle base) {
        return new ResolvedKeyframe(0, null, base, java.util.Set.of());
    }
}
