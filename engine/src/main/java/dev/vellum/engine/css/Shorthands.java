package dev.vellum.engine.css;

import dev.vellum.engine.css.ComponentValue.Block;
import dev.vellum.engine.css.Token.Type;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The shorthand registry and expanders. Expanders only route component values to longhands (see {@link Shorthand});
 * validation happens when each longhand parses its part. {@code list-style} is accepted and ignored.
 */
final class Shorthands {
    /** The value given to longhands a shorthand omits. */
    static final List<ComponentValue> INITIAL = values("initial");
    private static final List<ComponentValue> AUTO = values("auto"), ZERO = values("0"), ONE = values("1"),
            ZERO_PERCENT = values("0%"), COMMA = values(",");
    private static final Set<String> POSITION_KEYWORDS = Set.of("left", "right", "top", "bottom", "center");
    private static final Set<String> DECORATION_STYLES = Set.of("solid", "double", "dotted", "dashed", "wavy",
            "auto", "from-font");
    private static final Longhand BG_COLOR = Properties.longhand("background-color"),
            BG_IMAGE = Properties.longhand("background-image"), BG_X = Properties.longhand("background-position-x"),
            BG_Y = Properties.longhand("background-position-y"), BG_SIZE = Properties.longhand("background-size"),
            BG_REPEAT = Properties.longhand("background-repeat"), BG_CLIP = Properties.longhand("background-clip");
    /** Each list component's default entry as CSS ({@code transition-duration} → {@code 0s}). */
    private static final Map<Longhand, List<ComponentValue>> DEFAULT_ITEMS = new HashMap<>();
    private static final Map<String, Shorthand> BY_NAME = new HashMap<>();

    /** Splits a shorthand value among its longhands; omitted longhands may be left out of the result. */
    @FunctionalInterface
    private interface Expander {
        Map<Longhand, List<ComponentValue>> expand(List<Longhand> longhands, List<ComponentValue> value);
    }

    static {
        for (ListGroup g : ListGroup.values()) {
            for (Longhand c : Properties.components(g)) {
                DEFAULT_ITEMS.put(c, values(c.serialize(List.of(g.defaultValue(c.component)))));
            }
        }
        box("margin", "margin-top", "margin-right", "margin-bottom", "margin-left");
        box("padding", "padding-top", "padding-right", "padding-bottom", "padding-left");
        box("inset", "top", "right", "bottom", "left");
        for (String part : List.of("width", "style", "color")) {
            box("border-" + part, "border-top-" + part, "border-right-" + part, "border-bottom-" + part,
                    "border-left-" + part);
        }
        register("border-radius", longhands("border-top-left-radius", "border-top-right-radius",
                "border-bottom-right-radius", "border-bottom-left-radius"), CssText::collapse, (l, v) -> {
            // Elliptical corners ("a / b") keep the horizontal radii.
            List<List<ComponentValue>> axes = ValueReader.splitSlashes(v);
            return axes.size() > 2 ? null : sides(l, ValueReader.items(axes.get(0)));
        });
        pair("overflow", "overflow-x", "overflow-y");
        pair("gap", "row-gap", "column-gap");
        pair("place-items", "align-items", "justify-items");
        pair("place-content", "align-content", "justify-content");
        pair("place-self", "align-self", "justify-self");
        anyOrder("flex-flow", slot("flex-direction"), slot("flex-wrap"));
        anyOrder("outline", slot("outline-width"), slot("outline-style"), slot("outline-color"));
        for (String side : List.of("top", "right", "bottom", "left")) {
            String p = "border-" + side + "-";
            anyOrder("border-" + side, slot(p + "width"), slot(p + "style"), slot(p + "color"));
        }
        List<Slot> border = List.of(allSides("width"), allSides("style"), allSides("color"));
        register("border", border.stream().flatMap(s -> s.targets.stream()).toList(), Shorthands::joinBorder,
                (l, v) -> whole(v, border, new ValueContext()));
        register("flex", longhands("flex-grow", "flex-shrink", "flex-basis"), Shorthands::flex);
        register("font", longhands("font-style", "font-weight", "font-size", "line-height", "font-family"),
                Shorthands::font);
        register("text-decoration-line", longhands("-vellum-underline", "-vellum-line-through"), Shorthands::fill);
        register("text-decoration", longhands("-vellum-underline", "-vellum-line-through"),
                Shorthands::textDecoration);
        gridLines("grid-row", "grid-row-start", "grid-row-end");
        gridLines("grid-column", "grid-column-start", "grid-column-end");
        register("grid-area", longhands("grid-row-start", "grid-column-start", "grid-row-end", "grid-column-end"),
                values -> String.join(" / ", values), Shorthands::gridArea);
        register("grid-template", longhands("grid-template-rows", "grid-template-columns", "grid-template-areas"),
                Shorthands::gridTemplate);
        register("transform-origin", longhands("-vellum-transform-origin-x", "-vellum-transform-origin-y"),
                Shorthands::transformOrigin);
        register("scrollbar-color", longhands("-vellum-scrollbar-thumb-color", "-vellum-scrollbar-track-color"),
                (l, v) -> isIdent(v, "auto") ? Map.of() : sequence(l, v, false));
        register("background-position", List.of(BG_X, BG_Y), Shorthands::backgroundPosition);
        List<Longhand> background = new ArrayList<>(List.of(BG_COLOR));
        background.addAll(Properties.components(ListGroup.BACKGROUND));
        register("background", background, Shorthands::background);
        list("transition", ListGroup.TRANSITION, "transition-timing-function", "transition-duration",
                "transition-delay", "transition-property");
        list("animation", ListGroup.ANIMATION, "animation-timing-function", "animation-duration", "animation-delay",
                "animation-iteration-count", "animation-direction", "animation-fill-mode", "animation-play-state",
                "animation-name");
        register("list-style", List.of(), (l, v) -> Map.of());
    }

