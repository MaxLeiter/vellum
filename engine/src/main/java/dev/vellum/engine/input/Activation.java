package dev.vellum.engine.input;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.event.Event;

/**
 * Default actions of uncancelled clicks (HTML activation behaviour), for pointer clicks and synthetic
 * {@code element.click()} alike: checkboxes and radios, labels, details/summary, links and submit buttons.
 */
final class Activation {
    static final String CLICK_SOUND = "minecraft:ui.button.click";

    private Activation() {}

    /** Runs the activation behaviour of the nearest element at or above {@code target} that has one. */
    static void run(Element target, Event event) {
        Element e = Dom.closest(target, Activation::hasBehaviour);
        if (e == null) return;
        Document doc = e.ownerDocument();
        switch (e.tagName()) {
            case "label" -> forward(e, event);
            case "a" -> doc.host().navigate(doc.resolveUrl(e.getAttribute("href")));
            case "summary" -> {
                playClick(doc);
                Element details = e.parentElement();
                details.toggleAttribute("open", !details.hasAttribute("open"));
                details.dispatchEvent(new Event("toggle", false, false));
            }
            default -> { // buttons, checkboxes, radios
                if (e.isDisabled()) return;
                playClick(doc);
                if (e.inputType().equals("checkbox")) {
                    e.setChecked(!e.checked());
                    Forms.fireInputAndChange(e);
                } else if (e.inputType().equals("radio")) {
                    Forms.checkRadio(e);
                } else if (Forms.isSubmit(e)) {
                    Element form = e.form();
                    if (form != null) form.dispatchEvent(new Event("submit", true, true));
                }
            }
        }
    }

    static void playClick(Document doc) {
        doc.host().playSound(CLICK_SOUND, 1f, 1f);
    }

    private static boolean hasBehaviour(Element e) {
        return switch (e.tagName()) {
            case "label", "button" -> true;
            case "a" -> e.hasAttribute("href");
            case "summary" -> Forms.isDetailsSummary(e);
            case "input" -> Forms.isButton(e) || e.isCheckable();
            default -> false;
        };
    }

    /**
     * A label clicks (and focuses) its control, unless the click came from inside the control, which then handled it
     * itself.
     */
    private static void forward(Element label, Event event) {
        Element control = Forms.labeledControl(label);
        if (control == null || event.target() != null && control.contains(event.target())) return;
        if (control.isFocusable()) control.focus();
        control.click();
    }
}
