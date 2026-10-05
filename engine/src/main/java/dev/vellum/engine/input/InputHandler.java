package dev.vellum.engine.input;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Node;
import dev.vellum.engine.event.Event;
import dev.vellum.engine.event.KeyboardEvent;
import dev.vellum.engine.event.Modifiers;
import dev.vellum.engine.event.MouseEvent;
import dev.vellum.engine.event.WheelEvent;
import dev.vellum.engine.paint.Canvas;
import dev.vellum.engine.paint.HitResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns host input into DOM events and default actions: hover/active/focus state, click synthesis, wheel and
 * scrollbar scrolling (smooth), keyboard focus navigation, form controls, text editing, and the cursor.
 *
 * <p>The work is split into focused collaborators: {@link Pointer} (hover, active, clicks, capture, cursor),
 * {@link FocusNavigator} (tab order, focus-visible, autofocus), {@link Scroller} (wheel, smooth scrolling,
 * scrollbars), {@link TextField} / {@link RangeControl} / {@link SelectPopup} / {@link Turntable} (per-control
 * behaviour) and
 * {@link Activation} (click default actions), {@link Tooltips} ({@code title} tooltips). This class routes input
 * between them.
 *
 * <p>The host methods (pointer, wheel and keys) run inside the document's error boundary
 * ({@link Document#guard}): they return false once the document has stopped.
 */
public final class InputHandler {
    /** Wheel distance of one notch in GUI px (about three lines of 8 px text); hosts scale wheel notches by it. */
    public static final float WHEEL_NOTCH = 24;

    private final Document document;
    private final Pointer pointer;
    private final Scroller scroller;
    private final FocusNavigator focus;
    private final Tooltips tooltips = new Tooltips();
    private SelectPopup popup;
    /** Set when a keydown for a character was cancelled, so its charTyped is dropped (as browsers skip the input). */
    private boolean suppressChar;
    /** Keys held down, so a keydown without a keyup in between is reported as auto-repeat. */
    private final Set<String> keysDown = new HashSet<>();
    /** Turntables that are held or still spinning: frames keep coming until they stop. */
    private final List<Turntable> turntables = new ArrayList<>();

    public InputHandler(Document document) {
        this.document = document;
        this.pointer = new Pointer(document);
        this.scroller = new Scroller();
        this.focus = new FocusNavigator(document);
    }

    // ---- Pointer ----

    /** Mouse moved to (x, y) in viewport px. Returns true if the document is under the pointer. */
    public boolean mouseMove(float x, float y, Modifiers mods) {
        return document.guard(() -> {
            pointer.moveTo(x, y, mods);
            if (pointer.drag() != null) pointer.drag().move(x, y);
            if (popup != null) popup.hover(x, y);
            Element target = trackHover();
            if (target != null) pointer.fire("mousemove", target, 0, 0, null);
            return target != null;
        });
    }

    /**
     * The pointer left the document: the host stopped giving it the pointer (a HUD overlay whose screen closed). Hover
     * ends (with mouseout and mouseleave), a drag in progress ends, and no tooltip shows until the pointer is back.
     */
    public void mouseLeave() {
        document.guard(() -> {
            Drag drag = pointer.releaseCapture();
            if (drag != null) drag.end();
            pointer.leave();
            tooltips.track(null, document.scheduler().now());
            return true;
        });
    }

    /**
     * A mouse button went down. {@code button}: 0 left, 1 middle, 2 right. Returns true if the document is under the
     * pointer (or an open dropdown took the press).
     */
    public boolean mouseDown(float x, float y, int button, Modifiers mods) {
        return document.guard(() -> {
            pointer.moveTo(x, y, mods);
            tooltips.dismiss();
            if (popup != null) {
                // Like a native dropdown, an open list swallows presses; one outside it closes it.
                if (!popup.contains(x, y)) popup = null;
                return true;
            }
            HitResult hit = hitTest(x, y);
            Element target = target(hit, x, y);
            if (target == null) return false;
            if (button == 0 && hit != null && hit.scrollbar() != null) {
                pointer.capture(target, scroller.press(hit));
                return true;
            }
            int clicks = pointer.press(target, button);
            MouseEvent down = pointer.fire("mousedown", target, button, clicks, null);
            if (button == 2) pointer.fire("contextmenu", target, button, clicks, null);
            if (button == 0 && !down.defaultPrevented()) pressDefault(target, hit, clicks);
            return true;
        });
    }

    /** Default action of a primary mousedown: focus (or blur), then the control's own press behaviour. */
    private void pressDefault(Element target, HitResult hit, int clicks) {
        focus.focusByPointer(Dom.closest(target, Element::isFocusable));
        if (target.isDisabled() || target.box == null) return;
        float[] at = Dom.local(target.box, hit, pointer.x, pointer.y);
        Drag drag = null;
        if (target.isTextControl()) drag = TextField.of(target).press(at[0], at[1], clicks, pointer.mods().shift());
        else if (target.inputType().equals("range")) drag = RangeControl.of(target).press(at[0]);
        else if (target.tagName().equals("select")) openPopup(target);
        else if (target.replaced != null && target.hasAttribute("rotatable")) drag = turn(target);
        if (drag != null) pointer.capture(target, drag);
    }

    /** A press on a rotatable element: its turntable follows the pointer (in viewport px). */
    private Drag turn(Element target) {
        Turntable turntable = Turntable.of(target);
        if (!turntables.contains(turntable)) turntables.add(turntable);
        return turntable.press(pointer.x, pointer.y);
    }

    /** A mouse button went up. Returns true if the document is under the pointer. */
    public boolean mouseUp(float x, float y, int button, Modifiers mods) {
        return document.guard(() -> {
            pointer.moveTo(x, y, mods);
            Drag drag = pointer.releaseCapture();
            if (drag != null) drag.end();
            if (popup != null && popup.contains(x, y)) {
                pointer.release(null, button);
                if (popup.release(x, y)) popup = null;
                return true;
            }
            Element target = target(hitTest(x, y), x, y);
            Element clickTarget = pointer.release(target, button);
            if (target != null) pointer.fire("mouseup", target, button, pointer.clicks(), null);
            if (clickTarget != null && !clickTarget.isDisabled()) {
                MouseEvent click = pointer.fire("click", clickTarget, button, pointer.clicks(), null);
                if (!click.defaultPrevented()) Activation.run(clickTarget, click);
                if (pointer.clicks() == 2) pointer.fire("dblclick", clickTarget, button, 2, null);
            }
            if (drag != null) trackHover(); // the capture ended: hover follows the pointer again
            return target != null;
        });
    }

    /**
     * Wheel input; deltas in px (a notch is {@link #WHEEL_NOTCH}), positive scrolls down/right. Returns true if
     * consumed (cancelled or scrolled).
     */
    public boolean wheel(float x, float y, float deltaX, float deltaY, Modifiers mods) {
        return document.guard(() -> {
            pointer.moveTo(x, y, mods);
            float dx = deltaX, dy = deltaY;
            if (mods.shift() && dx == 0) {
                dx = dy;
                dy = 0;
            }
            if (popup != null) {
                if (popup.contains(x, y)) popup.wheel(dy);
                return true;
            }
            Element target = target(hitTest(x, y), x, y);
            if (target == null) return false;
            if (pointer.dispatch(new WheelEvent(x, y, pointer.buttons(), mods, dx, dy), target).defaultPrevented()) return true;
            boolean scrolled = scroller.wheel(target, dx, dy);
            if (scrolled) trackHover();
            return scrolled;
        });
    }

    /** Re-targets hover at the pointer position (after moves, scrolls and relayouts). Returns the hovered element. */
    private Element trackHover() {
        if (!pointer.known()) return null;
        HitResult hit = hitTest(pointer.x, pointer.y);
        Element target = target(hit, pointer.x, pointer.y);
        boolean overScrollbar = pointer.captured() == null && scroller.hover(hit);
        pointer.hover(target);
        pointer.updateCursor(target, overScrollbar);
        tooltips.track(pointer.hoverTarget(), document.scheduler().now());
        return target;
    }

    /** Hit tests a point, except while a drag holds the pointer or the open dropdown covers it (null then). */
    private HitResult hitTest(float x, float y) {
        if (pointer.captured() != null || popup != null && popup.contains(x, y)) return null;
        return document.painter().hitTest(x, y);
    }

    /** Where pointer events at a point go: the capturing element during drags, the select under its open list, else the hit. */
    private Element target(HitResult hit, float x, float y) {
        if (pointer.captured() != null) return pointer.captured();
        if (popup != null && popup.contains(x, y)) return popup.select();
        return hit == null ? null : hit.element();
    }

    // ---- Keyboard ----

    /**
     * A key went down. {@code key}/{@code code} use DOM names. A key that is already down (no keyup since) is
     * auto-repeat. Returns true if consumed (default prevented or handled).
     */
    public boolean keyDown(String key, String code, Modifiers mods) {
        return document.guard(() -> {
            boolean repeat = !keysDown.add(keyId(key, code));
            suppressChar = false;
            tooltips.dismiss();
            if (popup != null && popupKey(key)) return true;
            Element target = keyTarget();
            if (target == null) return false;
            if (!target.dispatchEvent(new KeyboardEvent("keydown", key, code, repeat, mods))) {
                suppressChar = key.codePointCount(0, key.length()) == 1;
                return true;
            }
            return keyDefault(document.focusedElement(), key, mods, repeat);
        });
    }

    public boolean keyUp(String key, String code, Modifiers mods) {
        return document.guard(() -> {
            keysDown.remove(keyId(key, code));
            Element target = keyTarget();
            if (target == null) return false;
            boolean prevented = !target.dispatchEvent(new KeyboardEvent("keyup", key, code, false, mods));
            return prevented || wantsKeyboard();
        });
    }

    /** What identifies a held key: its physical code, or its key when the host knows no code. */
    private static String keyId(String key, String code) {
        return code.isEmpty() || code.equals("Unidentified") ? key : code;
    }

    /** Text input (already composed characters). Returns true if consumed. */
    public boolean charTyped(String text) {
        return document.guard(() -> {
            if (suppressChar) {
                suppressChar = false;
                return true;
            }
            Element focused = document.focusedElement();
            if (focused == null || !focused.isTextControl()) return false;
            TextField.of(focused).type(text);
            return true;
        });
    }

    /** Keys while a dropdown is open; false lets the key continue (Tab closes the list and moves focus). */
    private boolean popupKey(String key) {
        switch (key) {
            case "Escape" -> popup = null;
            case "Enter", " " -> {
                popup.commit();
                popup = null;
            }
            case "Tab" -> {
                popup = null;
                return false;
            }
            default -> popup.navigate(key);
        }
        return true;
    }

    /** Default actions of an uncancelled keydown on the focused element (or with nothing focused). */
    private boolean keyDefault(Element el, String key, Modifiers mods, boolean repeat) {
        if (key.equals("Tab")) return focus.move(mods.shift());
        if (el == null) return false;
        if (el.isTextControl()) return TextField.of(el).keyDown(key, mods);
        if (el.isDisabled()) return false;
        if (el.tagName().equals("select")) {
            boolean open = key.equals("Enter") || key.equals(" ")
                    || mods.alt() && (key.equals("ArrowDown") || key.equals("ArrowUp"));
            if (open) openPopup(el);
            return open || SelectPopup.stepClosed(el, key);
        }
        if (el.inputType().equals("range")) return RangeControl.of(el).keyDown(key);
        if (el.inputType().equals("radio") && radioArrow(el, key)) return true;
        boolean pushable = Forms.isButton(el) || Forms.isDetailsSummary(el);
        boolean activate = switch (key) {
            case "Enter" -> pushable || el.tagName().equals("a") && el.hasAttribute("href");
            case " " -> !repeat && (pushable || el.isCheckable());
            default -> false;
        };
        if (activate) el.click();
        return activate;
    }

    /** Arrow keys in a radio group focus and check the next (or previous) enabled radio, wrapping. */
    private boolean radioArrow(Element radio, String key) {
        int dir = switch (key) {
            case "ArrowDown", "ArrowRight" -> 1;
            case "ArrowUp", "ArrowLeft" -> -1;
            default -> 0;
        };
        if (dir == 0) return false;
        List<Element> group = radio.radioGroup().stream().filter(r -> r == radio || !r.isDisabled()).toList();
        Element next = group.get(Math.floorMod(group.indexOf(radio) + dir, group.size()));
        focus.focusByKeyboard(next);
        Forms.checkRadio(next);
        return true;
    }

    private Element keyTarget() {
        Element focused = document.focusedElement();
        if (focused != null) return focused;
        return document.body() != null ? document.body() : document.documentElement();
    }

    // ---- Select dropdown ----

    private void openPopup(Element select) {
        SelectPopup p = new SelectPopup(select);
        if (p.isEmpty()) return;
        popup = p;
        Activation.playClick(document);
    }

    /**
     * Paints overlays above the whole document (the open select dropdown). The painter calls this last, with an
     * identity transform.
     */
    public void paintOverlays(Canvas canvas) {
        if (popup != null) popup.paint(canvas);
    }

    // ---- Frame and document hooks ----

    /**
     * Per-frame work before restyle: the document's scrolling (smooth scrolls, {@code scroll} events; hover follows
     * content that moved), drag auto-scroll, caret blink.
     */
    public void tick(double nowMs) {
        if (document.scrolling().tick(nowMs)) trackHover();
        if (pointer.drag() != null) pointer.drag().move(pointer.x, pointer.y);
        turntables.removeIf(t -> !t.moving());
        Element focused = document.focusedElement();
        if (focused != null && focused.isTextControl()) TextField.of(focused).blink(nowMs);
    }

    /** Whether {@link #tick} has work every frame: a drag, a spinning turntable, or a focused text field's caret. */
    public boolean isActive() {
        Element focused = document.focusedElement();
        return pointer.drag() != null || !turntables.isEmpty() || focused != null && focused.isTextControl();
    }

    /**
     * Whether input will still change the page by itself ({@link Document#settled}): a drag held, a turntable
     * spinning, or a tooltip waiting out its delay. Not the caret's blink, which never stops.
     */
    public boolean isSettling() {
        return pointer.drag() != null || !turntables.isEmpty() || pointer.known() && tooltips.pending();
    }

    /** Called after each relayout: keeps the caret in view, autofocus, re-hit-tests hover. */
    public void afterLayout() {
        Element focused = document.focusedElement();
        if (focused != null && focused.isTextControl()) TextField.of(focused).scrollToCaret();
        if (popup != null && popup.select().box == null) popup = null;
        focus.autofocus();
        trackHover();
    }

    /** Called before a node leaves the document. */
    public void nodeRemoving(Node node) {
        pointer.forget(node);
        scroller.forget(node);
        turntables.removeIf(t -> node.contains(t.element()));
        if (popup != null && node.contains(popup.select())) popup = null;
    }

    /**
     * Called by {@link Document#setFocus} when focus moves, before blur/focus events fire: text fields fire
     * {@code change} on blur and remember their value on focus, a select losing focus closes its dropdown, and the
     * newly focused element scrolls into view.
     */
    public void focusChanged(Element old, Element now) {
        if (old != null && old.isTextControl()) TextField.of(old).blurred();
        if (popup != null && popup.select() == old) popup = null;
        if (now != null && now.isTextControl()) TextField.of(now).focused();
        focus.focusChanged(now);
    }

    /** The default action of an uncancelled synthetic click ({@link Element#click()}). */
    public void activate(Element target, Event event) {
        Activation.run(target, event);
    }

    // ---- Queries ----

    /**
     * The {@code title} / {@code title-json} tooltip to show now, or null: the nearest element with one, from the
     * hovered element up, once the pointer has rested on it for half a second (and until a button or key is
     * pressed). Hosts ask once per frame after painting and draw it on top at the pointer.
     */
    public Tooltip tooltip() {
        if (!pointer.known() || document.error() != null) return null;
        return tooltips.current(pointer.hoverTarget(), document.scheduler().now(), pointer.x, pointer.y);
    }

    /** Whether a tooltip becomes visible at {@code nowMs} that {@link #tooltip()} has not returned yet. */
    public boolean tooltipDue(double nowMs) {
        return pointer.known() && tooltips.due(nowMs);
    }

    /**
     * True when focus last moved by keyboard (Tab, arrows), false after pointer input. The style engine uses it for
     * {@code :focus-visible}.
     */
    public boolean focusVisible() {
        return focus.visible();
    }

    /** True when a text field has focus, so hosts can suppress their own key bindings (e.g. inventory key). */
    public boolean wantsKeyboard() {
        Element focused = document.focusedElement();
        return focused != null && document.error() == null && Forms.isEditable(focused);
    }

    /** True when the pointer is over (or dragging) a scroll container's scrollbar; the painter widens it. */
    public boolean isScrollbarHovered(Element container, boolean vertical) {
        return scroller.isHovered(container, vertical);
    }
}
