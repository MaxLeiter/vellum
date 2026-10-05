package dev.vellum.engine.css;

import dev.vellum.engine.css.ComponentValue.Block;
import dev.vellum.engine.css.ComponentValue.Func;
import dev.vellum.engine.css.Token.Type;
import dev.vellum.engine.host.Host;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One longhand (or custom property) declaration after shorthand expansion: what the cascade sorts and applies.
 *
 * <p>Values that do not depend on the element are computed once when the stylesheet is parsed ({@link #constant}).
 * Values using {@code var()} are kept as written and substituted per element; when such a value belongs to a
 * shorthand, every longhand keeps the whole shorthand value and re-expands it after substitution ({@link #pending}).
 */
final class Decl {
    /** CSS-wide keywords. {@code revert} and {@code revert-layer} behave as {@code unset} (there is no user origin). */
    enum Keyword { INHERIT, INITIAL, UNSET }

    /** The longhand, or null for a custom property. */
    final Longhand property;
    /** The custom property name ({@code --x}), or null. */
    final String customName;
    /** A custom property's value as written (what {@code var()} substitutes when it has no var() itself). */
    final String customText;
    final List<ComponentValue> value;
    /** The shorthand to re-expand after {@code var()} substitution, or null. */
    final Shorthand pending;
    final boolean important;
    final Keyword keyword;
    /** The computed value when it does not depend on the element; null otherwise. */
    final Object constant;
    final boolean hasVar;
    /** Base for relative {@code url()}s: the declaring stylesheet's URL. */
    final String baseUrl;

    private Decl(Longhand property, String customName, List<ComponentValue> value, Shorthand pending,
                 boolean important, Keyword keyword, Object constant, String baseUrl) {
        this.property = property;
        this.customName = customName;
        this.customText = customName == null ? null : ComponentValue.text(value);
        this.value = value;
        this.pending = pending;
        this.important = important;
        this.keyword = keyword;
        this.constant = constant;
        this.hasVar = containsVar(value);
        this.baseUrl = baseUrl;
    }

    boolean isCustom() {
        return customName != null;
    }

    /**
     * Expands a parsed declaration into longhand declarations, validating values (with a probe context) and
     * pre-computing the ones that do not depend on the element. Returns null when the property is unknown or the
     * value invalid.
     */
    static List<Decl> expand(CssParser.Declaration d, String baseUrl, Host host) {
        Keyword keyword = keyword(d.value());
        if (d.name().startsWith("--")) {
            return List.of(new Decl(null, d.name(), d.value(), null, d.important(), keyword, null, baseUrl));
        }
        Longhand longhand = Properties.longhand(d.name());
        Shorthand shorthand = longhand == null ? Shorthands.get(d.name()) : null;
        if ((longhand == null && shorthand == null) || d.value().isEmpty()) return null;
        if (keyword != null || containsVar(d.value())) {
            List<Longhand> targets = longhand != null ? List.of(longhand) : shorthand.longhands();
            List<Decl> out = new ArrayList<>(targets.size());
            for (Longhand t : targets) {
                out.add(new Decl(t, null, d.value(), keyword == null ? shorthand : null, d.important(), keyword, null,
                        baseUrl));
            }
            return out;
        }
        Map<Longhand, List<ComponentValue>> values = longhand != null ? Map.of(longhand, d.value())
                : shorthand.expand(d.value());
        if (values == null) return null;
        ValueContext probe = new ValueContext().environment(host, 320, 240, 1);
        probe.baseUrl(baseUrl);
        List<Decl> out = new ArrayList<>(values.size());
        for (Map.Entry<Longhand, List<ComponentValue>> e : values.entrySet()) {
            Decl decl = longhand(e.getKey(), e.getValue(), d.important(), baseUrl, probe);
            if (decl == null) return null;
            out.add(decl);
        }
        return out;
    }

    /** A longhand declaration with a var()-free value, pre-computed when possible; null if the value is invalid. */
    private static Decl longhand(Longhand p, List<ComponentValue> value, boolean important, String baseUrl,
                                 ValueContext probe) {
        Keyword keyword = keyword(value);
        if (keyword != null) return new Decl(p, null, value, null, important, keyword, null, baseUrl);
        probe.dependent = false;
        Object v = parse(p, value, probe);
        if (v == null) return null;
        return new Decl(p, null, value, null, important, null, probe.dependent ? null : v, baseUrl);
    }

    /** A declaration of the CSS initial value, for {@link Longhand#initial}. */
    static Decl initial(Longhand p, String cssText) {
        Decl d = longhand(p, CssParser.parseComponentValues(cssText), false, "", new ValueContext());
        if (d == null) throw new IllegalArgumentException("Bad initial value for " + p + ": " + cssText);
        return d;
    }

    /** Parses a whole value with a longhand's parser: null unless every component value is consumed. */
    static Object parse(Longhand p, List<ComponentValue> value, ValueContext ctx) {
        if (value.isEmpty()) return null;
        ValueReader r = new ValueReader(value);
        Object v = p.parser.parse(r, ctx);
        return v != null && r.atEnd() ? v : null;
    }

    /** The CSS-wide keyword a value consists of, or null. */
    static Keyword keyword(List<ComponentValue> value) {
        if (value.size() != 1 || !(value.get(0) instanceof Token t) || !t.is(Type.IDENT)) return null;
        return switch (t.lower) {
            case "inherit" -> Keyword.INHERIT;
            case "initial" -> Keyword.INITIAL;
            case "unset", "revert", "revert-layer" -> Keyword.UNSET;
            default -> null;
        };
    }

    static boolean containsVar(List<ComponentValue> values) {
        for (ComponentValue v : values) {
            if (v instanceof Func f && (f.name().equals("var") || containsVar(f.args()))) return true;
            if (v instanceof Block b && containsVar(b.body())) return true;
        }
        return false;
    }
}
