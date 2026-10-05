package dev.vellum.engine.css;

import dev.vellum.engine.css.ComponentValue.Block;
import dev.vellum.engine.css.ComponentValue.Func;
import dev.vellum.engine.css.Token.Type;

import java.util.ArrayList;
import java.util.List;

/**
 * The CSS Syntax 3 parser (§5): turns tokens into component values, then into rules and declarations, with the
 * spec's error recovery (unclosed blocks close at EOF, a bad declaration is skipped up to the next {@code ;}, a rule
 * without a block is dropped). Interpreting preludes and values is left to {@link Stylesheet} and {@link Properties}.
 */
final class CssParser {
    private CssParser() {}

    /** A qualified rule (selector + block) or an at-rule; {@code block} is null for at-rules ending in {@code ;}. */
    record Rule(String atKeyword, List<ComponentValue> prelude, Block block, int line) {
        boolean isAtRule() { return atKeyword != null; }
    }

    /** A declaration; {@code value} has the {@code !important} flag and surrounding whitespace removed. */
    record Declaration(String name, List<ComponentValue> value, boolean important, int line) {}

    static List<ComponentValue> parseComponentValues(String source) {
        return new TreeBuilder(Tokenizer.tokenize(source)).list(null);
    }

    /** Parses a stylesheet's top-level rules. */
    static List<Rule> parseRules(String source) {
        return parseRules(parseComponentValues(source), true);
    }

    /** Parses a list of rules from component values (a stylesheet, or the block of {@code @media}). */
    static List<Rule> parseRules(List<ComponentValue> values, boolean topLevel) {
        List<Rule> rules = new ArrayList<>();
        int i = 0;
        while (i < values.size()) {
            ComponentValue v = values.get(i);
            if (v instanceof Token t && (t.is(Type.WHITESPACE) || (topLevel && (t.is(Type.CDO) || t.is(Type.CDC))))) {
                i++;
                continue;
            }
            String at = v instanceof Token t && t.is(Type.AT_KEYWORD) ? t.lower : null;
            if (at != null) i++;
            List<ComponentValue> prelude = new ArrayList<>();
            Block block = null;
            for (; i < values.size(); i++) {
                ComponentValue p = values.get(i);
                if (p instanceof Block b && b.open() == '{') {
                    block = b;
                    i++;
                    break;
                }
                if (at != null && p instanceof Token t && t.is(Type.SEMICOLON)) {
                    i++;
                    break;
                }
                prelude.add(p);
            }
            if (block != null || at != null) rules.add(new Rule(at, trim(prelude), block, v.line()));
        }
        return rules;
    }

    static List<Declaration> parseDeclarations(String source) {
        return parseDeclarations(parseComponentValues(source));
    }

    /** Parses a declaration list (a style rule's block or a {@code style} attribute). Nested at-rules are ignored. */
    static List<Declaration> parseDeclarations(List<ComponentValue> values) {
        List<Declaration> out = new ArrayList<>();
        int i = 0;
        while (i < values.size()) {
            int end = i;
            while (end < values.size() && !(values.get(end) instanceof Token t && t.is(Type.SEMICOLON))) end++;
            Declaration d = declaration(values.subList(i, end));
            if (d != null) out.add(d);
            i = end + 1;
        }
        return out;
    }

    private static Declaration declaration(List<ComponentValue> values) {
        List<ComponentValue> v = trim(values);
        if (v.size() < 2 || !(v.get(0) instanceof Token name) || !name.is(Type.IDENT)) return null;
        int colon = 1;
        while (colon < v.size() && v.get(colon) instanceof Token t && t.is(Type.WHITESPACE)) colon++;
        if (colon >= v.size() || !(v.get(colon) instanceof Token c) || !c.is(Type.COLON)) return null;
        List<ComponentValue> value = new ArrayList<>(v.subList(colon + 1, v.size()));
        boolean important = false;
        int last = lastNonWhitespace(value);
        if (last >= 0 && value.get(last) instanceof Token imp && imp.isIdent("important")) {
            int bang = lastNonWhitespace(value.subList(0, last));
            if (bang >= 0 && value.get(bang) instanceof Token b && b.isDelim('!')) {
                important = true;
                value = new ArrayList<>(value.subList(0, bang));
            }
        }
        // Custom property names are case-sensitive; everything else is matched in lower case.
        String n = name.value.startsWith("--") ? name.value : name.lower;
        return new Declaration(n, trim(value), important, name.line());
    }

    private static int lastNonWhitespace(List<ComponentValue> values) {
        for (int i = values.size() - 1; i >= 0; i--) {
            if (!(values.get(i) instanceof Token t && t.is(Type.WHITESPACE))) return i;
        }
        return -1;
    }

    /** The values without leading and trailing whitespace tokens. */
    static List<ComponentValue> trim(List<ComponentValue> values) {
        int from = 0, to = values.size();
        while (from < to && values.get(from) instanceof Token t && t.is(Type.WHITESPACE)) from++;
        while (to > from && values.get(to - 1) instanceof Token t && t.is(Type.WHITESPACE)) to--;
        return List.copyOf(values.subList(from, to));
    }

    /** Groups tokens into functions and blocks (§5.4.7–5.4.9). */
    private static final class TreeBuilder {
        private final List<Token> tokens;
        private int pos;

        TreeBuilder(List<Token> tokens) {
            this.tokens = tokens;
        }

        /** Consumes values up to the token of type {@code close} (consumed) or EOF. */
        List<ComponentValue> list(Type close) {
            List<ComponentValue> out = new ArrayList<>();
            while (pos < tokens.size()) {
                Token t = tokens.get(pos++);
                if (t.type == close) return out;
                out.add(value(t));
            }
            return out;
        }

        private ComponentValue value(Token t) {
            return switch (t.type) {
                case FUNCTION -> {
                    List<ComponentValue> args = list(Type.CLOSE_PAREN);
                    yield new Func(t.lower, args, t.source(), t.start(), end(t), t.line());
                }
                case OPEN_PAREN -> block(t, '(', Type.CLOSE_PAREN);
                case OPEN_SQUARE -> block(t, '[', Type.CLOSE_SQUARE);
                case OPEN_CURLY -> block(t, '{', Type.CLOSE_CURLY);
                default -> t;
            };
        }

        private Block block(Token open, char c, Type close) {
            List<ComponentValue> body = list(close);
            return new Block(c, body, open.source(), open.start(), end(open), open.line());
        }

        /** End offset of a function or block just consumed: after its closing token, or the last token at EOF. */
        private int end(Token open) {
            return pos > 0 ? tokens.get(pos - 1).end() : open.end();
        }
    }
}
