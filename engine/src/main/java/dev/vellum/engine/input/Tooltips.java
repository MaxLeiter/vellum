package dev.vellum.engine.input;

import dev.vellum.engine.dom.Element;

/**
 * Which element's {@code title} / {@code title-json} applies, and when it shows. The element is the nearest one with
 * either attribute, from the hovered element up (an empty attribute means "no tooltip here", as in HTML, so a child
 * can opt out of its ancestor's). Its tooltip shows once the pointer has rested on it, or anything inside it, for
 * {@link #DELAY_MS}, and stays until the pointer moves to another tooltip's element or a button or key is pressed.
 * Its lines wrap unless the element has {@code title-nowrap}.
 *
 * <p>While the pointer is on an element whose content shows a tooltip of its own (an {@code <item tooltip>}), that
 * tooltip shows instead, at once and through presses, as Minecraft's item tooltips do, and the title that applies
 * (looked up from that element) adds its lines after the content's, unwrapped.
 *
 * <p>The attributes are read when the host asks, so scripts can change the text live.
 */
final class Tooltips {
    /** How long the pointer rests before a tooltip shows, as for title tooltips in browsers. */
    static final double DELAY_MS = 500;

    private Element hovered, owner;
    private double since;
    private boolean dismissed, shown;

    /** The hover target changed (or its attributes may have): restart the delay if another element's tooltip applies. */
    void track(Element hovered, double now) {
        this.hovered = hovered;
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
        Element content = content();
        if (content == null && (dismissed || now - since < DELAY_MS)) return null;
        if (!titled(owner)) return content == null ? null : new Tooltip(null, null, null, x, y, content);
        if (content == null) shown = true;
        String text = nonBlank(owner.getAttribute("title")), json = nonBlank(owner.getAttribute("title-json"));
        return new Tooltip(owner, text, json, x, y, content);
    }

    /** Whether a title tooltip is coming that the host has not been given yet: waiting out the delay, or due. */
    boolean pending() {
        return !dismissed && !shown && content() == null && titled(owner);
    }

    /** Whether a tooltip becomes visible at {@code now} that the host has not been given yet. */
    boolean due(double now) {
        return now >= since + DELAY_MS && pending();
    }

    /** The hovered element when its content shows a tooltip of its own, else null. */
    private Element content() {
        return hovered != null && hovered.replaced != null && hovered.replaced.showsTooltip() ? hovered : null;
    }

    /** Whether {@code owner} (null for none) has a title to show: a non-blank {@code title} or {@code title-json}. */
    private static boolean titled(Element owner) {
        return owner != null && (nonBlank(owner.getAttribute("title")) != null
                || nonBlank(owner.getAttribute("title-json")) != null);
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
