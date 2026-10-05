package dev.vellum.engine.css;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Node;
import dev.vellum.engine.dom.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A complex selector (Selectors 4): compound selectors joined by combinators, with its specificity and optional
 * trailing pseudo-element. Matching runs right to left. Parsed by {@link SelectorParser}.
 */
final class Selector {
    enum PseudoElement { NONE, BEFORE, AFTER, PLACEHOLDER }

    /** Compounds from left to right. */
    final Compound[] compounds;
    /** {@code combinators[i]} joins {@code compounds[i]} and {@code compounds[i + 1]}: ' ', '>', '+' or '~'. */
    final char[] combinators;
    /** Packed (a, b, c) specificity: {@code a << 20 | b << 10 | c}. Packed values compare correctly as ints. */
    final int specificity;
    final PseudoElement pseudoElement;

    Selector(Compound[] compounds, char[] combinators, PseudoElement pseudoElement) {
        this.compounds = compounds;
        this.combinators = combinators;
        this.pseudoElement = pseudoElement;
        int s = pseudoElement == PseudoElement.NONE ? 0 : TYPE;
        for (Compound c : compounds) s += c.specificity();
        this.specificity = s;
    }

    /** Specificity units: an id, a class-like selector (class, attribute, pseudo-class), a type selector. */
    static final int ID = 1 << 20, CLASS = 1 << 10, TYPE = 1;

    /** The rightmost compound, which the rule index keys on. */
    Compound subject() {
        return compounds[compounds.length - 1];
    }

    /** Whether {@code element} matches, ignoring the pseudo-element (the caller decides what that applies to). */
    boolean matches(Element element, MatchContext ctx) {
        return matchAt(element, compounds.length - 1, ctx);
    }

    private boolean matchAt(Element e, int i, MatchContext ctx) {
        if (!compounds[i].matches(e, ctx)) return false;
        if (i == 0) return true;
        switch (combinators[i - 1]) {
            case '>' -> {
                Element p = e.parentElement();
                return p != null && matchAt(p, i - 1, ctx);
            }
            case ' ' -> {
                for (Element p = e.parentElement(); p != null; p = p.parentElement()) {
                    if (matchAt(p, i - 1, ctx)) return true;
                }
                return false;
            }
            default -> {
                // Sibling combinators: walk the preceding element siblings ('+' only looks at the nearest one).
                Siblings siblings = ctx.siblings(e);
                if (siblings == null) return false;
                boolean adjacent = combinators[i - 1] == '+';
                for (int k = ctx.position(e) - 1; k >= 0; k--) {
                    if (matchAt(siblings.elements[k], i - 1, ctx)) return true;
                    if (adjacent) return false;
                }
                return false;
            }
        }
    }

    /**
     * State for one matching pass (a restyle, a query): {@code :scope}, the anchor of the {@code :has()} being
     * evaluated, whether interaction state was read, and each parent's element children with their positions, found
     * on first use. The DOM must not change while a context is in use.
     */
    static final class MatchContext {
        final Element scope;
        Element anchor;
        /** Set when a selector read hover, active or focus state; the style engine resets it per element. */
        boolean interactionRead;
        private final IdentityHashMap<Node, Siblings> siblings = new IdentityHashMap<>();
        private final IdentityHashMap<Element, Integer> positions = new IdentityHashMap<>();

        MatchContext(Element scope) {
            this.scope = scope;
        }

        /** The element children of {@code e}'s parent, or null without a parent. */
        Siblings siblings(Element e) {
            Node parent = e.parentNode();
            if (parent == null) return null;
            Siblings s = siblings.get(parent);
            if (s == null) {
                List<Element> elements = new ArrayList<>(parent.childCount());
                for (int i = 0, n = parent.childCount(); i < n; i++) {
                    if (parent.childAt(i) instanceof Element c) {
                        positions.put(c, elements.size());
                        elements.add(c);
                    }
                }
                siblings.put(parent, s = new Siblings(elements.toArray(Element[]::new)));
            }
            return s;
        }

        /** {@code e}'s 0-based index in {@link #siblings}, which must have been called for it. */
        int position(Element e) {
            return positions.get(e);
        }
    }

