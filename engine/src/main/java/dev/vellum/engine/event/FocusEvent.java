package dev.vellum.engine.event;

import dev.vellum.engine.dom.Node;

/** focus / blur (do not bubble) and focusin / focusout (bubble). */
public class FocusEvent extends Event {
    public final Node relatedTarget;

    public FocusEvent(String type, Node relatedTarget) {
        super(type, type.equals("focusin") || type.equals("focusout"), false);
        this.relatedTarget = relatedTarget;
    }
}
