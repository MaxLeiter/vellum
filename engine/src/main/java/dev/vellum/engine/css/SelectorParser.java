package dev.vellum.engine.css;

import dev.vellum.engine.css.ComponentValue.Block;
import dev.vellum.engine.css.ComponentValue.Func;
import dev.vellum.engine.css.Selector.AttributeSelector;
import dev.vellum.engine.css.Selector.ClassSelector;
import dev.vellum.engine.css.Selector.Compound;
import dev.vellum.engine.css.Selector.Has;
import dev.vellum.engine.css.Selector.IdSelector;
import dev.vellum.engine.css.Selector.Logical;
import dev.vellum.engine.css.Selector.MatchContext;
import dev.vellum.engine.css.Selector.Nth;
import dev.vellum.engine.css.Selector.PseudoClass;
import dev.vellum.engine.css.Selector.PseudoElement;
import dev.vellum.engine.css.Selector.Simple;
import dev.vellum.engine.css.Token.Type;
import dev.vellum.engine.dom.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses selector lists (Selectors 4 subset, see DESIGN §5). Errors throw {@link IllegalArgumentException}. */
final class SelectorParser {
    private static final Pattern AN_PLUS_B = Pattern.compile("([+-]?\\d*)n([+-]\\d+)?|([+-]?\\d+)");

    private final List<ComponentValue> in;
    private int pos;
    /** The pseudo-element of the complex selector being parsed. */
    private PseudoElement pseudo;

    private SelectorParser(List<ComponentValue> in) {
        this.in = in;
    }

    static List<Selector> parse(String text) {
        return parse(CssParser.parseComponentValues(text));
    }

    static List<Selector> parse(List<ComponentValue> values) {
        return new SelectorParser(values).list(false, false);
    }

    static boolean matchesAny(List<Selector> selectors, Element e, MatchContext ctx) {
        for (Selector s : selectors) if (s.pseudoElement == PseudoElement.NONE && s.matches(e, ctx)) return true;
        return false;
    }

    static int maxSpecificity(List<Selector> selectors) {
        int max = 0;
        for (Selector s : selectors) max = Math.max(max, s.specificity);
        return max;
    }

    /**
     * A comma-separated list. {@code relative} selectors (inside {@code :has}) may start with a combinator;
     * {@code forgiving} lists (inside {@code :is}/{@code :where}) drop invalid entries instead of failing.
     */
    private List<Selector> list(boolean relative, boolean forgiving) {
        List<Selector> out = new ArrayList<>();
        while (true) {
            int start = pos;
            try {
                out.add(complex(relative));
            } catch (IllegalArgumentException ex) {
                if (!forgiving) throw ex;
                pos = start;
                while (pos < in.size() && !isToken(peek(), Type.COMMA)) pos++;
            }
            skipWhitespace();
            if (pos >= in.size()) break;
            if (!isToken(peek(), Type.COMMA)) throw error("Unexpected " + peek().text());
            pos++;
        }
        if (out.isEmpty() && !forgiving) throw error("Empty selector");
        return out;
    }

    private Selector complex(boolean relative) {
        List<Compound> compounds = new ArrayList<>();
        StringBuilder combinators = new StringBuilder();
        pseudo = PseudoElement.NONE;
        skipWhitespace();
        if (relative) {
            compounds.add(Has.ANCHOR);
            char c = explicitCombinator();
            combinators.append(c == 0 ? ' ' : c);
        }
        while (true) {
            if (pseudo != PseudoElement.NONE) throw error("Pseudo-element must be last");
            compounds.add(compound());
            boolean ws = skipWhitespace();
            if (pos >= in.size() || isToken(peek(), Type.COMMA)) break;
            char c = explicitCombinator();
            if (c == 0 && !ws) throw error("Unexpected " + peek().text());
            combinators.append(c == 0 ? ' ' : c);
        }
        return new Selector(compounds.toArray(Compound[]::new), combinators.toString().toCharArray(), pseudo);
    }

    /** Consumes {@code > + ~} (and surrounding whitespace); returns 0 when there is none. */
    private char explicitCombinator() {
        skipWhitespace();
        if (pos < in.size() && peek() instanceof Token t && (t.isDelim('>') || t.isDelim('+') || t.isDelim('~'))) {
            pos++;
            skipWhitespace();
            return t.value.charAt(0);
        }
        return 0;
    }

    private Compound compound() {
        String tag = null, id = null, firstClass = null;
        List<Simple> simples = new ArrayList<>();
        int start = pos;
        if (peek() instanceof Token t && (t.is(Type.IDENT) || t.isDelim('*'))) {
            pos++;
            if (t.is(Type.IDENT)) tag = t.lower;
            if (pos < in.size() && peek() instanceof Token bar && bar.isDelim('|')) throw error("Namespaces are not supported");
        }
        while (pos < in.size()) {
            ComponentValue v = peek();
            if (v instanceof Token t && t.is(Type.HASH)) {
                if (!t.flag) throw error("Invalid id selector #" + t.value);
                pos++;
                if (id == null) id = t.value;
                simples.add(new IdSelector(t.value));
            } else if (v instanceof Token t && t.isDelim('.')) {
                pos++;
                if (!(next() instanceof Token name) || !name.is(Type.IDENT)) throw error("Expected a class name");
                if (firstClass == null) firstClass = name.value;
                simples.add(new ClassSelector(name.value));
            } else if (v instanceof Block b && b.open() == '[') {
                pos++;
                simples.add(attribute(b.body()));
            } else if (v instanceof Token t && t.is(Type.COLON)) {
                pos++;
                if (pseudo != PseudoElement.NONE) throw error("Pseudo-classes after a pseudo-element");
                if (pos < in.size() && peek() instanceof Token c2 && c2.is(Type.COLON)) {
                    pos++;
                    pseudo = pseudoElement(next(), true);
                } else {
                    ComponentValue p = next();
                    PseudoElement legacy = p instanceof Token pt && pt.is(Type.IDENT)
                            && (pt.lower.equals("before") || pt.lower.equals("after")) ? pseudoElement(p, false) : null;
                    if (legacy != null) pseudo = legacy;
                    else simples.add(pseudoClass(p));
                }
            } else {
                break;
            }
        }
        if (pos == start) throw error(pos < in.size() ? "Unexpected " + peek().text() : "Expected a selector");
        return new Compound(tag, id, firstClass, simples.toArray(Simple[]::new));
    }