    private Shorthands() {}

    static Shorthand get(String name) {
        return BY_NAME.get(name);
    }

    // ---- Registration ----

    private static void register(String name, List<Longhand> longhands, Expander expander) {
        register(name, longhands, values -> String.join(" ", values), expander);
    }

    /** Registers a shorthand; longhands the expander leaves out are set to {@code initial}. */
    private static void register(String name, List<Longhand> longhands, Function<List<String>, String> joiner,
                                 Expander expander) {
        BY_NAME.put(name, new Shorthand(name, longhands, joiner, v -> {
            Map<Longhand, List<ComponentValue>> parts = expander.expand(longhands, v);
            if (parts == null) return null;
            Map<Longhand, List<ComponentValue>> out = new LinkedHashMap<>();
            for (Longhand l : longhands) out.put(l, parts.getOrDefault(l, INITIAL));
            return out;
        }));
    }

    private static List<Longhand> longhands(String... names) {
        return Arrays.stream(names).map(Properties::longhand).toList();
    }

    private static List<ComponentValue> values(String css) {
        return CssParser.parseComponentValues(css);
    }

    /** {@code top [right [bottom [left]]]}, as in margin and padding. */
    private static void box(String name, String... sides) {
        register(name, longhands(sides), CssText::collapse, (l, v) -> sides(l, ValueReader.items(v)));
    }

    private static Map<Longhand, List<ComponentValue>> sides(List<Longhand> longhands, List<ComponentValue> items) {
        if (items.isEmpty() || items.size() > 4) return null;
        int[] index = switch (items.size()) {
            case 1 -> new int[] {0, 0, 0, 0};
            case 2 -> new int[] {0, 1, 0, 1};
            case 3 -> new int[] {0, 1, 2, 1};
            default -> new int[] {0, 1, 2, 3};
        };
        Map<Longhand, List<ComponentValue>> out = new HashMap<>();
        for (int i = 0; i < 4; i++) out.put(longhands.get(i), List.of(items.get(index[i])));
        return out;
    }

    /** Two longhands in order; the second defaults to the first ({@code overflow: hidden}). */
    private static void pair(String name, String first, String second) {
        register(name, longhands(first, second), CssText::collapse, (l, v) -> sequence(l, v, true));
    }

