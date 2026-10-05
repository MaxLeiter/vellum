package dev.vellum.engine.css;

import dev.vellum.engine.style.ComputedStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * Serialises computed values for {@code getComputedStyle}: lengths as px or %, colours as {@code rgb()}/{@code rgba()},
 * keywords in lower case, lists comma-separated. Shorthands are rebuilt from their longhands.
 */
final class ComputedValues {
    private ComputedValues() {}

    static String serialize(ComputedStyle s, String property) {
        if (property.startsWith("--")) {
            String v = s.var(property);
            return v == null ? "" : v;
        }
        Longhand longhand = Properties.longhand(property);
        if (longhand != null) return longhand.serialize(longhand.get(s));
        Shorthand shorthand = Shorthands.get(property);
        if (shorthand == null) return "";
        return switch (property) {
            case "text-decoration", "text-decoration-line" -> s.underline || s.lineThrough
                    ? ((s.underline ? "underline " : "") + (s.lineThrough ? "line-through" : "")).trim() : "none";
            case "font" -> (s.fontItalic ? "italic " : "") + s.fontWeight + " " + CssText.px(s.fontSize)
                    + (Float.isNaN(s.lineHeight) ? "" : " / " + CssText.px(s.lineHeight)) + " "
                    + serialize(s, "font-family");
            case "transition" -> entries(ListGroup.TRANSITION, s.transitions);
            case "animation" -> entries(ListGroup.ANIMATION, s.animations);
            case "background" -> entries(ListGroup.BACKGROUND, s.backgroundLayers) + " "
                    + CssColors.serialize(s.backgroundColor);
            default -> {
                List<String> parts = new ArrayList<>(shorthand.longhands().size());
                for (Longhand l : shorthand.longhands()) parts.add(l.serialize(l.get(s)));
                String joined = shorthand.join(parts);
                yield joined == null ? "" : joined;
            }
        };
    }

    /**
     * A list property entry by entry, each entry's components in shorthand order ({@code opacity 0.2s ease 0s}); the
     * initial entry when the list is empty. Background sizes follow the position after a slash.
     */
    private static String entries(ListGroup group, List<?> list) {
        List<List<?>> components = group.decompose(list);
        List<Longhand> longhands = Properties.components(group);
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < components.get(0).size(); i++) {
            StringBuilder sb = new StringBuilder();
            for (Longhand c : longhands) {
                boolean size = group == ListGroup.BACKGROUND && c.name.equals("background-size");
                if (!sb.isEmpty()) sb.append(size ? " / " : " ");
                sb.append(c.serialize(List.of(components.get(c.component).get(i))));
            }
            entries.add(sb.toString());
        }
        return String.join(", ", entries);
    }
}