    /** A parent's element children in order, with their positions among same-type siblings (built on demand). */
    static final class Siblings {
        final Element[] elements;
        private int[] ofType, ofTypeFromEnd;

        Siblings(Element[] elements) {
            this.elements = elements;
        }

        /** The 1-based position of {@code elements[i]} among the siblings with its tag name. */
        int typePosition(int i, boolean fromEnd) {
            if (ofType == null) {
                int n = elements.length;
                ofType = new int[n];
                ofTypeFromEnd = new int[n];
                Map<String, Integer> counts = new HashMap<>();
                for (int k = 0; k < n; k++) ofType[k] = counts.merge(elements[k].tagName(), 1, Integer::sum);
                for (int k = 0; k < n; k++) ofTypeFromEnd[k] = counts.get(elements[k].tagName()) - ofType[k] + 1;
            }
            return fromEnd ? ofTypeFromEnd[i] : ofType[i];
        }
    }

    /** A compound selector: an optional type selector plus simple selectors, all of which must match. */
    record Compound(String tag, String id, String firstClass, Simple[] simples) {
        boolean matches(Element e, MatchContext ctx) {
            if (tag != null && !tag.equals(e.tagName())) return false;
            for (Simple s : simples) if (!s.matches(e, ctx)) return false;
            return true;
        }

        int specificity() {
            int s = tag == null ? 0 : TYPE;
            for (Simple simple : simples) s += simple.specificity();
            return s;
        }
    }

    /** A simple selector other than a type selector. */
    sealed interface Simple {
        boolean matches(Element e, MatchContext ctx);

        int specificity();
    }

    record IdSelector(String id) implements Simple {
        @Override public boolean matches(Element e, MatchContext ctx) { return id.equals(e.getAttribute("id")); }
        @Override public int specificity() { return ID; }
    }

    record ClassSelector(String name) implements Simple {
        @Override public boolean matches(Element e, MatchContext ctx) { return e.hasClass(name); }
        @Override public int specificity() { return CLASS; }
    }

    /** {@code [name]}, {@code [name op value]} with op one of = ~= |= ^= $= *=, and the {@code i} flag. */
    record AttributeSelector(String name, char op, String value, boolean ignoreCase) implements Simple {
        @Override
        public boolean matches(Element e, MatchContext ctx) {
            String actual = e.getAttribute(name);
            if (actual == null) return false;
            String v = value;
            int n = actual.length(), len = v == null ? 0 : v.length();
            return switch (op) {
                case 0 -> true;
                case '=' -> n == len && actual.regionMatches(ignoreCase, 0, v, 0, len);
                case '~' -> len > 0 && indexOfWhitespace(v, 0) < 0 && hasToken(actual, v, ignoreCase);
                case '|' -> (n == len || n > len && actual.charAt(len) == '-') && actual.regionMatches(ignoreCase, 0, v, 0, len);
                case '^' -> len > 0 && actual.regionMatches(ignoreCase, 0, v, 0, len);
                case '$' -> len > 0 && n >= len && actual.regionMatches(ignoreCase, n - len, v, 0, len);
                case '*' -> len > 0 && (ignoreCase ? containsIgnoringCase(actual, v) : actual.contains(v));
                default -> false;
            };
        }

        @Override public int specificity() { return CLASS; }
    }

    /** Whether the whitespace-separated {@code list} contains {@code token} (which has no whitespace). */
    static boolean hasToken(String list, String token, boolean ignoreCase) {
        int n = list.length(), len = token.length();
        for (int start = 0; start < n; ) {
            int end = indexOfWhitespace(list, start);
            if (end < 0) end = n;
            if (end - start == len && list.regionMatches(ignoreCase, start, token, 0, len)) return true;
            start = end + 1;
        }
        return false;
    }

    private static boolean containsIgnoringCase(String s, String part) {
        for (int i = 0, last = s.length() - part.length(); i <= last; i++) {
            if (s.regionMatches(true, i, part, 0, part.length())) return true;
        }
        return false;
    }

