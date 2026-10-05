package dev.vellum.engine.css;

import dev.vellum.engine.css.ComponentValue.Block;
import dev.vellum.engine.css.ComponentValue.Func;
import dev.vellum.engine.css.Token.Type;

import java.util.List;
import java.util.function.Function;

/** {@code var()} substitution: produces the value's text with every {@code var(--x[, fallback])} replaced. */
final class Vars {
    private Vars() {}

    /**
     * Substitutes {@code var()}s using {@code lookup} (custom property name → value text, or null when it is not
     * defined). Returns null when a reference is undefined and has no fallback: the declaration is then invalid at
     * computed-value time.
     */
    static String substitute(List<ComponentValue> values, Function<String, String> lookup) {
        StringBuilder sb = new StringBuilder();
        return append(values, lookup, sb) ? sb.toString().trim() : null;
    }

    private static boolean append(List<ComponentValue> values, Function<String, String> lookup, StringBuilder sb) {
        for (ComponentValue v : values) {
            if (v instanceof Func f && f.name().equals("var")) {
                if (!var(f, lookup, sb)) return false;
            } else if (v instanceof Func f && Decl.containsVar(f.args())) {
                sb.append(f.name()).append('(');
                if (!append(f.args(), lookup, sb)) return false;
                sb.append(')');
            } else if (v instanceof Block b && Decl.containsVar(b.body())) {
                sb.append(b.open());
                if (!append(b.body(), lookup, sb)) return false;
                sb.append(b.open() == '(' ? ')' : b.open() == '[' ? ']' : '}');
            } else {
                sb.append(v.text());
            }
        }
        return true;
    }

    private static boolean var(Func f, Function<String, String> lookup, StringBuilder sb) {
        List<ComponentValue> args = f.args();
        int comma = 0;
        while (comma < args.size() && !(args.get(comma) instanceof Token t && t.is(Type.COMMA))) comma++;
        List<ComponentValue> name = CssParser.trim(args.subList(0, comma));
        if (name.size() != 1 || !(name.get(0) instanceof Token t) || !t.is(Type.IDENT) || !t.value.startsWith("--")) {
            return false;
        }
        String value = lookup.apply(t.value);
        if (value != null) {
            sb.append(value);
            return true;
        }
        return comma < args.size() && append(args.subList(comma + 1, args.size()), lookup, sb);
    }
}
