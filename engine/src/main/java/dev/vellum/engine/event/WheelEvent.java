package dev.vellum.engine.event;

/** A wheel event. Deltas are in GUI pixels, positive down/right (DOM convention). */
public class WheelEvent extends MouseEvent {
    public final float deltaX, deltaY;

    public WheelEvent(float clientX, float clientY, int buttons, Modifiers modifiers, float deltaX, float deltaY) {
        super("wheel", true, true, clientX, clientY, 0, buttons, modifiers, 0, null);
        this.deltaX = deltaX;
        this.deltaY = deltaY;
    }
}
