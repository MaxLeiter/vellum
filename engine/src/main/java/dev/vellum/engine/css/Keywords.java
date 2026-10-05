package dev.vellum.engine.css;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Keyword values: tables from CSS keywords to values, and the enum ↔ keyword spelling convention. */
final class Keywords {
    private Keywords() {}

    /** The CSS spelling of an enum constant: {@code INLINE_BLOCK} → {@code inline-block}. */
    static String css(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** Every constant of {@code type} under its CSS spelling, plus {@code aliases} (which win on conflicts). */
    static <E extends Enum<E>> Map<String, E> table(Class<E> type, Map<String, E> aliases) {
        Map<String, E> table = new HashMap<>(aliases);
        for (E e : type.getEnumConstants()) table.putIfAbsent(css(e), e);
        return table;
    }

    static <E extends Enum<E>> Longhand.Parser parser(Class<E> type) {
        return parser(table(type, Map.of()));
    }

    static <E extends Enum<E>> Longhand.Parser parser(Class<E> type, Map<String, E> aliases) {
        return parser(table(type, aliases));
    }

    /** A parser accepting exactly one keyword of {@code table}. */
    static Longhand.Parser parser(Map<String, ?> table) {
        return (r, ctx) -> read(r, table);
    }

    /** Consumes one keyword of {@code table} and returns its value, or returns null without consuming. */
    static <T> T read(ValueReader r, Map<String, T> table) {
        String id = r.peekIdent();
        T value = id == null ? null : table.get(id);
        if (value != null) r.next();
        return value;
    }
}
