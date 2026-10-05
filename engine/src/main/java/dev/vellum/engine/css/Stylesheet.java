package dev.vellum.engine.css;

import dev.vellum.engine.css.ComponentValue.Block;
import dev.vellum.engine.css.ComponentValue.Func;
import dev.vellum.engine.css.Token.Type;
import dev.vellum.engine.host.Host;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A parsed stylesheet: style rules with expanded declarations, {@code @media} blocks, {@code @import}s (loaded by
 * the style engine) and {@code @keyframes}. {@code @supports} is decided while parsing, {@code @layer} blocks are
 * flattened (layer order is not modelled), and other at-rules ({@code @font-face}, {@code @page}...) are skipped.
 */
final class Stylesheet {
    sealed interface Rule permits StyleRule, MediaRule, ImportRule, KeyframesRule {}

    /** A style rule; each selector of its list is matched separately (they may differ in specificity). */
    record StyleRule(List<Selector> selectors, List<Decl> decls) implements Rule {}

    record MediaRule(MediaQuery media, List<Rule> rules) implements Rule {}

    /** An {@code @import}; {@code url} is resolved against the importing sheet. */
    record ImportRule(String url, MediaQuery media) implements Rule {}

    record KeyframesRule(String name, List<Keyframe> keyframes) implements Rule {}

    /** One keyframe block: its offsets (0..1, a block may list several) and declarations. */
    record Keyframe(List<Float> offsets, List<Decl> decls) {}

    /** The URL that relative URLs in this sheet resolve against. */
    final String url;
    final List<Rule> rules;

    private Stylesheet(String url, List<Rule> rules) {
        this.url = url;
        this.rules = rules;
    }

    /**
     * Parses a stylesheet. Problems (invalid selectors, declarations, at-rules) are reported to {@code log} as
     * "line: message" and skipped, per CSS error handling.
     */
    static Stylesheet parse(String css, String url, Host host, Consumer<String> log) {
        Builder b = new Builder(url, host, log);
        return new Stylesheet(url, b.rules(CssParser.parseRules(css), true));
    }

    /** Parses a declaration list (a {@code style} attribute) into longhand declarations. */
    static List<Decl> declarations(String css, String url, Host host, Consumer<String> log) {
        return new Builder(url, host, log).declarations(CssParser.parseComponentValues(css), false);
    }

    private record Builder(String url, Host host, Consumer<String> log) {
        List<Rule> rules(List<CssParser.Rule> raw, boolean topLevel) {
            List<Rule> out = new ArrayList<>();
            boolean importsAllowed = topLevel;
            for (CssParser.Rule r : raw) {
                if (!r.isAtRule()) {
                    importsAllowed = false;
                    styleRule(r, out);
                    continue;
                }
                String name = r.atKeyword();
                if (!name.equals("import") && !name.equals("charset") && !name.equals("layer")) importsAllowed = false;
                switch (name) {
                    case "media" -> {
                        if (r.block() != null) out.add(new MediaRule(MediaQuery.parse(r.prelude()), nested(r)));
                    }
                    case "supports" -> {
                        Predicate<Void> condition = Conditions.parse(r.prelude(), this::supports);
                        if (condition == null) log(r.line(), "Invalid @supports condition");
                        else if (r.block() != null && condition.test(null)) out.addAll(nested(r));
                    }
                    case "layer" -> {
                        if (r.block() != null) out.addAll(nested(r));
                    }
                    case "import" -> {
                        ImportRule imp = importRule(r);
                        if (imp == null || !importsAllowed) log(r.line(), "Ignored @import");
                        else out.add(imp);
                    }
                    case "keyframes", "-webkit-keyframes" -> keyframes(r, out);
                    case "charset", "namespace", "font-face", "page", "property", "counter-style" -> { }
                    default -> log(r.line(), "Unknown at-rule @" + name);
                }
            }
            return out;
        }

        private List<Rule> nested(CssParser.Rule r) {
            return rules(CssParser.parseRules(r.block().body(), false), false);
        }