    /** Values for {@code longhands} in order, each parsed greedily; missing ones repeat the first, or fail. */
    private static Map<Longhand, List<ComponentValue>> sequence(List<Longhand> longhands, List<ComponentValue> v,
                                                               boolean repeatFirst) {
        ValueReader r = new ValueReader(v);
        ValueContext probe = new ValueContext();
        Map<Longhand, List<ComponentValue>> out = new HashMap<>();
        for (Longhand l : longhands) {
            List<ComponentValue> part = r.atEnd() ? null : r.take(l.parser, probe);
            if (part == null) {
                if (!repeatFirst || out.isEmpty()) return null;
                part = out.get(longhands.get(0));
            }
            out.put(l, part);
        }
        return r.atEnd() ? out : null;
    }

    /** A value recognised by {@code parser} that sets all of {@code targets} (e.g. the four border widths). */
    private record Slot(Longhand.Parser parser, List<Longhand> targets) {}

    private static Slot slot(String longhand) {
        Longhand l = Properties.longhand(longhand);
        return new Slot(l.parser, List.of(l));
    }

    private static Slot allSides(String part) {
        List<Longhand> sides = longhands("border-top-" + part, "border-right-" + part, "border-bottom-" + part,
                "border-left-" + part);
        return new Slot(sides.get(0).parser, sides);
    }

    /** {@code border} serialises only when all four sides agree. */
    private static String joinBorder(List<String> values) {
        for (int i = 0; i < values.size(); i++) if (!values.get(i).equals(values.get(i - i % 4))) return null;
        return values.get(0) + " " + values.get(4) + " " + values.get(8);
    }

    /** Components in any order, each at most once ({@code border-top: solid 1px red}). */
    private static void anyOrder(String name, Slot... slots) {
        List<Slot> list = List.of(slots);
        register(name, list.stream().flatMap(s -> s.targets.stream()).toList(),
                (l, v) -> whole(v, list, new ValueContext()));
    }

    /** {@link #anyOrder} over a whole value: null unless every component value was claimed. */
    private static Map<Longhand, List<ComponentValue>> whole(List<ComponentValue> v, List<Slot> slots,
                                                             ValueContext probe) {
        ValueReader r = new ValueReader(v);
        Map<Longhand, List<ComponentValue>> out = anyOrder(r, slots, probe);
        return r.atEnd() && !out.isEmpty() ? out : null;
    }

    /**
     * Claims values for {@code slots} in any order, each slot at most once, trying slots in order for each value.
     * Stops at the first value no free slot accepts.
     */
    private static Map<Longhand, List<ComponentValue>> anyOrder(ValueReader r, List<Slot> slots, ValueContext probe) {
        Map<Longhand, List<ComponentValue>> out = new HashMap<>();
        boolean[] used = new boolean[slots.size()];
        boolean claimed = true;
        while (claimed && !r.atEnd()) {
            claimed = false;
            for (int i = 0; i < slots.size() && !claimed; i++) {
                List<ComponentValue> part = used[i] ? null : r.take(slots.get(i).parser, probe);
                if (part == null) continue;
                used[i] = claimed = true;
                for (Longhand t : slots.get(i).targets) out.put(t, part);
            }
        }
        return out;
    }

    /** Every longhand gets the whole value. */
    private static Map<Longhand, List<ComponentValue>> fill(List<Longhand> longhands, List<ComponentValue> v) {
        Map<Longhand, List<ComponentValue>> out = new HashMap<>();
        for (Longhand l : longhands) out.put(l, v);
        return out;
    }

    private static boolean isIdent(List<ComponentValue> v, String lower) {
        return v.size() == 1 && v.get(0) instanceof Token t && t.isIdent(lower);
    }

    /** A single custom identifier (not {@code auto} or {@code span}): grid line names copied to omitted ends. */
    private static boolean isCustomIdent(List<ComponentValue> v) {
        return v.size() == 1 && v.get(0) instanceof Token t && t.is(Type.IDENT) && !t.isIdent("auto")
                && !t.isIdent("span");
    }

    // ---- Expanders ----