    /** The index of the first CSS whitespace character at or after {@code from}, or -1. */
    private static int indexOfWhitespace(String s, int from) {
        for (int i = from, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f') return i;
        }
        return -1;
    }

    /** Pseudo-classes without arguments. */
    enum PseudoClass implements Simple {
        HOVER(true), ACTIVE(true), FOCUS(true), FOCUS_VISIBLE(true), FOCUS_WITHIN(true), CHECKED, DISABLED, ENABLED,
        EMPTY, ROOT, FIRST_CHILD, LAST_CHILD, ONLY_CHILD, FIRST_OF_TYPE, LAST_OF_TYPE, ONLY_OF_TYPE,
        PLACEHOLDER_SHOWN, OPEN, SCOPE, ANY_LINK;

        /** Reads interaction state (hover, active, focus), which changes without the DOM changing. */
        private final boolean interaction;

        PseudoClass() {
            this(false);
        }

        PseudoClass(boolean interaction) {
            this.interaction = interaction;
        }

        @Override
        public boolean matches(Element e, MatchContext ctx) {
            if (interaction) ctx.interactionRead = true;
            return switch (this) {
                case HOVER -> e.isHovered();
                case ACTIVE -> e.isActive();
                case FOCUS -> e.isFocused();
                case FOCUS_VISIBLE -> e.isFocused() && (isTextField(e) || e.ownerDocument().input().focusVisible());
                case FOCUS_WITHIN -> {
                    Element f = e.ownerDocument() == null ? null : e.ownerDocument().focusedElement();
                    yield f != null && e.contains(f);
                }
                case CHECKED -> switch (e.tagName()) {
                    case "input" -> isCheckable(e) && e.checked();
                    case "option" -> e.hasAttribute("selected");
                    default -> false;
                };
                case DISABLED -> isFormControl(e) && e.isDisabled();
                case ENABLED -> isFormControl(e) && !e.isDisabled();
                case EMPTY -> isEmpty(e);
                case ROOT -> e.parentNode() instanceof Document;
                case FIRST_CHILD -> Nth.position(e, false, false, null, ctx) == 1;
                case LAST_CHILD -> Nth.position(e, true, false, null, ctx) == 1;
                case ONLY_CHILD -> FIRST_CHILD.matches(e, ctx) && LAST_CHILD.matches(e, ctx);
                case FIRST_OF_TYPE -> Nth.position(e, false, true, null, ctx) == 1;
                case LAST_OF_TYPE -> Nth.position(e, true, true, null, ctx) == 1;
                case ONLY_OF_TYPE -> FIRST_OF_TYPE.matches(e, ctx) && LAST_OF_TYPE.matches(e, ctx);
                case PLACEHOLDER_SHOWN -> (e.tagName().equals("input") || e.tagName().equals("textarea"))
                        && e.hasAttribute("placeholder") && e.value().isEmpty();
                case OPEN -> (e.tagName().equals("details") || e.tagName().equals("dialog")) && e.hasAttribute("open");
                case SCOPE -> e == ctx.scope;
                case ANY_LINK -> e.tagName().equals("a") && e.hasAttribute("href");
            };
        }

        @Override public int specificity() { return CLASS; }

        private static boolean isEmpty(Element e) {
            for (int i = 0, n = e.childCount(); i < n; i++) {
                Node c = e.childAt(i);
                if (c instanceof Element || (c instanceof Text t && !t.data().isEmpty())) return false;
            }
            return true;
        }

        private static boolean isFormControl(Element e) {
            return switch (e.tagName()) {
                case "button", "input", "select", "textarea", "option", "optgroup", "fieldset" -> true;
                default -> false;
            };
        }

        private static boolean isCheckable(Element e) {
            String type = e.getAttribute("type");
            return "checkbox".equalsIgnoreCase(type) || "radio".equalsIgnoreCase(type);
        }

        /** Text fields show their focus ring for pointer focus too, as in browsers. */
        private static boolean isTextField(Element e) {
            if (e.tagName().equals("textarea")) return true;
            if (!e.tagName().equals("input")) return false;
            String type = e.getAttribute("type");
            return type == null || switch (type.toLowerCase(Locale.ROOT)) {
                case "text", "password", "number", "search", "email", "url", "tel" -> true;
                default -> false;
            };
        }
    }

