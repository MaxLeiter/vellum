package dev.vellum.engine.script;

import dev.vellum.engine.dom.Element;

import java.util.List;
import java.util.Locale;

/**
 * Form control state as scripts see it, shared by the DOM bindings and {@code v-model}. A select's selection is its
 * live value ({@link Element#value()}) when that names an option, else the first {@code option[selected]}, else the
 * first option.
 */
final class Forms {
    private Forms() {}

    /** The control type: an input's lower-case {@code type} (default "text"), else the tag name ("select"...). */
    static String type(Element e) {
        if (!e.tagName().equals("input")) return e.tagName();
        String type = e.getAttribute("type");
        return type == null || type.isBlank() ? "text" : type.trim().toLowerCase(Locale.ROOT);
    }

    /** Whether {@code value} is live state (typed text, selection) rather than the {@code value} attribute. */
    static boolean hasLiveValue(Element e) {
        return switch (e.tagName()) {
            case "input" -> !isCheckable(e);
            case "textarea", "select" -> true;
            default -> false;
        };
    }

    static boolean isCheckable(Element e) {
        String type = type(e);
        return e.tagName().equals("input") && (type.equals("checkbox") || type.equals("radio"));
    }

    /** {@code element.value}: a select's selected option value, "on" for a checkbox without one, else the live value. */
    static String value(Element e) {
        if (e.tagName().equals("select")) {
            int i = selectedIndex(e);
            return i < 0 ? "" : optionValue(options(e).get(i));
        }
        if (isCheckable(e) && !e.hasAttribute("value")) return "on";
        return e.value();
    }

    static List<Element> options(Element select) {
        return select.getElementsByTagName("option");
    }

    static String optionValue(Element option) {
        String value = option.getAttribute("value");
        return value != null ? value : option.textContent().strip().replaceAll("\\s+", " ");
    }

    static int selectedIndex(Element select) {
        List<Element> options = options(select);
        String live = select.value();
        if (!live.isEmpty()) {
            for (int i = 0; i < options.size(); i++) if (optionValue(options.get(i)).equals(live)) return i;
        }
        for (int i = 0; i < options.size(); i++) if (options.get(i).hasAttribute("selected")) return i;
        return options.isEmpty() ? -1 : 0;
    }

    static void setSelectedIndex(Element select, int index) {
        List<Element> options = options(select);
        select.setValue(index >= 0 && index < options.size() ? optionValue(options.get(index)) : "");
    }

    static boolean selected(Element option) {
        Element select = selectOf(option);
        return select == null ? option.hasAttribute("selected") : options(select).indexOf(option) == selectedIndex(select);
    }

    static void setSelected(Element option, boolean selected) {
        Element select = selectOf(option);
        if (select == null) option.toggleAttribute("selected", selected);
        else if (selected) select.setValue(optionValue(option));
    }

    private static Element selectOf(Element option) {
        for (Element p = option.parentElement(); p != null; p = p.parentElement()) {
            if (p.tagName().equals("select")) return p;
        }
        return null;
    }
}
