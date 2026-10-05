package dev.vellum.engine.css;

import dev.vellum.engine.css.Selector.MatchContext;
import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Node;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Selector matching for the DOM API (matches, closest, querySelector, querySelectorAll). Parsed selectors are kept
 * in a small LRU cache, since scripts tend to query the same few selectors over and over.
 */
public final class Selectors {
    private static final int CACHE_SIZE = 128;
    private static final Map<String, List<Selector>> CACHE = new LinkedHashMap<>(CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<Selector>> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    private Selectors() {}

    /** @throws IllegalArgumentException for an invalid selector (scripts see a SyntaxError) */
    public static boolean matches(Element element, String selector) {
        return SelectorParser.matchesAny(parse(selector), element, new MatchContext(element));
    }

    /** First descendant of {@code scope} (not scope itself) matching, in document order, or null. */
    public static Element querySelector(Node scope, String selector) {
        List<Element> found = query(scope, selector, true);
        return found.isEmpty() ? null : found.get(0);
    }

    /** All descendants of {@code scope} matching, in document order. */
    public static List<Element> querySelectorAll(Node scope, String selector) {
        return query(scope, selector, false);
    }

    private static List<Element> query(Node scope, String selector, boolean firstOnly) {
        List<Selector> selectors = parse(selector);
        Element scopeElement = scope instanceof Element e ? e : scope instanceof Document d ? d.documentElement() : null;
        List<Element> out = new ArrayList<>(firstOnly ? 1 : 8);
        collect(scope, selectors, new MatchContext(scopeElement), out, firstOnly);
        return out;
    }

    private static boolean collect(Node node, List<Selector> selectors, MatchContext ctx, List<Element> out,
                                   boolean firstOnly) {
        for (int i = 0, n = node.childCount(); i < n; i++) {
            if (!(node.childAt(i) instanceof Element e)) continue;
            if (SelectorParser.matchesAny(selectors, e, ctx)) {
                out.add(e);
                if (firstOnly) return true;
            }
            if (collect(e, selectors, ctx, out, firstOnly)) return true;
        }
        return false;
    }

    private static List<Selector> parse(String selector) {
        synchronized (CACHE) {
            List<Selector> cached = CACHE.get(selector);
            if (cached != null) return cached;
        }
        List<Selector> parsed = SelectorParser.parse(selector);
        synchronized (CACHE) {
            CACHE.put(selector, parsed);
        }
        return parsed;
    }
}
