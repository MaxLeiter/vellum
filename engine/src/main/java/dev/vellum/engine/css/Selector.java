package dev.vellum.engine.css;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Node;
import dev.vellum.engine.dom.Text;

import java.util.List;
import java.util.Locale;

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
                Node parent = e.parentNode();
                if (parent == null) return false;
                boolean adjacent = combinators[i - 1] == '+';
                for (int k = indexIn(parent, e) - 1; k >= 0; k--) {
                    if (parent.childAt(k) instanceof Element s) {
                        if (matchAt(s, i - 1, ctx)) return true;
                        if (adjacent) return false;
                    }
                }
                return false;
            }
        }
    }

    private static int indexIn(Node parent, Node child) {
        for (int i = 0, n = parent.childCount(); i < n; i++) if (parent.childAt(i) == child) return i;
        return -1;
    }

    /** Scope for matching: {@code :scope}, and the anchor element of the {@code :has()} being evaluated. */
    static final class MatchContext {
        final Element scope;
        Element anchor;

        MatchContext(Element scope) {
            this.scope = scope;
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
            if (op == 0) return true;
            String v = value;
            if (ignoreCase) {
                actual = actual.toLowerCase(Locale.ROOT);
                v = v.toLowerCase(Locale.ROOT);
            }
            return switch (op) {
                case '=' -> actual.equals(v);
                case '~' -> !v.isEmpty() && !v.contains(" ") && List.of(actual.trim().split("\\s+")).contains(v);
                case '|' -> actual.equals(v) || actual.startsWith(v + "-");
                case '^' -> !v.isEmpty() && actual.startsWith(v);
                case '$' -> !v.isEmpty() && actual.endsWith(v);
                case '*' -> !v.isEmpty() && actual.contains(v);
                default -> false;
            };
        }

        @Override public int specificity() { return CLASS; }
    }

    /** Pseudo-classes without arguments. */
    enum PseudoClass implements Simple {
        HOVER, ACTIVE, FOCUS, FOCUS_VISIBLE, FOCUS_WITHIN, CHECKED, DISABLED, ENABLED, EMPTY, ROOT, FIRST_CHILD,
        LAST_CHILD, ONLY_CHILD, FIRST_OF_TYPE, LAST_OF_TYPE, ONLY_OF_TYPE, PLACEHOLDER_SHOWN, OPEN, SCOPE, ANY_LINK;

        @Override
        public boolean matches(Element e, MatchContext ctx) {
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
            Node parent = e.parentNode();
            if (parent == null) return 1;
            int n = parent.childCount(), pos = 0;
            for (int k = 0; k < n; k++) {
                Node c = parent.childAt(fromEnd ? n - 1 - k : k);
                if (!(c instanceof Element s)) continue;
                if (ofType && !s.tagName().equals(e.tagName())) continue;
                if (of != null && s != e && !SelectorParser.matchesAny(of, s, ctx)) continue;
                pos++;
                if (s == e) return pos;
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
                        Node parent = e.parentNode();
                        if (parent == null) continue;
                        // Following siblings and their subtrees: the subject may sit below a later sibling.
                        for (int k = indexIn(parent, e) + 1, n = parent.childCount(); k < n; k++) {
                            if (parent.childAt(k) instanceof Element sib
                                    && (s.matches(sib, ctx) || anyDescendantMatches(sib, s, ctx))) return true;
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