    private PseudoElement pseudoElement(ComponentValue v, boolean doubleColon) {
        if (v instanceof Token t && t.is(Type.IDENT)) {
            switch (t.lower) {
                case "before" -> { return PseudoElement.BEFORE; }
                case "after" -> { return PseudoElement.AFTER; }
                case "placeholder" -> { if (doubleColon) return PseudoElement.PLACEHOLDER; }
                default -> { }
            }
        }
        throw error("Unknown pseudo-element " + (v == null ? "" : v.text()));
    }

    private Simple pseudoClass(ComponentValue v) {
        if (v instanceof Token t && t.is(Type.IDENT)) {
            String name = t.lower.equals("link") ? "any-link" : t.lower;
            try {
                return PseudoClass.valueOf(name.toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException ex) {
                throw error("Unknown pseudo-class :" + t.value);
            }
        }
        if (v instanceof Func f) {
            return switch (f.name()) {
                case "not" -> new Logical(true, false, nested(f, false, false));
                case "is", "matches", "any" -> new Logical(false, false, nested(f, false, true));
                case "where" -> new Logical(false, true, nested(f, false, true));
                case "has" -> new Has(nested(f, true, false));
                case "nth-child" -> nth(f, false, false);
                case "nth-last-child" -> nth(f, true, false);
                case "nth-of-type" -> nth(f, false, true);
                case "nth-last-of-type" -> nth(f, true, true);
                default -> throw error("Unknown pseudo-class :" + f.name() + "()");
            };
        }
        throw error("Expected a pseudo-class");
    }

    private static List<Selector> nested(Func f, boolean relative, boolean forgiving) {
        return new SelectorParser(f.args()).list(relative, forgiving);
    }

    private Nth nth(Func f, boolean last, boolean ofType) {
        List<ComponentValue> args = f.args();
        int of = -1;
        for (int i = 0; i < args.size(); i++) if (args.get(i) instanceof Token t && t.isIdent("of")) of = i;
        if (of >= 0 && ofType) throw error("'of' is only allowed in :nth-child");
        String expr = ComponentValue.text(CssParser.trim(of < 0 ? args : args.subList(0, of)))
                .replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        int a, b;
        if (expr.equals("odd")) { a = 2; b = 1; }
        else if (expr.equals("even")) { a = 2; b = 0; }
        else {
            Matcher m = AN_PLUS_B.matcher(expr);
            if (!m.matches()) throw error("Invalid An+B: " + expr);
            if (m.group(3) != null) {
                a = 0;
                b = Integer.parseInt(m.group(3).replace("+", ""));
            } else {
                String as = m.group(1).replace("+", "");
                a = as.isEmpty() ? 1 : as.equals("-") ? -1 : Integer.parseInt(as);
                b = m.group(2) == null ? 0 : Integer.parseInt(m.group(2).replace("+", ""));
            }
        }
        List<Selector> ofList = of < 0 ? null : new SelectorParser(args.subList(of + 1, args.size())).list(false, false);
        return new Nth(a, b, last, ofType, ofList);
    }

    private AttributeSelector attribute(List<ComponentValue> body) {
        SelectorParser p = new SelectorParser(body);
        p.skipWhitespace();
        if (!(p.next() instanceof Token name) || !name.is(Type.IDENT)) throw error("Expected an attribute name");
        p.skipWhitespace();
        if (p.pos >= body.size()) return new AttributeSelector(name.lower, (char) 0, null, false);
        char op = 0;
        if (p.next() instanceof Token t && t.is(Type.DELIM)) {
            char c = t.value.charAt(0);
            if (c == '=') op = '=';
            else if ("~|^$*".indexOf(c) >= 0 && p.next() instanceof Token eq && eq.isDelim('=')) op = c;
        }
        if (op == 0) throw error("Invalid attribute selector");
        p.skipWhitespace();
        if (!(p.next() instanceof Token value) || !(value.is(Type.IDENT) || value.is(Type.STRING))) {
            throw error("Expected an attribute value");
        }
        p.skipWhitespace();
        boolean ignoreCase = false;
        if (p.pos < body.size()) {
            if (!(p.next() instanceof Token flag) || !(flag.isIdent("i") || flag.isIdent("s"))) {
                throw error("Invalid attribute selector");
            }
            ignoreCase = flag.isIdent("i");
            p.skipWhitespace();
            if (p.pos < body.size()) throw error("Invalid attribute selector");
        }
        return new AttributeSelector(name.lower, op, value.value, ignoreCase);
    }

    // ---- Cursor ----

    private ComponentValue peek() {
        return pos < in.size() ? in.get(pos) : null;
    }

    private ComponentValue next() {
        return pos < in.size() ? in.get(pos++) : null;
    }

    private boolean skipWhitespace() {
        boolean any = false;
        while (pos < in.size() && isToken(in.get(pos), Type.WHITESPACE)) {
            pos++;
            any = true;
        }
        return any;
    }

    private static boolean isToken(ComponentValue v, Type type) {
        return v instanceof Token t && t.is(type);
    }

    private static IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message);
    }
}
