package dev.vellum.engine.event;

/** An application event carrying arbitrary detail (from scripts, or from the host such as server data updates). */
public class CustomEvent extends Event {
    public final Object detail;

    public CustomEvent(String type, boolean bubbles, boolean cancelable, Object detail) {
        super(type, bubbles, cancelable);
        this.detail = detail;
    }
}
