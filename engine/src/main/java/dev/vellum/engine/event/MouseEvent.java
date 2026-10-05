package dev.vellum.engine.event;

import dev.vellum.engine.dom.Node;

/**
 * Mouse events: mousedown, mouseup, click, dblclick, contextmenu, mousemove, mouseover, mouseout, mouseenter,
 * mouseleave. Coordinates are document (viewport) GUI pixels. Buttons use DOM numbering: 0 left, 1 middle, 2 right.
 */
public class MouseEvent extends Event {
    public final float clientX, clientY;
    public final int button;
    /** Bit mask of pressed buttons: 1 left, 2 right, 4 middle. */
    public final int buttons;
    public final Modifiers modifiers;
    /** Click count for click / dblclick. */
    public final int detail;
    public final Node relatedTarget;
    /** Position relative to the target's padding box; filled in by the dispatcher. */
    public float offsetX, offsetY;

    public MouseEvent(String type, boolean bubbles, boolean cancelable, float clientX, float clientY, int button,
                      int buttons, Modifiers modifiers, int detail, Node relatedTarget) {
        super(type, bubbles, cancelable);
        this.clientX = clientX;
        this.clientY = clientY;
        this.button = button;
        this.buttons = buttons;
        this.modifiers = modifiers;
        this.detail = detail;
        this.relatedTarget = relatedTarget;
    }
}
