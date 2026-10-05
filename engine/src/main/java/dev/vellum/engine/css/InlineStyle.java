package dev.vellum.engine.css;

import dev.vellum.engine.dom.Element;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Read/write access to an element's inline {@code style} attribute, for the script binding of
 * {@code element.style} (CSSStyleDeclaration). Property names are CSS names ({@code background-color}, or
 * {@code --custom}); the script layer converts camelCase. Every write rewrites the {@code style} attribute, which
 * invalidates style.
 *
 * <p>The attribute keeps declarations as written (a shorthand stays a shorthand) in their original order; a
 * property that is set again is updated in place. Reading a longhand covered by a shorthand returns that
 * longhand's part of it.
 */
public final class InlineStyle {
    private InlineStyle() {}

    /** One declaration as written. */
    private record Entry(String name, String value, boolean important) {
        CssParser.Declaration parse() {
            return new CssParser.Declaration(name, CssParser.parseComponentValues(value), important, 0);
        }

        /** The longhands (or custom property) this entry sets. */
        List<String> longhands() {
            Shorthand s = Shorthands.get(name);
            if (s != null) return s.longhands().stream().map(l -> l.name).toList();
            Longhand l = Properties.longhand(name);
            return List.of(l == null ? name : l.name);
        }

        boolean covers(String longhand) {
            return longhands().contains(longhand);
        }

        /** This entry's value for one of its longhands; "" if it cannot be told (var() in a shorthand). */
        String valueOf(String longhand) {
            Shorthand s = Shorthands.get(name);
            if (s == null) return value;
            CssParser.Declaration d = parse();
            if (Decl.keyword(d.value()) != null) return value;
            Map<Longhand, List<ComponentValue>> parts = Decl.containsVar(d.value()) ? null : s.expand(d.value());
            return parts == null ? "" : ComponentValue.text(parts.get(Properties.longhand(longhand)));
        }
    }

    /** The declared value text, or "" if not set. Shorthands are reconstructed when all longhands are set. */
    public static String getPropertyValue(Element element, String property) {
        String name = normalize(property);
        List<Entry> entries = entries(element);
        Shorthand shorthand = Shorthands.get(name);
        if (shorthand == null) {
            Entry e = effective(entries, name);
            return e == null ? "" : e.valueOf(name);
        }
        // The shorthand as written, unless a later declaration overrides part of it.
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (e.name.equals(name)) return e.value;
            if (e.longhands().stream().anyMatch(l -> shorthand.longhands().contains(Properties.longhand(l)))) break;
        }
        List<String> values = new ArrayList<>();
        for (Longhand l : shorthand.longhands()) {
            Entry e = effective(entries, l.name);
            String v = e == null ? "" : e.valueOf(l.name);
            if (v.isEmpty()) return "";
            values.add(v);
        }
        String joined = shorthand.join(values);
        return joined == null ? "" : joined;
    }

    /** "important" or "". */
    public static String getPropertyPriority(Element element, String property) {
        List<String> longhands = new Entry(normalize(property), "", false).longhands();
        List<Entry> entries = entries(element);
        for (String l : longhands) {
            Entry e = effective(entries, l);
            if (e == null || !e.important) return "";
        }
        return longhands.isEmpty() ? "" : "important";
    }

    /** Sets a declaration; a null or empty value removes it. Invalid values are ignored, as in browsers. */
    public static void setProperty(Element element, String property, String value, String priority) {
        if (value == null || value.isBlank()) {
            removeProperty(element, property);
            return;
        }
        Entry entry = new Entry(normalize(property), value.trim(), "important".equalsIgnoreCase(priority));
        if (!valid(entry)) return;
        List<Entry> entries = entries(element);
        int at = -1;
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).name.equals(entry.name)) at = i;
        if (at >= 0) {
            entries.set(at, entry);
        } else {
            // A shorthand replaces earlier declarations of its longhands.
            List<String> covered = entry.longhands();
            entries.removeIf(e -> Shorthands.get(e.name) == null && covered.containsAll(e.longhands()));
            entries.add(entry);
        }
        write(element, entries);
    }

    /** Removes a declaration and returns its old value ("" if none). */
    public static String removeProperty(Element element, String property) {
        String name = normalize(property);
        String old = getPropertyValue(element, name);
        List<String> removed = new Entry(name, "", false).longhands();
        List<Entry> before = entries(element), after = new ArrayList<>();
        for (int i = 0; i < before.size(); i++) {
            Entry e = before.get(i);
            List<String> longhands = e.longhands();
            if (e.name.equals(name) || (!longhands.isEmpty() && removed.containsAll(longhands))) continue;
            if (longhands.stream().noneMatch(removed::contains)) {
                after.add(e);
                continue;
            }
            // A shorthand only partly removed splits into its remaining longhands (unless set again later).
            List<Entry> later = before.subList(i + 1, before.size());
            for (String l : longhands) {
                if (!removed.contains(l) && later.stream().noneMatch(x -> x.covers(l))) {
                    after.add(new Entry(l, e.valueOf(l), e.important));
                }
            }
        }
        write(element, after);
        return old;
    }

    /** The serialised declarations. */
    public static String cssText(Element element) {
        return serialize(entries(element));
    }

    public static void setCssText(Element element, String cssText) {
        write(element, parse(cssText));
    }

    /** Number of declared properties (after shorthand expansion). */
    public static int length(Element element) {
        return declaredLonghands(element).size();
    }

    /** The name of the index-th declared property, or "". */
    public static String item(Element element, int index) {
        List<String> names = new ArrayList<>(declaredLonghands(element));
        return index >= 0 && index < names.size() ? names.get(index) : "";
    }

    private static Set<String> declaredLonghands(Element element) {
        Set<String> names = new LinkedHashSet<>();
        for (Entry e : entries(element)) names.addAll(e.longhands());
        return names;
    }

    /** The last declaration setting {@code longhand}, preferring important ones. */
    private static Entry effective(List<Entry> entries, String longhand) {
        Entry found = null;
        for (Entry e : entries) {
            if (e.covers(longhand) && (found == null || e.important || !found.important)) found = e;
        }
        return found;
    }

    private static String normalize(String property) {
        String p = property.trim();
        if (p.startsWith("--")) return p;
        p = p.toLowerCase(Locale.ROOT);
        Longhand alias = Properties.longhand(p);
        return alias == null ? p : alias.name;
    }

    private static List<Entry> entries(Element element) {
        String style = element.getAttribute("style");
        return style == null ? new ArrayList<>() : parse(style);
    }

    /** The valid declarations of a style attribute, as written. */
    private static List<Entry> parse(String css) {
        List<Entry> out = new ArrayList<>();
        for (CssParser.Declaration d : CssParser.parseDeclarations(css)) {
            Entry e = new Entry(normalize(d.name()), ComponentValue.text(d.value()), d.important());
            if (valid(e)) out.add(e);
        }
        return out;
    }

    private static boolean valid(Entry e) {
        return Decl.expand(e.parse(), "", null) != null;
    }

    private static String serialize(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(e.name).append(": ").append(e.value).append(e.important ? " !important;" : ";");
        }
        return sb.toString();
    }

    private static void write(Element element, List<Entry> entries) {
        String css = serialize(entries);
        if (css.isEmpty()) element.removeAttribute("style");
        else element.setAttribute("style", css);
    }
}
