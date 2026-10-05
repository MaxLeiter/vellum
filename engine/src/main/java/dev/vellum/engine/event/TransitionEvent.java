package dev.vellum.engine.event;

/** transitionstart / transitionend / transitioncancel, and animationstart / animationiteration / animationend. */
public class TransitionEvent extends Event {
    /** The property name (transitions) or the animation name (animations). */
    public final String name;
    /** {@code "::before"} or {@code "::after"} when a pseudo-element of the target runs it, else {@code ""}. */
    public final String pseudoElement;
    public final float elapsedSeconds;

    public TransitionEvent(String type, String name, String pseudoElement, float elapsedSeconds) {
        super(type, true, false);
        this.name = name;
        this.pseudoElement = pseudoElement;
        this.elapsedSeconds = elapsedSeconds;
    }
}
