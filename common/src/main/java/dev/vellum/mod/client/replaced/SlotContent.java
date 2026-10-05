package dev.vellum.mod.client.replaced;

import dev.vellum.engine.dom.Element;
import dev.vellum.mod.client.render.McCanvas;

/**
 * {@code <slot index="n">}: reserves an 18×18 place for slot {@code n} of the open container menu. The slot's look
 * comes from CSS. Painting reports where the slot is drawn ({@link McCanvas#placeSlot}), after scrolling,
 * transforms, clipping and visibility, and the container screen moves the menu slot there for that frame; vanilla
 * draws its item, highlight and tooltip and handles its clicks. Outside container screens it is an empty box.
 */
final class SlotContent extends McReplaced {
    static final float SIZE = 18;

    private int index;

    SlotContent(Element element) {
        super(element);
        index = index();
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
    public void attributeChanged(String name) {
        if (name.equals("index")) index = index();
    }

    @Override
    protected void draw(McCanvas canvas, float x, float y, float width, float height) {
        if (index >= 0) canvas.placeSlot(index, x, y, width, height);
    }

    /** The menu slot index, or -1 when the attribute is missing or not a number. */
    private int index() {
        return (int) element.numberAttribute("index", -1);
    }
}