    /** {@code none | auto | <grow> [<shrink>]? || <basis>}. A grow without a basis means basis 0%. */
    private static Map<Longhand, List<ComponentValue>> flex(List<Longhand> l, List<ComponentValue> v) {
        Longhand growL = l.get(0), shrinkL = l.get(1), basisL = l.get(2);
        if (isIdent(v, "none")) return Map.of(growL, ZERO, shrinkL, ZERO, basisL, AUTO);
        if (isIdent(v, "auto")) return Map.of(growL, ONE, shrinkL, ONE, basisL, AUTO);
        ValueReader r = new ValueReader(v);
        ValueContext probe = new ValueContext();
        List<ComponentValue> grow = null, shrink = null, basis = null, part;
        while (!r.atEnd()) {
            if (grow == null && (part = r.take(growL.parser, probe)) != null) {
                grow = part;
                shrink = r.take(shrinkL.parser, probe);
            } else if (basis == null && (part = r.take(basisL.parser, probe)) != null) {
                basis = part;
            } else {
                return null;
            }
        }
        return Map.of(growL, grow == null ? ONE : grow, shrinkL, shrink == null ? ONE : shrink,
                basisL, basis != null ? basis : grow != null ? ZERO_PERCENT : AUTO);
    }

    /** {@code [<style> || <weight>]? <size> [/ <line-height>]? <family>} (no font-variant or font-stretch). */
    private static Map<Longhand, List<ComponentValue>> font(List<Longhand> l, List<ComponentValue> v) {
        Longhand style = l.get(0), weight = l.get(1), size = l.get(2), lineHeight = l.get(3), family = l.get(4);
        ValueReader r = new ValueReader(v);
        ValueContext probe = new ValueContext();
        Map<Longhand, List<ComponentValue>> out = anyOrder(r,
                List.of(new Slot(style.parser, List.of(style)), new Slot(weight.parser, List.of(weight))), probe);
        List<ComponentValue> sizePart = r.take(size.parser, probe);
        List<ComponentValue> heightPart = sizePart != null && r.delim('/') ? r.take(lineHeight.parser, probe) : INITIAL;
        List<ComponentValue> familyPart = r.take(family.parser, probe);
        if (sizePart == null || heightPart == null || familyPart == null || !r.atEnd()) return null;
        out.put(size, sizePart);
        out.put(lineHeight, heightPart);
        out.put(family, familyPart);
        return out;
    }

    /** The line keywords go to both decoration longhands; colours, styles and thickness are accepted and ignored. */
    private static Map<Longhand, List<ComponentValue>> textDecoration(List<Longhand> l, List<ComponentValue> v) {
        List<ComponentValue> lines = new ArrayList<>();
        ValueContext probe = new ValueContext();
        for (ComponentValue item : ValueReader.items(v)) {
            String id = item instanceof Token t && t.is(Type.IDENT) ? t.lower : null;
            if (id != null && (id.equals("none") || Properties.DECORATION_LINES.contains(id))) lines.add(item);
            else if ((id == null || !DECORATION_STYLES.contains(id)) && CssColors.of(item, probe) == null
                    && Numeric.of(item, probe) == null) return null;
        }
        return lines.isEmpty() ? Map.of() : fill(l, lines);
    }

    /** {@code <start> [/ <end>]}; an omitted end copies a custom-ident start, else is auto. */
    private static void gridLines(String name, String start, String end) {
        register(name, longhands(start, end), values -> String.join(" / ", values), (l, v) -> {
            List<List<ComponentValue>> parts = ValueReader.splitSlashes(v);
            if (parts.size() > 2) return null;
            List<ComponentValue> s = parts.get(0);
            return Map.of(l.get(0), s, l.get(1), parts.size() > 1 ? parts.get(1) : isCustomIdent(s) ? s : AUTO);
        });
    }

    /** {@code <row-start> [/ <column-start> [/ <row-end> [/ <column-end>]]]}. */
    private static Map<Longhand, List<ComponentValue>> gridArea(List<Longhand> l, List<ComponentValue> v) {
        List<List<ComponentValue>> p = ValueReader.splitSlashes(v);
        if (p.size() > 4) return null;
        List<ComponentValue> rowStart = p.get(0);
        List<ComponentValue> colStart = p.size() > 1 ? p.get(1) : isCustomIdent(rowStart) ? rowStart : AUTO;
        List<ComponentValue> rowEnd = p.size() > 2 ? p.get(2) : isCustomIdent(rowStart) ? rowStart : AUTO;
        List<ComponentValue> colEnd = p.size() > 3 ? p.get(3) : isCustomIdent(colStart) ? colStart : AUTO;
        return Map.of(l.get(0), rowStart, l.get(1), colStart, l.get(2), rowEnd, l.get(3), colEnd);
    }

