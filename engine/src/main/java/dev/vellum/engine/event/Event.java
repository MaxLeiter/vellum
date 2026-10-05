package dev.vellum.engine.event;

import dev.vellum.engine.dom.Node;

/** A DOM event. Dispatch follows the DOM: capture from the document down, the target, then bubbling back up. */
public class Event {
    public enum Phase { NONE, CAPTURING, AT_TARGET, BUBBLING }

    public final String type;
    public final boolean bubbles;
    public final boolean cancelable;
    public final double timeStamp;
    Node target;
    Node currentTarget;
    Phase phase = Phase.NONE;
    boolean defaultPrevented;
    boolean propagationStopped;
    boolean immediatePropagationStopped;
    boolean dispatching;

    /** Slot for the script runtime's wrapper object, so every listener of a dispatch sees the same script object. */
    public Object scriptWrapper;

    public Event(String type, boolean bubbles, boolean cancelable) {
        this.type = type;
        this.bubbles = bubbles;
        this.cancelable = cancelable;
        this.timeStamp = System.nanoTime() / 1_000_000.0;
    }

    public Event(String type) {
        this(type, true, true);
    }

    public Node target() { return target; }
    public Node currentTarget() { return currentTarget; }
    public Phase phase() { return phase; }
    public boolean defaultPrevented() { return defaultPrevented; }
    public boolean propagationStopped() { return propagationStopped; }
    public boolean immediatePropagationStopped() { return immediatePropagationStopped; }

    public void preventDefault() {
        if (cancelable) defaultPrevented = true;
    }

    public void stopPropagation() {
        propagationStopped = true;
    }

    public void stopImmediatePropagation() {
        propagationStopped = true;
        immediatePropagationStopped = true;
    }

    /** Used by {@link dev.vellum.engine.dom.Node#dispatchEvent} only. */
    public static final class Access {
        private Access() {}

        public static void begin(Event e, Node target) {
            e.target = target;
            e.dispatching = true;
            e.propagationStopped = false;
            e.immediatePropagationStopped = false;
        }

        public static void at(Event e, Node current, Phase phase) {
            e.currentTarget = current;
            e.phase = phase;
        }

        public static void end(Event e) {
            e.currentTarget = null;
            e.phase = Phase.NONE;
            e.dispatching = false;
        }
    }
}
