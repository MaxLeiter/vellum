package dev.vellum.engine.input;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.event.InputEvent;
import dev.vellum.engine.style.ComputedStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Classification of form elements and the small DOM queries form behaviour needs (form owner, label target, radio
 * group).
 */
final class Forms {
    /** Input types that are not text fields. Anything else (including unknown types) edits text, as in HTML. */
    private static final Set<String> NON_TEXT_TYPES =
            Set.of("checkbox", "radio", "range", "submit", "reset", "button", "image", "hidden", "color", "file");
    private static final Set<String> LABELABLE = Set.of("input", "select", "textarea", "button", "meter", "progress");

    private Forms() {}

    /** The lower-case {@code type} of an input, defaulting to "text". */
    static String inputType(Element input) {
        String t = input.getAttribute("type");
        return t == null || t.isBlank() ? "text" : t.trim().toLowerCase(Locale.ROOT);
    }

    static boolean isInput(Element e, String type) {
        return e.tagName().equals("input") && inputType(e).equals(type);
    }

    /** True for textareas and text-like inputs (text, password, number, search, email, url, tel...). */
    static boolean isTextControl(Element e) {
        return e.tagName().equals("textarea") || e.tagName().equals("input") && !NON_TEXT_TYPES.contains(inputType(e));
    }

    /** True for a text control the user can type into. */
    static boolean isEditable(Element e) {
        return isTextControl(e) && !e.hasAttribute("readonly") && !e.isDisabled();
    }

    /** Push buttons: {@code <button>} and input types submit, reset, button and image. */
    static boolean isButton(Element e) {
        if (e.tagName().equals("button")) return true;
        if (!e.tagName().equals("input")) return false;
        return switch (inputType(e)) {
            case "submit", "reset", "button", "image" -> true;
            default -> false;
        };
    }

    /** True for a {@code <summary>} that toggles its parent {@code <details>}. */
    static boolean isDetailsSummary(Element e) {
        return e.tagName().equals("summary") && e.parentElement() != null && e.parentElement().tagName().equals("details");
    }

    /** The style used for painting and metrics; the initial style before the first restyle. */
    static ComputedStyle style(Element e) {
        return e.style != null ? e.style : ComputedStyle.INITIAL;
    }

    /** The element's form: the one named by its {@code form} attribute, else the nearest ancestor form, else null. */
    static Element formOwner(Element e) {
        String id = e.getAttribute("form");
        if (id != null && e.ownerDocument() != null) {
            Element f = e.ownerDocument().getElementById(id);
            return f != null && f.tagName().equals("form") ? f : null;
        }
        for (Element p = e.parentElement(); p != null; p = p.parentElement()) if (p.tagName().equals("form")) return p;
        return null;
    }

    /** The control a label activates: its {@code for} target, else its first labelable descendant. */
    static Element labeledControl(Element label) {
        String id = label.getAttribute("for");
        if (id != null) {
            Element c = label.ownerDocument() == null ? null : label.ownerDocument().getElementById(id);
            return c != null && LABELABLE.contains(c.tagName()) ? c : null;
        }
        for (Element d : label.getElementsByTagName("*")) if (LABELABLE.contains(d.tagName())) return d;
        return null;
    }

    /** The radios sharing {@code radio}'s name and form owner, in tree order (just the radio itself when unnamed). */
    static List<Element> radioGroup(Element radio) {
        String name = radio.getAttribute("name");
        if (name == null || name.isEmpty()) return List.of(radio);
        Element form = formOwner(radio);
        Element root = form;
        if (root == null) for (root = radio; root.parentElement() != null; ) root = root.parentElement();
        if (root == radio) return List.of(radio);
        List<Element> group = new ArrayList<>();
        for (Element e : root.getElementsByTagName("input")) {
            if (isInput(e, "radio") && name.equals(e.getAttribute("name")) && formOwner(e) == form) group.add(e);
        }
        return group;
    }

    /** Checks a radio and unchecks the rest of its group, firing input and change. False if it was already checked. */
    static boolean checkRadio(Element radio) {
        if (radio.checked()) return false;
        for (Element other : radioGroup(radio)) if (other != radio) other.setChecked(false);
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
