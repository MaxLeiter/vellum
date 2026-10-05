package dev.vellum.engine.input;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.input.Narration.Announcement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Finds what the page's live regions have to say: an element with {@code aria-live="polite"} or {@code "assertive"},
 * or with {@code role="status"}, {@code "log"} (both polite) or {@code "alert"} (assertive) unless its
 * {@code aria-live} says otherwise. When a region's text ({@link Accessibility#lines}, without the regions inside it,
 * which speak for themselves) differs from what it had at the last {@link #poll}, its new text is announced; a log
 * announces only its new entries (its children's texts). A region the page did not have before announces its text,
 * so a page opens reading its regions after its title (a log, its last entry only).
 *
 * <p>Comparing at polls, once a frame, is the debounce: a script that changes a region several times in a frame
 * announces it once. No work is done while nothing changed in the document ({@link Document#domVersion}).
 */
final class LiveRegions {
    private enum Politeness { OFF, POLITE, ASSERTIVE }

    /** What a region said at the last poll: its lines, or for a log its entries. */
    private record Said(List<String> parts) {}

    private final Document document;
    /** {@link Document#domVersion} at the last poll; regions only change with the document. */
    private int version = -1;
    private Map<Element, Said> regions = new HashMap<>();

    LiveRegions(Document document) {
        this.document = document;
    }

    /** What the regions announce since the last call, in document order. */
    List<Announcement> poll() {
        if (document.domVersion() == version) return List.of();
        version = document.domVersion();
        Map<Element, Said> now = new HashMap<>();
        List<Announcement> out = new ArrayList<>();
        Element root = document.documentElement();
        if (root != null) visit(root, now, out);
        regions = now;
        return out;
    }

    private void visit(Element e, Map<Element, Said> now, List<Announcement> out) {
        if ("true".equalsIgnoreCase(e.getAttribute("aria-hidden")) || e.hasInertContent()) return;
        Politeness politeness = politeness(e);
        if (politeness != Politeness.OFF) {
            boolean log = "log".equals(Accessibility.firstToken(e.getAttribute("role")));
            Predicate<Element> nested = n -> n != e && politeness(n) != Politeness.OFF;
            Said said = new Said(log ? entries(e, nested) : Accessibility.lines(e, nested));
            Said before = regions.get(e);
            String text = Accessibility.join(log ? added(before, said.parts) : changed(before, said.parts));
            if (!text.isEmpty()) out.add(new Announcement(e, text, politeness == Politeness.ASSERTIVE));
            now.put(e, said);
        }
        for (int i = 0, n = e.childCount(); i < n; i++) {
            if (e.childAt(i) instanceof Element child) visit(child, now, out);
        }
    }

    /** A region's text when it changed (all of it, as a new region's), else nothing. */
    private static List<String> changed(Said before, List<String> lines) {
        return before != null && before.parts.equals(lines) ? List.of() : lines;
    }

    /**
     * The entries a log gained: those after the ones it kept from the start; else, when old entries scrolled off its
     * top, those after the overlap of its old end with its new start; else those after the entries it kept. A new log,
     * or one with nothing in common with its last entries, announces its last entry.
     */
    private static List<String> added(Said before, List<String> entries) {
        if (entries.isEmpty()) return List.of();
        List<String> old = before == null ? List.of() : before.parts;
        int kept = 0;
        while (kept < old.size() && kept < entries.size() && old.get(kept).equals(entries.get(kept))) kept++;
        if (kept == old.size() && before != null) return entries.subList(kept, entries.size());
        for (int overlap = Math.min(old.size(), entries.size()); overlap > 0; overlap--) {
            if (old.subList(old.size() - overlap, old.size()).equals(entries.subList(0, overlap))) {
                return entries.subList(overlap, entries.size());
            }
        }
        if (kept > 0) return entries.subList(kept, entries.size());
        return entries.subList(entries.size() - 1, entries.size());
    }

    /** A log's entries: the text of each child (an element, or loose text), leaving out what is not read. */
    private static List<String> entries(Element log, Predicate<Element> nested) {
        List<String> entries = new ArrayList<>();
        for (int i = 0, n = log.childCount(); i < n; i++) {
            String text = Accessibility.join(Accessibility.contentLines(log.childAt(i), nested));
            if (!text.isEmpty()) entries.add(text);
        }
        return entries;
    }

    private static Politeness politeness(Element e) {
        String live = e.getAttribute("aria-live");
        if (live != null) {
            switch (live.strip().toLowerCase(Locale.ROOT)) {
                case "assertive" -> { return Politeness.ASSERTIVE; }
                case "polite" -> { return Politeness.POLITE; }
                case "off" -> { return Politeness.OFF; }
                default -> { } // not a value: the role decides
            }
        }
        String role = Accessibility.firstToken(e.getAttribute("role"));
        if (role == null) return Politeness.OFF;
        return switch (role) {
            case "alert" -> Politeness.ASSERTIVE;
            case "status", "log" -> Politeness.POLITE;
            default -> Politeness.OFF;
        };
    }
}
