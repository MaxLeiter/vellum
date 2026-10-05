package dev.vellum.engine.css;

import dev.vellum.engine.css.ComponentValue.Block;
import dev.vellum.engine.css.Token.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * A media query list (Media Queries 4 subset): media types ({@code all}, {@code screen}; anything else never
 * matches), {@code not}/{@code only}, conditions with {@code and}/{@code or}/{@code not}, and the features
 * {@code width}, {@code height} (with {@code min-}/{@code max-} and range syntax), {@code orientation},
 * {@code prefers-reduced-motion}, and Vellum's {@code gui-scale} (with {@code min-}/{@code max-}). Unknown features
 * never match. Lengths use the default 8px em.
 */
final class MediaQuery {
    /** What media queries are evaluated against. */
    record Environment(float width, float height, float guiScale, boolean reducedMotion) {}

    static final MediaQuery ALL = new MediaQuery(List.of(env -> true));

    private final List<Predicate<Environment>> queries;

    private MediaQuery(List<Predicate<Environment>> queries) {
        this.queries = queries;
    }

    boolean matches(Environment env) {
        for (Predicate<Environment> q : queries) if (q.test(env)) return true;
        return false;
    }

    /** Parses a comma-separated query list; malformed queries become {@code not all}. */
    static MediaQuery parse(List<ComponentValue> values) {
        if (ValueReader.items(values).isEmpty()) return ALL;
        List<Predicate<Environment>> queries = new ArrayList<>();
        for (List<ComponentValue> q : ValueReader.splitCommas(values)) {
            Predicate<Environment> p = query(q);
            queries.add(p == null ? env -> false : p);
        }
        return new MediaQuery(queries);
    }

    static MediaQuery parse(String text) {
        return parse(CssParser.parseComponentValues(text));
    }

    private static Predicate<Environment> query(List<ComponentValue> values) {
        List<ComponentValue> items = ValueReader.items(values);
        if (items.isEmpty()) return null;
        if (items.get(0) instanceof Block) return Conditions.parse(values, MediaQuery::feature);
        int i = 0;
        boolean not = items.get(0) instanceof Token t && t.isIdent("not");
        if (not || (items.get(0) instanceof Token t && t.isIdent("only"))) i++;
        if (i >= items.size() || !(items.get(i) instanceof Token type) || !type.is(Type.IDENT)) return null;
        boolean typeMatches = type.lower.equals("all") || type.lower.equals("screen");
        Predicate<Environment> condition = env -> typeMatches;
        if (++i < items.size()) {
            if (!(items.get(i) instanceof Token and) || !and.isIdent("and")) return null;
            Predicate<Environment> rest = Conditions.parse(items.subList(i + 1, items.size()), MediaQuery::feature);
            if (rest == null) return null;
            condition = condition.and(rest);
        }
        return not ? condition.negate() : condition;
    }

    /** One {@code (feature)}, {@code (feature: value)} or range {@code (a < feature <= b)}. */
    private static Predicate<Environment> feature(ComponentValue v) {
        if (!(v instanceof Block b) || b.open() != '(') return null;
        List<ComponentValue> items = ValueReader.items(b.body());
        if (items.size() == 1 && items.get(0) instanceof Token name && name.is(Type.IDENT)) {
            return booleanFeature(name.lower);
        }
        if (items.size() == 3 && items.get(1) instanceof Token colon && colon.is(Type.COLON)
                && items.get(0) instanceof Token name && name.is(Type.IDENT)) {
            return plainFeature(name.lower, items.get(2));
        }
        return range(items);
    }

    private static Predicate<Environment> booleanFeature(String name) {
        return switch (name) {
            case "width" -> env -> env.width() > 0;
            case "height" -> env -> env.height() > 0;
            case "prefers-reduced-motion" -> Environment::reducedMotion;
            case "orientation", "gui-scale" -> env -> true;
            default -> null;
        };
    }

    private static Predicate<Environment> plainFeature(String name, ComponentValue value) {
        String keyword = value instanceof Token t && t.is(Type.IDENT) ? t.lower : null;
        switch (name) {
            case "orientation" -> {
                if ("portrait".equals(keyword)) return env -> env.height() >= env.width();
                if ("landscape".equals(keyword)) return env -> env.width() > env.height();
                return null;
            }
            case "prefers-reduced-motion" -> {
                if ("reduce".equals(keyword)) return Environment::reducedMotion;
                if ("no-preference".equals(keyword)) return env -> !env.reducedMotion();
                return null;
            }
            default -> {
                boolean min = name.startsWith("min-"), max = name.startsWith("max-");
                String base = min || max ? name.substring(4) : name;
                Float n = number(base, value);
                if (n == null) return null;
                String op = min ? ">=" : max ? "<=" : "=";
                return env -> compare(measure(base, env), op, n);
            }
        }
    }

    /** {@code (feature op value)}, {@code (value op feature)} or {@code (value op feature op value)}. */
    private static Predicate<Environment> range(List<ComponentValue> items) {
        List<String> ops = new ArrayList<>();
        List<ComponentValue> operands = new ArrayList<>();
        for (int i = 0; i < items.size(); ) {
            operands.add(items.get(i++));
            if (i >= items.size()) break;
            StringBuilder op = new StringBuilder();
            while (i < items.size() && items.get(i) instanceof Token t && t.is(Type.DELIM) && "<>=".contains(t.value)) {
                op.append(t.value);
                i++;
            }
            if (op.isEmpty()) return null;
            ops.add(op.toString());
        }
        int nameIndex = -1;
        for (int i = 0; i < operands.size(); i++) {
            if (operands.get(i) instanceof Token t && t.is(Type.IDENT)) nameIndex = i;
        }
        if (nameIndex < 0 || operands.size() < 2 || operands.size() > 3 || ops.size() != operands.size() - 1) return null;
        String name = ((Token) operands.get(nameIndex)).lower;
        Predicate<Environment> result = env -> true;
        for (int i = 0; i < ops.size(); i++) {
            // Normalise each comparison to "feature op value".
            boolean featureLeft = i == nameIndex;
            Float n = number(name, operands.get(featureLeft ? i + 1 : i));
            String op = featureLeft ? ops.get(i) : flip(ops.get(i));
            if (n == null || op == null) return null;
            result = result.and(env -> compare(measure(name, env), op, n));
        }
        return result;
    }

    private static String flip(String op) {
        return switch (op) {
            case "<" -> ">";
            case ">" -> "<";
            case "<=" -> ">=";
            case ">=" -> "<=";
            case "=" -> "=";
            default -> null;
        };
    }

    private static float measure(String feature, Environment env) {
        return switch (feature) {
            case "width" -> env.width();
            case "height" -> env.height();
            default -> env.guiScale();
        };
    }

    /** A range feature's comparison value (px for width/height, a number for gui-scale), or null. */
    private static Float number(String feature, ComponentValue value) {
        ValueReader r = new ValueReader(List.of(value));
        return switch (feature) {
            case "width", "height" -> Numeric.px(r, new ValueContext(), true);
            case "gui-scale" -> Numeric.number(r, new ValueContext());
            default -> null;
        };
    }

    private static boolean compare(float actual, String op, float value) {
        return switch (op) {
            case "<" -> actual < value;
            case "<=" -> actual <= value;
            case ">" -> actual > value;
            case ">=" -> actual >= value;
            case "=" -> actual == value;
            default -> false;
        };
    }
}
