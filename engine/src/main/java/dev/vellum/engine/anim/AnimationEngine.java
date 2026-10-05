package dev.vellum.engine.anim;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.style.ComputedStyle;

/**
 * CSS transitions and keyframe animations, plus the Web Animations-style {@code element.animate()} used by scripts.
 * STUB: implemented by the animation workstream.
 */
public final class AnimationEngine {
    private final Document document;

    public AnimationEngine(Document document) {
        this.document = document;
    }

    /**
     * Called by the style engine after computing an element's base style. Starts, retargets or cancels
     * transitions (comparing {@code oldBase} with {@code newBase} for properties listed in {@code transition}),
     * starts or stops keyframe animations when {@code animation-name} changes, and sets {@code element.style}.
     * {@code oldBase} is null the first time an element is styled (no transitions then).
     */
    public void styleChanged(Element element, ComputedStyle oldBase, ComputedStyle newBase) {
        element.style = newBase;
    }

    /**
     * Advances running transitions and animations to {@code nowMs}, recomputing {@code element.style} for animated
     * elements, firing transition/animation events, and invalidating layout when a layout-affecting property moved.
     */
    public void tick(double nowMs) {
    }

    /** True while anything is running (hosts may use it to keep rendering). */
    public boolean isAnimating() {
        return false;
    }
}