    /**
     * {@code none}, {@code <rows> / <columns>}, or the areas form: strings each optionally followed by a row size,
     * then {@code / <columns>}.
     */
    private static Map<Longhand, List<ComponentValue>> gridTemplate(List<Longhand> l, List<ComponentValue> v) {
        Longhand rowsL = l.get(0), columnsL = l.get(1), areasL = l.get(2);
        if (isIdent(v, "none")) return fill(l, v);
        List<List<ComponentValue>> parts = ValueReader.splitSlashes(v);
        if (parts.size() > 2) return null;
        List<ComponentValue> columns = parts.size() > 1 ? parts.get(1) : null;
        List<ComponentValue> areas = new ArrayList<>(), rows = new ArrayList<>();
        boolean sized = true;
        for (ComponentValue item : ValueReader.items(parts.get(0))) {
            if (item instanceof Token t && t.is(Type.STRING)) {
                if (!sized) rows.addAll(AUTO);
                areas.add(item);
                sized = false;
            } else if (!(item instanceof Block b && b.open() == '[')) {
                if (sized && !areas.isEmpty()) return null; // one size per row
                rows.add(item);
                sized = true;
            }
        }
        if (areas.isEmpty()) return columns == null ? null : Map.of(rowsL, parts.get(0), columnsL, columns);
        if (!sized) rows.addAll(AUTO);
        Map<Longhand, List<ComponentValue>> out = new HashMap<>(Map.of(rowsL, rows, areasL, areas));
        if (columns != null) out.put(columnsL, columns);
        return out;
    }

    /** {@code <x> <y> [<z>]}, keywords in either order; the z offset is dropped (2D only). */
    private static Map<Longhand, List<ComponentValue>> transformOrigin(List<Longhand> l, List<ComponentValue> v) {
        List<ComponentValue> items = ValueReader.items(v);
        if (items.size() == 3) {
            if (Numeric.of(items.get(2), new ValueContext()) == null) return null;
            items = items.subList(0, 2);
        }
        List<List<ComponentValue>> axes = items.size() > 2 ? null : Images.splitPosition(items);
        return axes == null ? null : Map.of(l.get(0), axes.get(0), l.get(1), axes.get(1));
    }

    private static Map<Longhand, List<ComponentValue>> backgroundPosition(List<Longhand> l, List<ComponentValue> v) {
        List<List<ComponentValue>> xs = new ArrayList<>(), ys = new ArrayList<>();
        for (List<ComponentValue> position : ValueReader.splitCommas(v)) {
            List<List<ComponentValue>> axes = Images.splitPosition(position);
            if (axes == null) return null;
            xs.add(axes.get(0));
            ys.add(axes.get(1));
        }
        return Map.of(BG_X, joinCommas(xs), BG_Y, joinCommas(ys));
    }

    /** Layers of {@code <image> || <position> [/ <size>] || <repeat> || <attachment> || <box>{1,2}}, then a colour. */
    private static Map<Longhand, List<ComponentValue>> background(List<Longhand> l, List<ComponentValue> v) {
        List<List<ComponentValue>> layers = ValueReader.splitCommas(v);
        List<Map<Longhand, List<ComponentValue>>> parsed = new ArrayList<>(layers.size());
        ValueContext probe = new ValueContext();
        for (int i = 0; i < layers.size(); i++) {
            Map<Longhand, List<ComponentValue>> layer = backgroundLayer(layers.get(i), i == layers.size() - 1, probe);
            if (layer == null) return null;
            parsed.add(layer);
        }
        Map<Longhand, List<ComponentValue>> out = joinItems(l.subList(1, l.size()), parsed);
        out.put(BG_COLOR, parsed.get(parsed.size() - 1).getOrDefault(BG_COLOR, INITIAL));
        return out;
    }

