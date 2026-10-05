package dev.vellum.engine.event;

/** transitionstart / transitionend / transitioncancel, and animationstart / animationiteration / animationend. */
public class TransitionEvent extends Event {
    /** The property name (transitions) or the animation name (animations). */
    public final String name;
    public final float elapsedSeconds;

    public TransitionEvent(String type, String name, float elapsedSeconds) {
        super(type, true, false);
        this.name = name;
        this.elapsedSeconds = elapsedSeconds;
    }
}
