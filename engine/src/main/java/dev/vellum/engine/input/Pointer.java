package dev.vellum.engine.input;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Node;
import dev.vellum.engine.event.Modifiers;
import dev.vellum.engine.event.MouseEvent;
import dev.vellum.engine.layout.Box;
import dev.vellum.engine.paint.Affine;
import dev.vellum.engine.paint.Coordinates;
import dev.vellum.engine.style.Cursor;

import java.util.List;

/**
 * The pointer's DOM state: position, pressed buttons, the hover chain (with mouseover/out/enter/leave), the
 * {@code :active} chain, click counting and the click target rule, pointer capture, and the cursor. Every mouse
 * event is dispatched through {@link #dispatch} so all of them carry the same position, buttons and offsets.
 */
final class Pointer {
    private static final double MULTI_CLICK_MS = 400;
    private static final float MULTI_CLICK_SLOP = 4;

    private final Document document;
    float x = Float.NaN, y = Float.NaN;
    private Modifiers mods = Modifiers.NONE;
    /** Pressed buttons as a DOM {@code buttons} mask. */
    private int buttons;
    private Element hoverTarget;
    private List<Element> hoverChain = List.of(), activeChain = List.of();
    private Element pressed;
    private int clicks, lastButton = -1;
    private double lastPressTime;
    private float lastPressX, lastPressY;
    private Element captured;
    private Drag drag;
    private Cursor cursor;
    private final Affine toBox = new Affine();

    Pointer(Document document) {
        this.document = document;
    }

    void moveTo(float x, float y, Modifiers mods) {
        this.x = x;
        this.y = y;
        this.mods = mods;
    }

    Modifiers mods() { return mods; }
    int buttons() { return buttons; }
    int clicks() { return clicks; }
    boolean known() { return !Float.isNaN(x); }
    Element hoverTarget() { return hoverTarget; }

    /**
     * The pointer left the document (the host stopped tracking it): its position becomes unknown, buttons are up and
     * nothing is hovered or active. The caller ends any drag first.
     */
    void leave() {
        x = y = Float.NaN;
        buttons = 0;
        pressed = null;
        setActive(List.of());
        hover(null);
        updateCursor(null, false);
    }

    // ---- Hover ----

    /** Moves the hover target, updating {@code :hover} and firing mouseout, mouseleave, mouseover, mouseenter. */
    void hover(Element target) {
        if (target == hoverTarget) return;
        Element old = hoverTarget;
        List<Element> oldChain = hoverChain, newChain = Dom.chain(target);
        hoverTarget = target;
        hoverChain = newChain;
        for (Element e : oldChain) if (!newChain.contains(e)) document.setHovered(e, false);
        for (Element e : newChain) document.setHovered(e, true);
        if (old != null) fire("mouseout", old, 0, 0, target);
        for (Element e : oldChain) if (!newChain.contains(e)) fire("mouseleave", e, 0, 0, target);
        if (target != null) fire("mouseover", target, 0, 0, old);
        for (Element e : newChain.reversed()) if (!oldChain.contains(e)) fire("mouseenter", e, 0, 0, old);
    }

    /** Sets the host cursor from the hovered element's {@code cursor} (auto: an I-beam over text fields), on change. */
    void updateCursor(Element target, boolean overScrollbar) {
        Cursor c = Cursor.DEFAULT;
        if (target != null && !overScrollbar) {
            c = Forms.style(target).cursor;
            if (c == Cursor.AUTO) c = target.isTextControl() && !target.isDisabled() ? Cursor.TEXT : Cursor.DEFAULT;
        }
        if (c != cursor) {
            cursor = c;
            document.host().setCursor(c);
        }
    }

    // ---- Buttons ----

    /** Mousedown bookkeeping: pressed buttons, the click count, and {@code :active} for the primary button. */
    int press(Element target, int button) {
        double now = document.scheduler().now();
        boolean repeat = button == lastButton && now - lastPressTime <= MULTI_CLICK_MS
                && Math.abs(x - lastPressX) <= MULTI_CLICK_SLOP && Math.abs(y - lastPressY) <= MULTI_CLICK_SLOP;
        clicks = repeat ? clicks + 1 : 1;
        lastButton = button;
        lastPressTime = now;
        lastPressX = x;
        lastPressY = y;
        buttons |= mask(button);
        if (button == 0) {
            pressed = target;
            setActive(Dom.chain(target));
        }
        return clicks;
    }

    /**
     * Mouseup bookkeeping. Returns the click target: for the primary button, the nearest common ancestor of the
     * pressed and released elements; otherwise null.
     */
    Element release(Element target, int button) {
        buttons &= ~mask(button);
        if (button != 0) return null;
        Element down = pressed;
        pressed = null;
        setActive(List.of());
        if (down == null || target == null) return null;
        return Dom.closest(down, e -> e.contains(target));
    }

    private void setActive(List<Element> chain) {
        for (Element e : activeChain) if (!chain.contains(e)) document.setActive(e, false);
        for (Element e : chain) document.setActive(e, true);
        activeChain = chain;
    }

    private static int mask(int button) {
        return switch (button) {
            case 0 -> 1;
            case 1 -> 4;
            case 2 -> 2;
            default -> 0;
        };
    }

    // ---- Capture ----

    /** Routes the pointer to {@code element} and feeds {@code drag} until release. */
    void capture(Element element, Drag drag) {
        this.captured = element;
        this.drag = drag;
    }

    Element captured() { return captured; }
    Drag drag() { return drag; }

    /** Ends the capture, returning the drag that was running (or null). */
    Drag releaseCapture() {
        Drag d = drag;
        drag = null;
        captured = null;
        return d;
    }

    // ---- Events ----

    /** Creates and dispatches a mouse event at the pointer. Enter and leave neither bubble nor cancel. */
    MouseEvent fire(String type, Element target, int button, int detail, Element related) {
        boolean boundary = type.equals("mouseenter") || type.equals("mouseleave");
        return dispatch(new MouseEvent(type, !boundary, !boundary, x, y, button, buttons, mods, detail, related), target);
    }

    /** Fills in offsetX/Y (relative to the target's padding box, as painted) and dispatches. */
    <E extends MouseEvent> E dispatch(E event, Element target) {
        Box box = target.box;
        if (box != null && Coordinates.fromViewport(box, toBox)) {
            event.offsetX = toBox.mapX(event.clientX, event.clientY) - box.borderLeft;
            event.offsetY = toBox.mapY(event.clientX, event.clientY) - box.borderTop;
        }
        target.dispatchEvent(event);
        return event;
    }

    /** Drops references into a subtree leaving the document; hover and :active fall back to the nearest survivor. */
    void forget(Node removed) {
        if (hoverTarget != null && removed.contains(hoverTarget)) {
            hoverChain = prune(hoverChain, removed, true);
            hoverTarget = hoverChain.isEmpty() ? null : hoverChain.getFirst();
        }
        if (pressed != null && removed.contains(pressed)) {
            activeChain = prune(activeChain, removed, false);
            pressed = activeChain.isEmpty() ? null : activeChain.getFirst();
        }
        if (captured != null && removed.contains(captured)) releaseCapture();
    }

    /** The part of a hover or active chain outside {@code removed}; the removed part loses its flag. */
    private List<Element> prune(List<Element> chain, Node removed, boolean hover) {
        for (Element e : chain) {
            if (!removed.contains(e)) continue;
            if (hover) document.setHovered(e, false);
            else document.setActive(e, false);
        }
        return chain.stream().filter(e -> !removed.contains(e)).toList();
    }
}