    private static Map<Longhand, List<ComponentValue>> backgroundLayer(List<ComponentValue> v, boolean last,
                                                                      ValueContext probe) {
        Map<Longhand, List<ComponentValue>> out = new HashMap<>();
        List<ComponentValue> box = null, part;
        ValueReader r = new ValueReader(v);
        while (!r.atEnd()) {
            int m = r.mark();
            if (!out.containsKey(BG_IMAGE) && (part = r.take(BG_IMAGE.parser, probe)) != null) {
                out.put(BG_IMAGE, part);
            } else if (!out.containsKey(BG_REPEAT) && (part = r.take(BG_REPEAT.parser, probe)) != null) {
                out.put(BG_REPEAT, part);
            } else if (r.ident("scroll") || r.ident("fixed") || r.ident("local")) {
                continue; // background-attachment is not supported
            } else if ((part = r.take(BG_CLIP.parser, probe)) != null) {
                box = part; // of two boxes, the second is the clip
            } else if (last && !out.containsKey(BG_COLOR) && (part = r.take(BG_COLOR.parser, probe)) != null) {
                out.put(BG_COLOR, part);
            } else if (!out.containsKey(BG_X) && takePosition(r, probe)) {
                List<ComponentValue> position = r.since(m);
                List<List<ComponentValue>> axes = Images.splitPosition(position);
                if (axes == null || Images.position(position, probe) == null) return null;
                out.put(BG_X, axes.get(0));
                out.put(BG_Y, axes.get(1));
                if (r.delim('/')) {
                    if ((part = r.take(BG_SIZE.parser, probe)) == null) return null;
                    out.put(BG_SIZE, part);
                }
            } else {
                return null;
            }
        }
        if (box != null) out.put(BG_CLIP, box);
        return out;
    }

    /** Consumes up to four position keywords and lengths. */
    private static boolean takePosition(ValueReader r, ValueContext probe) {
        int count = 0;
        while (count < 4 && !r.atEnd()) {
            String id = r.peekIdent();
            if (id != null && POSITION_KEYWORDS.contains(id)) r.next();
            else if (Numeric.length(r, probe, true) == null) break;
            count++;
        }
        return count > 0;
    }

    /**
     * {@code transition} and {@code animation}: comma-separated items whose parts come in any order; {@code slots}
     * name the components in the order they claim ambiguous values (the first time is the duration...).
     */
    private static void list(String name, ListGroup group, String... slots) {
        List<Slot> slotList = Arrays.stream(slots).map(Shorthands::slot).toList();
        register(name, Properties.components(group), (l, v) -> {
            ValueContext probe = new ValueContext();
            List<Map<Longhand, List<ComponentValue>>> items = new ArrayList<>();
            for (List<ComponentValue> item : ValueReader.splitCommas(v)) {
                Map<Longhand, List<ComponentValue>> parts = whole(item, slotList, probe);
                if (parts == null) return null;
                items.add(parts);
            }
            return joinItems(l, items);
        });
    }

    /**
     * Joins per-item values into comma-separated component values; an item that omits a component gets the
     * component's default entry ({@code 0s}, {@code ease}...), so the lists stay aligned.
     */
    private static Map<Longhand, List<ComponentValue>> joinItems(List<Longhand> components,
                                                                 List<Map<Longhand, List<ComponentValue>>> items) {
        Map<Longhand, List<ComponentValue>> out = new HashMap<>();
        for (Longhand c : components) {
            List<List<ComponentValue>> parts = new ArrayList<>(items.size());
            for (Map<Longhand, List<ComponentValue>> item : items) parts.add(item.getOrDefault(c, DEFAULT_ITEMS.get(c)));
            out.put(c, joinCommas(parts));
        }
        return out;
    }

    private static List<ComponentValue> joinCommas(List<List<ComponentValue>> parts) {
        List<ComponentValue> out = new ArrayList<>();
        for (List<ComponentValue> p : parts) {
            if (!out.isEmpty()) out.addAll(COMMA);
            out.addAll(p);
        }
        return out;
    }
}
