package dev.vellum.engine.event;

/** A listener added with {@link dev.vellum.engine.dom.Node#addEventListener}. */
@FunctionalInterface
public interface EventListener {
    void handleEvent(Event event);
}
