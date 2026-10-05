package dev.vellum.engine.css;

import dev.vellum.engine.Limits;
import dev.vellum.engine.css.ComponentValue.Block;
import dev.vellum.engine.css.ComponentValue.Func;
import dev.vellum.engine.css.Token.Type;
import dev.vellum.engine.style.GridLine;
import dev.vellum.engine.style.GridTrack;
import dev.vellum.engine.style.Length;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Grid values: track lists ({@code grid-template-columns/rows}, {@code grid-auto-*}), {@code grid-template-areas}
 * and placement lines. Line names ({@code [name]}) are accepted and ignored; integer {@code repeat()}s are expanded.
 */
final class Grids {
    private static final Map<String, Length> TRACK_KEYWORDS = Map.of(
            "auto", Length.AUTO, "min-content", Length.MIN_CONTENT, "max-content", Length.MAX_CONTENT);
    private static final Map<String, GridTrack.RepeatKind> AUTO_REPEATS = Map.of(
            "auto-fill", GridTrack.RepeatKind.AUTO_FILL, "auto-fit", GridTrack.RepeatKind.AUTO_FIT);

    private Grids() {}

    /** A track list, optionally with {@code repeat()}; null if empty or malformed. */
    static List<GridTrack> tracks(ValueReader r, ValueContext ctx, boolean allowRepeat) {
        List<GridTrack> out = new ArrayList<>();
        while (!r.atEnd()) {
            if (r.peek() instanceof Block b && b.open() == '[') {
                r.next();
                continue;
            }
            Func repeat = allowRepeat ? r.function("repeat") : null;
            if (repeat != null) {
                if (!repeat(repeat, ctx, out)) return null;
                continue;
            }
            GridTrack t = track(r, ctx);
            if (t == null) return null;
            out.add(t);
        }
        return out.isEmpty() ? null : List.copyOf(out);
    }

    /** {@code repeat(<count> | auto-fill | auto-fit, <tracks>)}: integer counts are expanded into {@code out}. */
    private static boolean repeat(Func f, ValueContext ctx, List<GridTrack> out) {
        List<List<ComponentValue>> args = ValueReader.splitCommas(f.args());
        if (args.size() != 2) return false;
        ValueReader countReader = new ValueReader(args.get(0));
        GridTrack.RepeatKind auto = Keywords.read(countReader, AUTO_REPEATS);
        Integer count = auto == null ? Numeric.integer(countReader, ctx) : null;
        List<GridTrack> tracks = tracks(new ValueReader(args.get(1)), ctx, false);
        if (!countReader.atEnd() || tracks == null || (auto == null && (count == null || count < 1))) return false;
        if (auto != null) out.add(new GridTrack.Repeat(auto, 0, tracks, List.of()));
        else if ((long) count * tracks.size() + out.size() > Limits.current().maxGridTracks()) return false;
        else for (int i = 0; i < count; i++) out.addAll(tracks);
        return true;
    }

    /** A track size: breadth, {@code minmax()} or {@code fit-content()}. */
    private static GridTrack track(ValueReader r, ValueContext ctx) {
        Func f = r.function("minmax");
        if (f != null) {
            List<List<ComponentValue>> args = ValueReader.splitCommas(f.args());
            if (args.size() != 2) return null;
            GridTrack min = single(args.get(0), ctx), max = single(args.get(1), ctx);
            return min == null || max == null || min instanceof GridTrack.Flex ? null : new GridTrack.MinMax(min, max);
        }
        f = r.function("fit-content");
        if (f != null) {
            ValueReader a = new ValueReader(f.args());
            Length limit = Numeric.length(a, ctx, false);
            return limit == null || !a.atEnd() ? null : new GridTrack.FitContent(limit);
        }
        return breadth(r, ctx);
    }

    private static GridTrack single(List<ComponentValue> values, ValueContext ctx) {
        ValueReader r = new ValueReader(values);
        GridTrack t = breadth(r, ctx);
        return r.atEnd() ? t : null;
    }

    /** A length or percentage, {@code <n>fr}, or auto / min-content / max-content. */
    private static GridTrack breadth(ValueReader r, ValueContext ctx) {
        Length keyword = Keywords.read(r, TRACK_KEYWORDS);
        if (keyword != null) return new GridTrack.Keyword(keyword);
        if (r.peek() instanceof Token t && t.is(Type.DIMENSION) && t.lower.equals("fr") && t.number >= 0) {
            r.next();
            return new GridTrack.Flex((float) t.number);
        }
        Length l = Numeric.length(r, ctx, false);
        return l == null ? null : new GridTrack.Fixed(l);
    }

