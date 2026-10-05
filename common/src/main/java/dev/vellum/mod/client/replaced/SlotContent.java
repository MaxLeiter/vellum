package dev.vellum.mod.client.replaced;

import dev.vellum.engine.dom.Element;
import dev.vellum.mod.client.render.McCanvas;

/**
 * {@code <slot index="n">}: reserves an 18×18 place for slot {@code n} of the open container menu. The slot's look
 * comes from CSS; {@code VellumContainerScreen} moves the menu slot to this box after each layout, and vanilla draws
 * its item, highlight and tooltip and handles its clicks. Outside container screens it is an empty box.
 */
public final class SlotContent extends McReplaced {
    public static final float SIZE = 18;

    SlotContent(Element element) {
        super(element);
    }

    /** The menu slot index, or -1 when the attribute is missing or not a number. */
    public int index() {
        return (int) number("index", -1);
    }

    @Override
    public float intrinsicWidth() {
        return SIZE;
    }

    @Override
    public float intrinsicHeight() {
        return SIZE;
    }

    @Override
    public void draw(McCanvas canvas, Element element, float x, float y, float width, float height) {
        // Vanilla draws the slot's contents in the container screen's slot pass.
    }
}