        private void styleRule(CssParser.Rule r, List<Rule> out) {
            List<Selector> selectors;
            try {
                selectors = SelectorParser.parse(r.prelude());
            } catch (IllegalArgumentException e) {
                log(r.line(), "Invalid selector '" + ComponentValue.text(r.prelude()) + "': " + e.getMessage());
                return;
            }
            List<Decl> decls = declarations(r.block().body(), false);
            if (!decls.isEmpty()) out.add(new StyleRule(selectors, decls));
        }

        /** Expands declarations, logging invalid ones; {@code !important} ones are dropped in keyframes. */
        List<Decl> declarations(List<ComponentValue> body, boolean keyframe) {
            List<Decl> out = new ArrayList<>();
            for (CssParser.Declaration d : CssParser.parseDeclarations(body)) {
                List<Decl> expanded = keyframe && d.important() ? List.of() : Decl.expand(d, url, host);
                if (expanded == null) {
                    log(d.line(), "Invalid declaration '" + d.name() + ": " + ComponentValue.text(d.value()) + "'");
                } else {
                    out.addAll(expanded);
                }
            }
            return out;
        }

        private ImportRule importRule(CssParser.Rule r) {
            ValueReader reader = new ValueReader(r.prelude());
            ComponentValue first = reader.next();
            String target = null;
            if (first instanceof Token t && (t.is(Type.STRING) || t.is(Type.URL))) target = t.value;
            else if (first instanceof Func f && f.name().equals("url") && f.args().size() == 1
                    && f.args().get(0) instanceof Token s && s.is(Type.STRING)) target = s.value;
            return target == null ? null
                    : new ImportRule(ValueContext.resolve(host, url, target), MediaQuery.parse(reader.rest()));
        }

        private void keyframes(CssParser.Rule r, List<Rule> out) {
            ComponentValue name = r.prelude().size() == 1 ? r.prelude().get(0) : null;
            if (r.block() == null || !(name instanceof Token t) || !(t.is(Type.IDENT) || t.is(Type.STRING))) {
                log(r.line(), "Invalid @keyframes");
                return;
            }
            List<Keyframe> frames = new ArrayList<>();
            for (CssParser.Rule frame : CssParser.parseRules(r.block().body(), false)) {
                List<Float> offsets = frame.isAtRule() || frame.block() == null ? null : offsets(frame.prelude());
                if (offsets == null) log(frame.line(), "Invalid keyframe selector");
                else frames.add(new Keyframe(offsets, declarations(frame.block().body(), true)));
            }
            out.add(new KeyframesRule(t.value, frames));
        }

        private static List<Float> offsets(List<ComponentValue> prelude) {
            List<Float> out = new ArrayList<>();
            for (List<ComponentValue> part : ValueReader.splitCommas(prelude)) {
                ComponentValue v = part.size() == 1 ? part.get(0) : null;
                if (v instanceof Token t && t.isIdent("from")) out.add(0f);
                else if (v instanceof Token t && t.isIdent("to")) out.add(1f);
                else if (v instanceof Token t && t.is(Type.PERCENTAGE) && t.number >= 0 && t.number <= 100) {
                    out.add((float) t.number / 100f);
                } else return null;
            }
            return out;
        }

        /** An {@code @supports} leaf: {@code (property: value)} or {@code selector(...)}. */
        private Predicate<Void> supports(ComponentValue v) {
            Boolean supported = null;
            if (v instanceof Func f && f.name().equals("selector")) {
                try {
                    SelectorParser.parse(f.args());
                    supported = true;
                } catch (IllegalArgumentException e) {
                    supported = false;
                }
            } else if (v instanceof Block b && b.open() == '(') {
                List<CssParser.Declaration> d = CssParser.parseDeclarations(b.body());
                supported = d.size() == 1 && Decl.expand(d.get(0), url, host) != null;
            }
            if (supported == null) return null;
            boolean result = supported;
            return ignored -> result;
        }

        private void log(int line, String message) {
            log.accept(line + ": " + message);
        }
    }
}
