package dev.vellum.engine.input;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.event.Event;
import dev.vellum.engine.event.InputEvent;

import java.util.Set;

/**
 * What form behaviour needs beyond the DOM's own form state ({@link Element#inputType()},
 * {@link Element#isTextControl()}, {@link Element#radioGroup()}...): push buttons, label targets, user-initiated
 * changes and their events.
 */
final class Forms {
    private static final Set<String> LABELABLE = Set.of("input", "select", "textarea", "button", "meter", "progress");

    private Forms() {}

    /** True for a text control the user can type into. */
    static boolean isEditable(Element e) {
        return e.isTextControl() && !e.hasAttribute("readonly") && !e.isDisabled();
    }

    /** Push buttons: {@code <button>} and input types submit, reset, button and image. */
    static boolean isButton(Element e) {
        if (e.tagName().equals("button")) return true;
        return switch (e.inputType()) {
            case "submit", "reset", "button", "image" -> true;
            default -> false;
        };
    }

    /** Submit buttons: {@code <button>} without a type (or type=submit) and input types submit and image. */
    static boolean isSubmit(Element e) {
        if (e.tagName().equals("button")) {
            String type = e.getAttribute("type");
            return type == null || !(type.equalsIgnoreCase("button") || type.equalsIgnoreCase("reset"));
        }
        return e.inputType().equals("submit") || e.inputType().equals("image");
    }

    /**
     * Enter in a single-line text field submits its form, as in browsers: it clicks the form's first submit button,
     * or, when the form has none, submits the form if the field is its only one.
     */
    static void submitImplicitly(Element field) {
        Element form = field.form();
        if (form == null) return;
        Element button = form.firstDescendant(Forms::isSubmit);
        if (button != null) {
            if (!button.isDisabled()) button.click();
        } else if (form.descendants(d -> d.tagName().equals("input") && d.isTextControl()).size() == 1) {
            form.dispatchEvent(new Event("submit", true, true));
        }
    }

    /** True for a {@code <summary>} that toggles its parent {@code <details>}. */
    static boolean isDetailsSummary(Element e) {
        return e.tagName().equals("summary") && e.parentElement() != null && e.parentElement().tagName().equals("details");
    }

    /** Elements a {@code <label>} can label: inputs, selects, textareas, buttons, meters and progress bars. */
    static boolean isLabelable(Element e) {
        return LABELABLE.contains(e.tagName());
    }

    /** The control a label activates: its {@code for} target, else its first labelable descendant. */
    static Element labeledControl(Element label) {
        String id = label.getAttribute("for");
        if (id != null) {
            Element c = label.ownerDocument().getElementById(id);
            return c != null && LABELABLE.contains(c.tagName()) ? c : null;
        }
        return label.firstDescendant(d -> LABELABLE.contains(d.tagName()));
    }

    /** The user checks a radio (unchecking its group), firing input and change. False if it was already checked. */
    static boolean checkRadio(Element radio) {
        if (radio.checked()) return false;
        radio.setChecked(true);
        fireInputAndChange(radio);
        return true;
    }

    /** Parses a number (an attribute or value), or returns {@code fallback} when missing or invalid. */
    static double number(String s, double fallback) {
        if (s == null) return fallback;
        try {
            double d = Double.parseDouble(s.trim());
            return Double.isFinite(d) ? d : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Fires {@code input} then {@code change}, as after a user-initiated value change of a non-text control. */
    static void fireInputAndChange(Element e) {
        e.dispatchEvent(new InputEvent("input", null, null));
        e.dispatchEvent(new InputEvent("change", null, null));
    }
}
