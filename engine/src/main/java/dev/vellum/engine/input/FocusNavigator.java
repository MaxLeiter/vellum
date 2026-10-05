package dev.vellum.engine.input;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.style.Display;
import dev.vellum.engine.style.Visibility;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Keyboard focus: sequential navigation (Tab / Shift+Tab) in tabindex order, the {@code :focus-visible} flag,
 * {@code autofocus}, and scrolling newly focused elements into view (except when focused by a pointer press, which
 * is already on screen).
 */
final class FocusNavigator {
    private final Document document;
    private final Scroller scroller;
    private boolean visible;
    private boolean autofocusDone;
    /** True while a pointer press moves focus. */
    private boolean byPointer;

    FocusNavigator(Document document, Scroller scroller) {
        this.document = document;
        this.scroller = scroller;
    }

    /** True when focus last moved by keyboard, false after pointer focus. */
    boolean visible() {
        return visible;
    }

    /**
     * The sequential navigation order: elements with a positive tabindex ascending, then those with tabindex 0, each
     * in tree order. Skips elements that are disabled, not rendered, hidden, inside a closed {@code <details>}, and
     * unchecked radios of a group that has a checked one (arrow keys move within a group).
     */
    List<Element> order() {
        List<Element> positive = new ArrayList<>(), zero = new ArrayList<>();
        for (Element e : Dom.elements(document)) {
            if (!e.isFocusable() || e.tabIndex() < 0 || !isNavigable(e) || !isRadioTabStop(e)) continue;
            (e.tabIndex() > 0 ? positive : zero).add(e);
        }
        positive.sort(Comparator.comparingInt(Element::tabIndex)); // stable: ties stay in tree order
        positive.addAll(zero);
        return positive;
    }

    /** Moves focus to the next (or previous) element in tab order, wrapping. False when nothing is focusable. */
    boolean move(boolean backward) {
        List<Element> order = order();
        if (order.isEmpty()) return false;
        int i = order.indexOf(document.focusedElement());
        int next = i < 0 ? (backward ? order.size() - 1 : 0) : Math.floorMod(i + (backward ? -1 : 1), order.size());
        Element target = order.get(next);
        focusByKeyboard(target);
        if (target.tagName().equals("input") && Forms.isTextControl(target)) TextField.of(target).selectAll();
        return true;
    }

    /** Focuses an element by keyboard, making focus visible. */
    void focusByKeyboard(Element e) {
        setVisible(true);
        document.setFocus(e);
    }

    /** Focuses an element (or blurs, for null) because of a pointer press. */
    void focusByPointer(Element e) {
        setVisible(false);
        byPointer = true;
        try {
            document.setFocus(e);
        } finally {
            byPointer = false;
        }
    }

    /** Focus moved to {@code e} (by keyboard, script or pointer): scrolls it into view unless the pointer did it. */
    void focusChanged(Element e) {
        if (e != null && !byPointer) scroller.scrollIntoView(e);
    }

    /** Focuses the first rendered focusable {@code autofocus} element, once, unless something already has focus. */
    void autofocus() {
        if (autofocusDone) return;
        autofocusDone = true;
        if (document.focusedElement() != null) return;
        for (Element e : Dom.elements(document)) {
            if (e.hasAttribute("autofocus") && e.isFocusable() && isNavigable(e)) {
                focusByKeyboard(e);
                return;
            }
        }
    }

    private void setVisible(boolean v) {
        if (visible == v) return;
        visible = v;
        document.invalidateStyle(); // :focus-visible
    }

    private static boolean isNavigable(Element e) {
        return isRendered(e) && (e.style == null || e.style.visibility == Visibility.VISIBLE) && !inClosedDetails(e);
    }

    /**
     * Rendered: has a box, or is a boxless (inline) element whose ancestors up to the nearest box are all displayed.
     */
    private static boolean isRendered(Element e) {
        for (Element p = e; p != null; p = p.parentElement()) {
            if (p.box != null) return true;
            if (p.style == null || p.style.display == Display.NONE) return false;
        }
        return false;
    }

    /** True inside a closed {@code <details>}, except in its summary. */
    private static boolean inClosedDetails(Element e) {
        for (Element child = e, p = e.parentElement(); p != null; child = p, p = p.parentElement()) {
            if (p.tagName().equals("details") && !p.hasAttribute("open") && !child.tagName().equals("summary")) return true;
        }
        return false;
    }

    private static boolean isRadioTabStop(Element e) {
        if (!Forms.isInput(e, "radio") || e.checked()) return true;
        for (Element r : Forms.radioGroup(e)) if (r.checked()) return false;
        return true;
    }
}
