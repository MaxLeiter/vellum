package dev.vellum.engine.css;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.Host;

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
 *
 * <p>The parsed declarations live in the element's {@link ElementState}, which the cascade reads too: the attribute
 * is parsed once when it changes from outside, and writes through this class install their result directly.
 */
public final class InlineStyle {
    private InlineStyle() {}

    /** One declaration as written, with the longhand declarations it expands to for the cascade. */
    record Entry(String name, String value, boolean important, List<Decl> decls) {
        /** The longhands (or custom property) this entry sets. */
        List<String> longhands() {
            return InlineStyle.longhands(name);
        }

        boolean covers(String longhand) {
            return longhands().contains(longhand);
        }

        /** This entry's value for one of its longhands; "" if it cannot be told (var() in a shorthand). */
        String valueOf(String longhand) {
            Shorthand s = Shorthands.get(name);
            if (s == null) return value;
            List<ComponentValue> parsed = CssParser.parseComponentValues(value);
            if (Decl.keyword(parsed) != null) return value;
            Map<Longhand, List<ComponentValue>> parts = Decl.containsVar(parsed) ? null : s.expand(parsed);
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
        List<String> longhands = longhands(normalize(property));
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
        Entry entry = entry(element, normalize(property), value.trim(), "important".equalsIgnoreCase(priority));
        if (entry == null) return; // invalid
        List<Entry> entries = new ArrayList<>(entries(element));
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
        List<String> removed = longhands(name);
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
                    Entry part = entry(element, l, e.valueOf(l), e.important);
                    if (part != null) after.add(part);
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
        write(element, parse(element, cssText));
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

    /** The longhands (or the custom property) a declaration of {@code name} sets. */
    private static List<String> longhands(String name) {
        Shorthand s = Shorthands.get(name);
        if (s != null) return s.longhands().stream().map(l -> l.name).toList();
        Longhand l = Properties.longhand(name);
        return List.of(l == null ? name : l.name);
    }

    private static String normalize(String property) {
        String p = property.trim();
        if (p.startsWith("--")) return p;
        p = p.toLowerCase(Locale.ROOT);
        Longhand alias = Properties.longhand(p);
        return alias == null ? p : alias.name;
    }

    /** The element's declarations as written (read-only). */
    private static List<Entry> entries(Element element) {
        return ElementState.of(element).inlineEntries;
    }

    /**
     * The valid declarations of a style attribute, as written. Invalid ones are dropped, as browsers do, and logged
     * at debug level.
     */
    static List<Entry> parse(Element element, String css) {
        List<Entry> out = new ArrayList<>();
        Document doc = element.ownerDocument();
        for (CssParser.Declaration d : CssParser.parseDeclarations(css)) {
            String name = normalize(d.name());
            List<Decl> decls = Decl.expand(new CssParser.Declaration(name, d.value(), d.important(), 0), doc.url(),
                    doc.host());
            if (decls != null) {
                out.add(new Entry(name, ComponentValue.text(d.value()), d.important(), decls));
            } else {
                doc.host().log(Host.LogLevel.DEBUG, doc.url() + ": invalid declaration in a style attribute '"
                        + d.name() + ": " + ComponentValue.text(d.value()) + "'");
            }
        }
        return out;
    }

    /** A declaration of {@code element}'s inline style, or null when the value is invalid. */
    private static Entry entry(Element element, String name, String value, boolean important) {
        Document doc = element.ownerDocument();
        List<Decl> decls = Decl.expand(new CssParser.Declaration(name, CssParser.parseComponentValues(value),
                important, 0), doc.url(), doc.host());
        return decls == null ? null : new Entry(name, value, important, decls);
    }

    private static String serialize(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(e.name).append(": ").append(e.value).append(e.important ? " !important;" : ";");
        }
        return sb.toString();
    }

    /** Writes the attribute, then installs the parsed result so nothing parses it again. */
    private static void write(Element element, List<Entry> entries) {
        String css = serialize(entries);
        if (css.isEmpty()) element.removeAttribute("style");
        else element.setAttribute("style", css);
        element.parsedInlineStyle = new ElementState(element.getAttribute("style"), List.copyOf(entries));
    }
}
