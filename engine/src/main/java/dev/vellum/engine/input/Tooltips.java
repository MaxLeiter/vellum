package dev.vellum.engine.input;

import dev.vellum.engine.dom.Element;

/**
 * Which element's {@code title} / {@code title-json} applies, and when it shows. The element is the nearest one with
 * either attribute, from the hovered element up (an empty attribute means "no tooltip here", as in HTML, so a child
 * can opt out of its ancestor's). Its tooltip shows once the pointer has rested on it, or anything inside it, for
 * {@link #DELAY_MS}, and stays until the pointer moves to another tooltip's element or a button or key is pressed.
 * The attributes are read when the host asks, so scripts can change the text live.
 */
final class Tooltips {
    /** How long the pointer rests before a tooltip shows, as for title tooltips in browsers. */
    static final double DELAY_MS = 500;

    private Element owner;
    private double since;
    private boolean dismissed, shown;

    /** The hover target changed (or its attributes may have): restart the delay if another element's tooltip applies. */
    void track(Element hovered, double now) {
        Element o = owner(hovered);
        if (o == owner) return;
        owner = o;
        since = now;
        dismissed = false;
        shown = false;
    }

    /** A button or key was pressed: hide the tooltip until the pointer reaches another tooltip's element. */
    void dismiss() {
        dismissed = true;
    }

    /** The tooltip to show at {@code now} for the hovered element, or null. */
    Tooltip current(Element hovered, double now, float x, float y) {
        track(hovered, now);
        if (owner == null || dismissed || now - since < DELAY_MS) return null;
        String text = nonBlank(owner.getAttribute("title")), json = nonBlank(owner.getAttribute("title-json"));
        if (text == null && json == null) return null;
        shown = true;
        return new Tooltip(owner, text, json, x, y);
    }

    /** Whether a tooltip with text is coming that the host has not been given yet: waiting out the delay, or due. */
    boolean pending() {
        return owner != null && !dismissed && !shown
                && (nonBlank(owner.getAttribute("title")) != null || nonBlank(owner.getAttribute("title-json")) != null);
    }

    /** Whether a tooltip becomes visible at {@code now} that the host has not been given yet. */
    boolean due(double now) {
        return now >= since + DELAY_MS && pending();
    }

    private static Element owner(Element e) {
        for (; e != null; e = e.parentElement()) {
            if (e.hasAttribute("title") || e.hasAttribute("title-json")) return e;
        }
        return null;
    }

    private static String nonBlank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
