package dev.vellum.engine.css;

import java.util.List;
import java.util.function.Function;

/** Formatting helpers for serialising computed values ({@code getComputedStyle}). */
final class CssText {
    private CssText() {}

    /** A number without a trailing ".0", rounded to 4 decimals. */
    static String number(float f) {
        if (f == (int) f) return Integer.toString((int) f);
        float rounded = Math.round(f * 10000) / 10000f;
        return rounded == (int) rounded ? Integer.toString((int) rounded) : Float.toString(rounded);
    }

    static String px(float f) {
        return number(f) + "px";
    }

    static String deg(float f) {
        return number(f) + "deg";
    }

    /** A time in seconds, as browsers report durations. */
    static String seconds(float ms) {
        return number(ms / 1000f) + "s";
    }

    static String string(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /**
     * The shortest box-shorthand form of {@code values} (top, right, bottom, left; or corners, or a pair):
     * {@code 1px 1px 1px 1px} → {@code 1px}, {@code 1px 2px 1px 2px} → {@code 1px 2px}.
     */
    static String collapse(List<String> values) {
        int n = values.size();
        if (n == 4 && values.get(3).equals(values.get(1))) n = 3;
        if (n == 3 && values.get(2).equals(values.get(0))) n = 2;
        if (n == 2 && values.get(1).equals(values.get(0))) n = 1;
        return String.join(" ", values.subList(0, n));
    }

    static <T> String join(List<T> items, String separator, Function<? super T, String> format) {
        StringBuilder sb = new StringBuilder();
        for (T item : items) {
            if (!sb.isEmpty()) sb.append(separator);
            sb.append(format.apply(item));
        }
        return sb.toString();
    }
}