    /**
     * {@code :nth-child(an+b [of S])}, {@code :nth-last-child}, {@code :nth-of-type}, {@code :nth-last-of-type}.
     */
    record Nth(int a, int b, boolean last, boolean ofType, List<Selector> of) implements Simple {
        @Override
        public boolean matches(Element e, MatchContext ctx) {
            if (of != null && !SelectorParser.matchesAny(of, e, ctx)) return false;
            int pos = position(e, last, ofType, of, ctx);
            if (a == 0) return pos == b;
            int diff = pos - b;
            return diff % a == 0 && diff / a >= 0;
        }

        @Override
        public int specificity() {
            return CLASS + (of == null ? 0 : SelectorParser.maxSpecificity(of));
        }

        /** 1-based position of {@code e} among its element siblings that are of the same type / match {@code of}. */
        static int position(Element e, boolean fromEnd, boolean ofType, List<Selector> of, MatchContext ctx) {
            Siblings siblings = ctx.siblings(e);
            if (siblings == null) return 1;
            Element[] all = siblings.elements;
            int i = ctx.position(e);
            if (ofType) return siblings.typePosition(i, fromEnd);
            if (of == null) return fromEnd ? all.length - i : i + 1;
            int pos = 1; // e itself (the caller has checked it matches `of`)
            for (int k = fromEnd ? i + 1 : i - 1; k >= 0 && k < all.length; k += fromEnd ? 1 : -1) {
                if (SelectorParser.matchesAny(of, all[k], ctx)) pos++;
            }
            return pos;
        }
    }

    /** {@code :not()}, {@code :is()} and {@code :where()} (which contributes no specificity). */
    record Logical(boolean negate, boolean zeroSpecificity, List<Selector> selectors) implements Simple {
        @Override
        public boolean matches(Element e, MatchContext ctx) {
            return SelectorParser.matchesAny(selectors, e, ctx) != negate;
        }

        @Override
        public int specificity() {
            return zeroSpecificity ? 0 : SelectorParser.maxSpecificity(selectors);
        }
    }

    /**
     * {@code :has(<relative selectors>)}. Each relative selector starts with an {@link #ANCHOR} compound standing
     * for the element being tested, so {@code :has(> .a)} is stored as {@code <anchor> > .a}.
     */
    record Has(List<Selector> relative) implements Simple {
        static final Compound ANCHOR = new Compound(null, null, null, new Simple[] {Anchor.INSTANCE});

        @Override
        public boolean matches(Element e, MatchContext ctx) {
            Element saved = ctx.anchor;
            ctx.anchor = e;
            try {
                for (Selector s : relative) {
                    char first = s.combinators[0];
                    if (first == ' ' || first == '>') {
                        if (anyDescendantMatches(e, s, ctx)) return true;
                    } else {
                        Siblings siblings = ctx.siblings(e);
                        if (siblings == null) continue;
                        // Following siblings and their subtrees: the subject may sit below a later sibling.
                        Element[] all = siblings.elements;
                        for (int k = ctx.position(e) + 1; k < all.length; k++) {
                            if (s.matches(all[k], ctx) || anyDescendantMatches(all[k], s, ctx)) return true;
                        }
                    }
                }
                return false;
            } finally {
                ctx.anchor = saved;
            }
        }

        private static boolean anyDescendantMatches(Element root, Selector s, MatchContext ctx) {
            for (int i = 0, n = root.childCount(); i < n; i++) {
                if (root.childAt(i) instanceof Element c && (s.matches(c, ctx) || anyDescendantMatches(c, s, ctx))) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public int specificity() {
            return SelectorParser.maxSpecificity(relative);
        }
    }

    /** Matches the anchor of the enclosing {@code :has()}. */
    enum Anchor implements Simple {
        INSTANCE;

        @Override public boolean matches(Element e, MatchContext ctx) { return e == ctx.anchor; }
        @Override public int specificity() { return 0; }
    }
}