    /**
     * {@code grid-template-areas} strings as rows of cell names ("." for empty cells). Null unless every row has the
     * same number of cells and each named area is a filled rectangle.
     */
    static List<List<String>> areas(ValueReader r) {
        List<List<String>> rows = new ArrayList<>();
        Token s;
        while ((s = r.next(Type.STRING)) != null) {
            List<String> row = cells(s.value);
            if (row == null || row.isEmpty() || (!rows.isEmpty() && row.size() != rows.get(0).size())) return null;
            rows.add(List.copyOf(row));
        }
        return rows.isEmpty() || !rectangular(rows) ? null : List.copyOf(rows);
    }

    private static List<String> cells(String row) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < row.length()) {
            char c = row.charAt(i);
            int start = i;
            if (Tokenizer.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '.') {
                while (i < row.length() && row.charAt(i) == '.') i++;
                out.add(".");
                continue;
            }
            while (i < row.length() && isNameChar(row.charAt(i))) i++;
            if (i == start) return null;
            out.add(row.substring(start, i));
        }
        return out;
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_' || c >= 0x80;
    }

    /** Each named area's cells must exactly fill its bounding box. */
    private static boolean rectangular(List<List<String>> rows) {
        Map<String, int[]> bounds = new HashMap<>(); // name → {minRow, minCol, maxRow, maxCol, cells}
        for (int y = 0; y < rows.size(); y++) {
            for (int x = 0; x < rows.get(y).size(); x++) {
                String name = rows.get(y).get(x);
                if (name.equals(".")) continue;
                int[] b = bounds.computeIfAbsent(name, k -> new int[] {Integer.MAX_VALUE, Integer.MAX_VALUE, -1, -1, 0});
                b[0] = Math.min(b[0], y);
                b[1] = Math.min(b[1], x);
                b[2] = Math.max(b[2], y);
                b[3] = Math.max(b[3], x);
                b[4]++;
            }
        }
        for (int[] b : bounds.values()) if ((b[2] - b[0] + 1) * (b[3] - b[1] + 1) != b[4]) return false;
        return true;
    }

    /**
     * A placement: {@code auto}, {@code <integer>}, {@code span <integer>}, or an area / line name. Combinations with a
     * name ({@code 2 foo}, {@code span foo}) use the name, or span 1.
     */
    static GridLine line(ValueReader r, ValueContext ctx) {
        if (r.ident("auto")) return GridLine.AUTO;
        boolean span = r.ident("span");
        Integer n = Numeric.integer(r, ctx);
        String name = null;
        if (r.peekIdent() != null && !r.peekIdent().equals("span") && !r.peekIdent().equals("auto")) {
            name = ((Token) r.next()).value;
            if (n == null) n = Numeric.integer(r, ctx);
        }
        if (span) return n == null ? (name == null ? null : GridLine.span(1)) : n > 0 ? GridLine.span(n) : null;
        if (name != null) return GridLine.named(name);
        return n == null || n == 0 ? null : GridLine.line(n);
    }

    static String serializeTracks(List<GridTrack> tracks) {
        return tracks.isEmpty() ? "none" : CssText.join(tracks, " ", Grids::serialize);
    }

    private static String serialize(GridTrack t) {
        return switch (t) {
            case GridTrack.Fixed f -> f.size().toString();
            case GridTrack.Flex f -> CssText.number(f.fr()) + "fr";
            case GridTrack.Keyword k -> k.keyword().toString();
            case GridTrack.MinMax m -> "minmax(" + serialize(m.min()) + ", " + serialize(m.max()) + ")";
            case GridTrack.FitContent f -> "fit-content(" + f.limit() + ")";
            case GridTrack.Repeat rep -> "repeat(" + Keywords.css(rep.kind()) + ", " + serializeTracks(rep.tracks()) + ")";
        };
    }

    static String serializeAreas(List<List<String>> areas) {
        return areas == null ? "none" : CssText.join(areas, " ", row -> CssText.string(String.join(" ", row)));
    }

    static String serializeLine(GridLine line) {
        return switch (line.kind()) {
            case AUTO -> "auto";
            case LINE -> Integer.toString(line.value());
            case SPAN -> "span " + line.value();
            case NAMED -> line.name();
        };
    }
}
