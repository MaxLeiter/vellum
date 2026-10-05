package dev.vellum.engine.css;

import dev.vellum.engine.css.ComponentValue.Func;
import dev.vellum.engine.css.Longhand.LineHeightFactor;
import dev.vellum.engine.css.Longhand.Parser;
import dev.vellum.engine.css.Token.Type;
import dev.vellum.engine.host.FontFamilies;
import dev.vellum.engine.style.Align;
import dev.vellum.engine.style.AnimationSpec;
import dev.vellum.engine.style.BackgroundLayer;
import dev.vellum.engine.style.BorderStyle;
import dev.vellum.engine.style.BoxSizing;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Cursor;
import dev.vellum.engine.style.Display;
import dev.vellum.engine.style.FlexDirection;
import dev.vellum.engine.style.FlexWrap;
import dev.vellum.engine.style.GridAutoFlow;
import dev.vellum.engine.style.GridLine;
import dev.vellum.engine.style.Image;
import dev.vellum.engine.style.ImageRendering;
import dev.vellum.engine.style.Length;
import dev.vellum.engine.style.ObjectFit;
import dev.vellum.engine.style.Overflow;
import dev.vellum.engine.style.PointerEvents;
import dev.vellum.engine.style.Position;
import dev.vellum.engine.style.Prop;
import dev.vellum.engine.style.TextAlign;
import dev.vellum.engine.style.TextOverflow;
import dev.vellum.engine.style.TextTransform;
import dev.vellum.engine.style.TimingFunction;
import dev.vellum.engine.style.TransformFunction;
import dev.vellum.engine.style.UserSelect;
import dev.vellum.engine.style.VerticalAlign;
import dev.vellum.engine.style.Visibility;
import dev.vellum.engine.style.WhiteSpace;
import dev.vellum.engine.style.WordBreak;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The longhand registry: every CSS longhand Vellum understands, with its value parser and serialiser. Shorthands are
 * in {@link Shorthands}.
 *
 * <p>Choices worth knowing: {@code thin}/{@code medium}/{@code thick} borders are 1/2/3px (Minecraft's GUI is
 * pixel art; vanilla bevels are 1–2px). Font-size keywords map onto sizes that keep Minecraft's 8px font crisp:
 * xx-small 4px, x-small 5px, small 6px, medium 8px, large 12px, x-large 16px, xx-large 24px, xxx-large 32px;
 * {@code larger}/{@code smaller} scale the parent size by 1.5.
 */
final class Properties {
    private static final Map<String, Longhand> BY_NAME = new HashMap<>();
    private static final List<Longhand> ALL = new ArrayList<>();
    private static final Longhand[] BY_PROP = new Longhand[Prop.values().length];

    // ---- Shared serialisers ----
    private static final Function<Object, String> TEXT = String::valueOf;
    private static final Function<Object, String> PX = v -> CssText.px((Float) v);
    private static final Function<Object, String> NUMBER = v -> CssText.number((Float) v);
    private static final Function<Object, String> COLOR = v -> CssColors.serialize((Integer) v);
    private static final Function<Object, String> KEYWORD = v -> Keywords.css((Enum<?>) v);

    // ---- Keyword tables ----
    private static final Map<String, Length> SIZE_KEYWORDS = Map.of("auto", Length.AUTO,
            "min-content", Length.MIN_CONTENT, "max-content", Length.MAX_CONTENT, "fit-content", Length.FIT_CONTENT);
    private static final Map<String, Length> MAX_SIZE_KEYWORDS = Map.of("none", Length.NONE,
            "min-content", Length.MIN_CONTENT, "max-content", Length.MAX_CONTENT, "fit-content", Length.FIT_CONTENT);
    private static final Map<String, Length> FLEX_BASIS_KEYWORDS = Map.of("auto", Length.AUTO, "content", Length.AUTO,
            "min-content", Length.MIN_CONTENT, "max-content", Length.MAX_CONTENT, "fit-content", Length.FIT_CONTENT);
    private static final Map<String, Length> AUTO = Map.of("auto", Length.AUTO);
    private static final Map<String, Length> NORMAL_GAP = Map.of("normal", Length.ZERO);
    private static final Map<String, Float> BORDER_WIDTHS = Map.of("thin", 1f, "medium", 2f, "thick", 3f);
    private static final Map<String, Float> NORMAL_SPACING = Map.of("normal", 0f);
    private static final Map<String, Float> FONT_SIZES = Map.of("xx-small", 4f, "x-small", 5f, "small", 6f,
            "medium", 8f, "large", 12f, "x-large", 16f, "xx-large", 24f, "xxx-large", 32f);
    private static final float FONT_SIZE_STEP = 1.5f;
    private static final Map<String, BackgroundLayer.Repeat> REPEATS =
            Keywords.table(BackgroundLayer.Repeat.class, Map.of());
    static final Set<String> DECORATION_LINES = Set.of("underline", "overline", "line-through", "blink");
    private static final Map<String, Align> ALIGN = Keywords.table(Align.class,
            Map.of("self-start", Align.START, "self-end", Align.END));
    private static final Map<String, Cursor> CURSORS = Keywords.table(Cursor.class, Map.ofEntries(
            Map.entry("progress", Cursor.WAIT), Map.entry("col-resize", Cursor.EW_RESIZE),
            Map.entry("e-resize", Cursor.EW_RESIZE), Map.entry("w-resize", Cursor.EW_RESIZE),
            Map.entry("row-resize", Cursor.NS_RESIZE), Map.entry("n-resize", Cursor.NS_RESIZE),
            Map.entry("s-resize", Cursor.NS_RESIZE), Map.entry("ne-resize", Cursor.NESW_RESIZE),
            Map.entry("sw-resize", Cursor.NESW_RESIZE), Map.entry("nw-resize", Cursor.NWSE_RESIZE),
            Map.entry("se-resize", Cursor.NWSE_RESIZE), Map.entry("all-scroll", Cursor.MOVE),
            Map.entry("vertical-text", Cursor.TEXT), Map.entry("no-drop", Cursor.NOT_ALLOWED),
            Map.entry("cell", Cursor.CROSSHAIR), Map.entry("context-menu", Cursor.DEFAULT),
            Map.entry("alias", Cursor.DEFAULT), Map.entry("copy", Cursor.DEFAULT), Map.entry("zoom-in", Cursor.DEFAULT),
            Map.entry("zoom-out", Cursor.DEFAULT)));

    static {
        registerBox();
        registerFlexAndGrid();
        registerText();
        registerDecoration();
        registerLists();
        for (Prop p : Prop.values()) {
            boolean derived = p == Prop.CUSTOM_PROPERTIES || BY_PROP[p.ordinal()] != null
                    || List.of(Prop.BACKGROUND_LAYERS, Prop.TRANSITION, Prop.ANIMATION).contains(p);
            if (!derived) throw new IllegalStateException("No CSS longhand for " + p);
        }
        // CSS initial values that differ from ComputedStyle's defaults.
        for (String side : List.of("top-", "right-", "bottom-", "left-", "")) {
            String prefix = side.isEmpty() ? "outline-" : "border-" + side;
            initial(prefix + "width", "medium");
            initial(prefix + "color", "currentcolor");
        }
        alias("overflow-wrap", "word-break");
        alias("word-wrap", "word-break");
        alias("-webkit-line-clamp", "line-clamp");
    }

    private Properties() {}

    /** The longhand with this CSS name (or alias), or null. */
    static Longhand longhand(String name) {
        return BY_NAME.get(name);
    }

    static Longhand of(Prop prop) {
        return BY_PROP[prop.ordinal()];
    }

    static List<Longhand> all() {
        return Collections.unmodifiableList(ALL);
    }

    /** The components of a list group, in component order. */
    static List<Longhand> components(ListGroup group) {
        List<Longhand> out = new ArrayList<>(group.size());
        for (Longhand l : ALL) if (l.group == group) out.add(l);
        return out;
    }

    // ---- Registration ----

    private static void add(Prop prop, Parser parser, Function<Object, String> serializer) {
        Longhand l = new Longhand(ALL.size(), prop.cssName, prop, null, 0, parser, serializer);
        ALL.add(l);
        BY_NAME.put(l.name, l);
        BY_PROP[prop.ordinal()] = l;
    }

    private static void add(List<Prop> props, Parser parser, Function<Object, String> serializer) {
        for (Prop p : props) add(p, parser, serializer);
    }

    private static void addComponent(String name, ListGroup group, Parser item, Function<Object, String> itemSerializer) {
        int component = (int) ALL.stream().filter(l -> l.group == group).count();
        Function<Object, String> serializer = v -> CssText.join((List<?>) v, ", ", itemSerializer);
        Longhand l = new Longhand(ALL.size(), name, null, group, component, listOf(item), serializer);
        ALL.add(l);
        BY_NAME.put(name, l);
    }

    private static void initial(String name, String css) {
        Longhand l = longhand(name);
        l.initial = Decl.initial(l, css);
    }

    private static void alias(String alias, String name) {
        BY_NAME.put(alias, BY_NAME.get(name));
    }

    private static void registerBox() {
        add(Prop.DISPLAY, Keywords.parser(Display.class, Map.of("flow-root", Display.BLOCK, "list-item", Display.BLOCK)),
                KEYWORD);
        add(Prop.POSITION, Keywords.parser(Position.class), KEYWORD);
        add(Prop.BOX_SIZING, Keywords.parser(BoxSizing.class), KEYWORD);
        add(List.of(Prop.WIDTH, Prop.HEIGHT, Prop.MIN_WIDTH, Prop.MIN_HEIGHT), length(SIZE_KEYWORDS, false), TEXT);
        add(List.of(Prop.MAX_WIDTH, Prop.MAX_HEIGHT), length(MAX_SIZE_KEYWORDS, false), TEXT);
        add(List.of(Prop.TOP, Prop.RIGHT, Prop.BOTTOM, Prop.LEFT, Prop.MARGIN_TOP, Prop.MARGIN_RIGHT,
                Prop.MARGIN_BOTTOM, Prop.MARGIN_LEFT), length(AUTO, true), TEXT);
        add(List.of(Prop.PADDING_TOP, Prop.PADDING_RIGHT, Prop.PADDING_BOTTOM, Prop.PADDING_LEFT),
                length(Map.of(), false), TEXT);
        Parser borderWidth = or(BORDER_WIDTHS, (r, ctx) -> Numeric.px(r, ctx, false));
        Parser borderStyle = Keywords.parser(BorderStyle.class);
        Parser radius = (r, ctx) -> {
            Length h = Numeric.length(r, ctx, false);
            if (h != null) Numeric.length(r, ctx, false); // the vertical radius of an elliptical corner is dropped
            return h;
        };
        add(List.of(Prop.BORDER_TOP_WIDTH, Prop.BORDER_RIGHT_WIDTH, Prop.BORDER_BOTTOM_WIDTH,
                Prop.BORDER_LEFT_WIDTH), borderWidth, PX);
        add(List.of(Prop.BORDER_TOP_STYLE, Prop.BORDER_RIGHT_STYLE, Prop.BORDER_BOTTOM_STYLE,
                Prop.BORDER_LEFT_STYLE), borderStyle, KEYWORD);
        add(List.of(Prop.BORDER_TOP_COLOR, Prop.BORDER_RIGHT_COLOR, Prop.BORDER_BOTTOM_COLOR,
                Prop.BORDER_LEFT_COLOR), CssColors::read, COLOR);
        add(List.of(Prop.BORDER_TOP_LEFT_RADIUS, Prop.BORDER_TOP_RIGHT_RADIUS, Prop.BORDER_BOTTOM_RIGHT_RADIUS,
                Prop.BORDER_BOTTOM_LEFT_RADIUS), radius, TEXT);
        Parser overflow = Keywords.parser(Overflow.class, Map.of("overlay", Overflow.AUTO));
        add(Prop.OVERFLOW_X, overflow, KEYWORD);
        add(Prop.OVERFLOW_Y, overflow, KEYWORD);
        add(Prop.Z_INDEX, or(Map.of("auto", Longhand.None.VALUE), Numeric::integer),
                v -> v == Longhand.None.VALUE ? "auto" : v.toString());
        add(Prop.OPACITY, (r, ctx) -> {
            Float f = Numeric.fraction(r, ctx);
            return f == null ? null : Math.max(0, Math.min(1, f));
        }, NUMBER);
        add(Prop.VISIBILITY, Keywords.parser(Visibility.class), KEYWORD);
        add(Prop.VERTICAL_ALIGN, Keywords.parser(VerticalAlign.class), KEYWORD);
        add(Prop.ASPECT_RATIO, Properties::aspectRatio, v -> Float.isNaN((Float) v) ? "auto" : CssText.number((Float) v));
        add(Prop.OBJECT_FIT, Keywords.parser(ObjectFit.class), KEYWORD);
    }

    private static void registerFlexAndGrid() {
        add(Prop.FLEX_DIRECTION, Keywords.parser(FlexDirection.class), KEYWORD);
        add(Prop.FLEX_WRAP, Keywords.parser(FlexWrap.class), KEYWORD);
        Parser align = (r, ctx) -> {
            if (!r.ident("safe")) r.ident("unsafe");
            if (r.ident("first") || r.ident("last")) return r.ident("baseline") ? Align.BASELINE : null;
            return Keywords.read(r, ALIGN);
        };
        add(List.of(Prop.JUSTIFY_CONTENT, Prop.ALIGN_ITEMS, Prop.ALIGN_CONTENT, Prop.ALIGN_SELF,
                Prop.JUSTIFY_ITEMS, Prop.JUSTIFY_SELF), align, KEYWORD);
        Parser nonNegative = (r, ctx) -> {
            Float f = Numeric.number(r, ctx);
            return f == null || f < 0 ? null : f;
        };
        add(Prop.FLEX_GROW, nonNegative, NUMBER);
        add(Prop.FLEX_SHRINK, nonNegative, NUMBER);
        add(Prop.FLEX_BASIS, length(FLEX_BASIS_KEYWORDS, false), TEXT);
        add(Prop.ORDER, Numeric::integer, TEXT);
        add(Prop.ROW_GAP, length(NORMAL_GAP, false), TEXT);
        add(Prop.COLUMN_GAP, length(NORMAL_GAP, false), TEXT);

        Parser template = or(Map.of("none", List.of()), (r, ctx) -> Grids.tracks(r, ctx, true));
        Function<Object, String> tracks = v -> Grids.serializeTracks(cast(v));
        add(Prop.GRID_TEMPLATE_COLUMNS, template, tracks);
        add(Prop.GRID_TEMPLATE_ROWS, template, tracks);
        add(Prop.GRID_TEMPLATE_AREAS, or(Map.of("none", Longhand.None.VALUE), (r, ctx) -> Grids.areas(r)),
                v -> Grids.serializeAreas(v == Longhand.None.VALUE ? null : cast(v)));
        add(Prop.GRID_AUTO_COLUMNS, (r, ctx) -> Grids.tracks(r, ctx, false), tracks);
        add(Prop.GRID_AUTO_ROWS, (r, ctx) -> Grids.tracks(r, ctx, false), tracks);
        add(Prop.GRID_AUTO_FLOW, Properties::gridAutoFlow, KEYWORD);
        add(List.of(Prop.GRID_COLUMN_START, Prop.GRID_COLUMN_END, Prop.GRID_ROW_START, Prop.GRID_ROW_END), Grids::line,
                v -> Grids.serializeLine((GridLine) v));
    }

    private static void registerText() {
        add(Prop.COLOR, CssColors::read, COLOR);
        add(Prop.FONT_FAMILY, Properties::fontFamily, v -> CssText.join(Properties.<String>cast(v), ", ", s -> s));
        add(Prop.FONT_SIZE, Properties::fontSize, PX);
        add(Prop.FONT_WEIGHT, Properties::fontWeight, TEXT);
        add(Prop.FONT_STYLE, Keywords.parser(Map.of("normal", false, "italic", true, "oblique", true)),
                v -> (Boolean) v ? "italic" : "normal");
        add(Prop.LINE_HEIGHT, Properties::lineHeight, v -> v instanceof LineHeightFactor f ? CssText.number(f.factor())
                : Float.isNaN((Float) v) ? "normal" : CssText.px((Float) v));
        Parser spacing = or(NORMAL_SPACING, (r, ctx) -> Numeric.px(r, ctx, true));
        add(Prop.LETTER_SPACING, spacing, PX);
        add(Prop.WORD_SPACING, spacing, PX);
        add(Prop.TEXT_INDENT, (r, ctx) -> Numeric.px(r, ctx, true), PX);
        add(Prop.TEXT_ALIGN, Keywords.parser(TextAlign.class), KEYWORD);
        add(Prop.TEXT_TRANSFORM, Keywords.parser(TextTransform.class), KEYWORD);
        add(Prop.TEXT_DECORATION_UNDERLINE, decorationLine("underline"), TEXT);
        add(Prop.TEXT_DECORATION_LINE_THROUGH, decorationLine("line-through"), TEXT);
        add(Prop.WHITE_SPACE, Keywords.parser(WhiteSpace.class), KEYWORD);
        add(Prop.WORD_BREAK, Keywords.parser(WordBreak.class, Map.of("keep-all", WordBreak.NORMAL,
                "anywhere", WordBreak.BREAK_WORD)), KEYWORD);
        add(Prop.TEXT_OVERFLOW, Keywords.parser(TextOverflow.class), KEYWORD);
        add(Prop.LINE_CLAMP, or(Map.of("none", 0), (r, ctx) -> {
            Integer n = Numeric.integer(r, ctx);
            return n == null || n < 1 ? null : n;
        }), v -> (Integer) v == 0 ? "none" : v.toString());
        add(Prop.TEXT_SHADOW, (r, ctx) -> Shadows.read(r, ctx, false), v -> Shadows.serialize(cast(v), false));
        add(Prop.CURSOR, Properties::cursor, KEYWORD);
        add(Prop.POINTER_EVENTS, Keywords.parser(PointerEvents.class, Map.of("all", PointerEvents.AUTO)), KEYWORD);
        add(Prop.USER_SELECT, Keywords.parser(UserSelect.class, Map.of("contain", UserSelect.AUTO)), KEYWORD);
        add(Prop.IMAGE_RENDERING, Keywords.parser(Map.of("auto", ImageRendering.SMOOTH, "smooth", ImageRendering.SMOOTH,
                "high-quality", ImageRendering.SMOOTH, "pixelated", ImageRendering.PIXELATED,
                "crisp-edges", ImageRendering.PIXELATED)), KEYWORD);
        add(Prop.ACCENT_COLOR, or(Map.of("auto", ComputedStyle.INITIAL.accentColor), CssColors::read), COLOR);
    }

    private static void registerDecoration() {
        add(Prop.BACKGROUND_COLOR, CssColors::read, COLOR);
        add(Prop.BOX_SHADOW, (r, ctx) -> Shadows.read(r, ctx, true), v -> Shadows.serialize(cast(v), true));
        add(Prop.OUTLINE_WIDTH, or(BORDER_WIDTHS, (r, ctx) -> Numeric.px(r, ctx, false)), PX);
        add(Prop.OUTLINE_STYLE, Keywords.parser(BorderStyle.class, Map.of("auto", BorderStyle.SOLID)), KEYWORD);
        add(Prop.OUTLINE_COLOR, CssColors::read, COLOR);
        add(Prop.OUTLINE_OFFSET, (r, ctx) -> Numeric.px(r, ctx, true), PX);
        add(Prop.TRANSFORM, Transforms::read, v -> Transforms.serialize(Properties.<TransformFunction>cast(v)));
        add(Prop.TRANSFORM_ORIGIN_X, (r, ctx) -> Images.axis(r, ctx, "left", "right"), TEXT);
        add(Prop.TRANSFORM_ORIGIN_Y, (r, ctx) -> Images.axis(r, ctx, "top", "bottom"), TEXT);
        add(Prop.TINT, CssColors::read, COLOR);
        add(Prop.SCROLL_BEHAVIOR, Keywords.parser(Map.of("auto", false, "smooth", true)),
                v -> (Boolean) v ? "smooth" : "auto");
        add(Prop.SCROLLBAR_WIDTH, Keywords.parser(Map.of("auto", 2, "thin", 1, "none", 0)),
                v -> List.of("none", "thin", "auto").get((Integer) v));
        add(Prop.SCROLLBAR_THUMB_COLOR, CssColors::read, COLOR);
        add(Prop.SCROLLBAR_TRACK_COLOR, CssColors::read, COLOR);
        add(Prop.CONTENT, Properties::content, v -> v == Longhand.None.VALUE ? "none" : CssText.string((String) v));
    }

    private static void registerLists() {
        ListGroup bg = ListGroup.BACKGROUND;
        addComponent("background-image", bg, or(Map.of("none", Longhand.None.VALUE), Images::read),
                v -> v == Longhand.None.VALUE ? "none" : Images.serialize((Image) v));
        addComponent("background-position-x", bg, (r, ctx) -> Images.axis(r, ctx, "left", "right"), TEXT);
        addComponent("background-position-y", bg, (r, ctx) -> Images.axis(r, ctx, "top", "bottom"), TEXT);
        addComponent("background-size", bg, Properties::backgroundSize, v -> {
            ListGroup.BackgroundSize s = (ListGroup.BackgroundSize) v;
            return s.keyword() != null ? s.keyword() : s.width() + " " + s.height();
        });
        addComponent("background-repeat", bg, Properties::backgroundRepeat, v -> {
            ListGroup.BackgroundRepeat rep = (ListGroup.BackgroundRepeat) v;
            return Keywords.css(rep.x()) + " " + Keywords.css(rep.y());
        });
        addComponent("background-clip", bg, Keywords.parser(BackgroundLayer.Box.class), KEYWORD);

        Parser time = Numeric::time;
        Function<Object, String> seconds = v -> CssText.seconds((Float) v);
        Function<Object, String> timing = v -> Timings.serialize((TimingFunction) v);
        ListGroup tr = ListGroup.TRANSITION;
        addComponent("transition-property", tr, (r, ctx) -> {
            Token t = r.next(Type.IDENT);
            return t == null ? null : t.lower;
        }, TEXT);
        addComponent("transition-duration", tr, nonNegativeTime(), seconds);
        addComponent("transition-timing-function", tr, Timings::read, timing);
        addComponent("transition-delay", tr, time, seconds);

        ListGroup an = ListGroup.ANIMATION;
        addComponent("animation-name", an, (r, ctx) -> {
            Token t = r.next(Type.IDENT);
            if (t == null) t = r.next(Type.STRING);
            return t == null ? null : t.is(Type.IDENT) && t.lower.equals("none") ? "none" : t.value;
        }, TEXT);
        addComponent("animation-duration", an, or(Map.of("auto", 0f), nonNegativeTime()), seconds);
        addComponent("animation-timing-function", an, Timings::read, timing);
        addComponent("animation-delay", an, time, seconds);
        addComponent("animation-iteration-count", an, or(Map.of("infinite", Float.POSITIVE_INFINITY), (r, ctx) -> {
            Float n = Numeric.number(r, ctx);
            return n == null || n < 0 ? null : n;
        }), v -> (Float) v == Float.POSITIVE_INFINITY ? "infinite" : CssText.number((Float) v));
        addComponent("animation-direction", an, Keywords.parser(AnimationSpec.Direction.class), KEYWORD);
        addComponent("animation-fill-mode", an, Keywords.parser(AnimationSpec.FillMode.class), KEYWORD);
        addComponent("animation-play-state", an, Keywords.parser(Map.of("running", false, "paused", true)),
                v -> (Boolean) v ? "paused" : "running");
    }

    // ---- Parser building blocks ----

    /** A keyword of {@code keywords}, else {@code fallback}. */
    static Parser or(Map<String, ?> keywords, Parser fallback) {
        return (r, ctx) -> {
            Object v = Keywords.read(r, keywords);
            return v != null ? v : fallback.parse(r, ctx);
        };
    }

    private static Parser length(Map<String, Length> keywords, boolean allowNegative) {
        return or(keywords, (r, ctx) -> Numeric.length(r, ctx, allowNegative));
    }

    /** A comma-separated list of {@code item}s. */
    private static Parser listOf(Parser item) {
        return (r, ctx) -> {
            List<Object> out = new ArrayList<>();
            do {
                Object v = item.parse(r, ctx);
                if (v == null) return null;
                out.add(v);
            } while (r.comma());
            return List.copyOf(out);
        };
    }

    private static Parser nonNegativeTime() {
        return (r, ctx) -> {
            Float t = Numeric.time(r, ctx);
            return t == null || t < 0 ? null : t;
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> cast(Object list) {
        return (List<T>) list;
    }

    // ---- Property-specific parsers ----

    private static Object aspectRatio(ValueReader r, ValueContext ctx) {
        boolean auto = r.ident("auto");
        Float w = Numeric.number(r, ctx);
        if (w == null) return auto ? Float.NaN : null;
        Float h = r.delim('/') ? Numeric.number(r, ctx) : Float.valueOf(1);
        if (!auto) r.ident("auto");
        return h == null || w <= 0 || h <= 0 ? null : w / h;
    }

    private static Object gridAutoFlow(ValueReader r, ValueContext ctx) {
        boolean dense = r.ident("dense");
        boolean column = r.ident("column");
        boolean row = !column && r.ident("row");
        if (!dense) dense = r.ident("dense");
        if (!dense && !column && !row) return null;
        return column ? (dense ? GridAutoFlow.COLUMN_DENSE : GridAutoFlow.COLUMN)
                : (dense ? GridAutoFlow.ROW_DENSE : GridAutoFlow.ROW);
    }

    /** Family names, quoted or as identifier sequences ({@code minecraft:uniform}); generics become Minecraft fonts. */
    private static Object fontFamily(ValueReader r, ValueContext ctx) {
        List<String> out = new ArrayList<>();
        do {
            Token s = r.next(Type.STRING);
            String name;
            if (s != null) {
                name = s.value;
            } else {
                int m = r.mark();
                while (r.peek() instanceof Token t && (t.is(Type.IDENT) || t.is(Type.COLON) || t.is(Type.DELIM)
                        || t.is(Type.NUMBER) || t.is(Type.DIMENSION))) r.next();
                name = ComponentValue.text(r.since(m)).replaceAll("\\s+", " ");
                if (name.isEmpty()) return null;
            }
            out.add(FontFamilies.computed(name));
        } while (r.comma());
        return List.copyOf(out);
    }

    /** Font size in px; em and % refer to the parent's size (the context's em during font-size). */
    private static Object fontSize(ValueReader r, ValueContext ctx) {
        Float keyword = Keywords.read(r, FONT_SIZES);
        if (keyword != null) return keyword;
        if (r.ident("larger")) return ctx.em() * FONT_SIZE_STEP;
        if (r.ident("smaller")) return ctx.em() / FONT_SIZE_STEP;
        Length l = Numeric.length(r, ctx, false);
        if (l == null) return null;
        return l.hasPercent() ? l.px + l.percent * ctx.em() / 100f : l.px;
    }

    /** {@code normal}, {@code bold}, {@code bolder}/{@code lighter} (relative to the parent) or 1–1000. */
    private static Object fontWeight(ValueReader r, ValueContext ctx) {
        if (r.ident("normal")) return 400;
        if (r.ident("bold")) return 700;
        boolean bolder = r.ident("bolder");
        if (bolder || r.ident("lighter")) {
            int p = ctx.parentFontWeight();
            if (bolder) return p < 350 ? 400 : p < 550 ? 700 : Math.max(p, 900);
            return p < 550 ? Math.min(p, 100) : p < 750 ? 400 : 700;
        }
        Float n = Numeric.number(r, ctx);
        return n == null || n < 1 || n > 1000 ? null : Math.round(n);
    }

    /** {@code normal} (NaN), a unitless factor (which inherits as a factor), or a length in px. */
    private static Object lineHeight(ValueReader r, ValueContext ctx) {
        if (r.ident("normal")) return Float.NaN;
        Float factor = Numeric.number(r, ctx);
        if (factor != null) return factor < 0 ? null : new LineHeightFactor(factor);
        Length l = Numeric.length(r, ctx, false);
        if (l == null) return null;
        return l.hasPercent() ? l.px + l.percent * ctx.em() / 100f : l.px;
    }

    /** A {@code text-decoration-line} value, as whether it contains {@code line}. */
    private static Parser decorationLine(String line) {
        return (r, ctx) -> {
            if (r.ident("none")) return false;
            Set<String> seen = new HashSet<>();
            while (r.peekIdent() != null && DECORATION_LINES.contains(r.peekIdent()) && seen.add(r.peekIdent())) r.next();
            return seen.isEmpty() ? null : seen.contains(line);
        };
    }

    /** {@code [<url> [x y]?,]* <keyword>}: images are skipped (hosts only have system cursors). */
    private static Object cursor(ValueReader r, ValueContext ctx) {
        while (Images.read(r, ctx) != null) {
            Float hotspotX = Numeric.number(r, ctx);
            if (hotspotX != null && Numeric.number(r, ctx) == null) return null;
            if (!r.comma()) return null;
        }
        return Keywords.read(r, CURSORS);
    }

    /** Generated content: strings, {@code attr()}, quotes; {@code counter()} renders as nothing. */
    private static Object content(ValueReader r, ValueContext ctx) {
        if (r.ident("normal") || r.ident("none")) return Longhand.None.VALUE;
        StringBuilder sb = new StringBuilder();
        while (!r.atEnd()) {
            ComponentValue v = r.next();
            if (v instanceof Token t && t.is(Type.STRING)) sb.append(t.value);
            else if (v instanceof Token t && (t.isIdent("open-quote") || t.isIdent("close-quote"))) sb.append('"');
            else if (v instanceof Token t && (t.isIdent("no-open-quote") || t.isIdent("no-close-quote"))) continue;
            else if (v instanceof Func f && f.name().equals("attr") && f.args().size() == 1
                    && f.args().get(0) instanceof Token a && a.is(Type.IDENT)) sb.append(ctx.attr(a.lower));
            else if (!(v instanceof Func f && (f.name().equals("counter") || f.name().equals("counters")))) return null;
        }
        return sb.toString();
    }

    private static Object backgroundSize(ValueReader r, ValueContext ctx) {
        if (r.ident("cover")) return new ListGroup.BackgroundSize("cover", Length.AUTO, Length.AUTO);
        if (r.ident("contain")) return new ListGroup.BackgroundSize("contain", Length.AUTO, Length.AUTO);
        Parser side = length(AUTO, false);
        Length w = (Length) side.parse(r, ctx);
        if (w == null) return null;
        Length h = (Length) side.parse(r, ctx);
        return new ListGroup.BackgroundSize(null, w, h == null ? Length.AUTO : h);
    }

    private static Object backgroundRepeat(ValueReader r, ValueContext ctx) {
        BackgroundLayer.Repeat repeat = BackgroundLayer.Repeat.REPEAT, noRepeat = BackgroundLayer.Repeat.NO_REPEAT;
        if (r.ident("repeat-x")) return new ListGroup.BackgroundRepeat(repeat, noRepeat);
        if (r.ident("repeat-y")) return new ListGroup.BackgroundRepeat(noRepeat, repeat);
        BackgroundLayer.Repeat x = Keywords.read(r, REPEATS);
        if (x == null) return null;
        BackgroundLayer.Repeat y = Keywords.read(r, REPEATS);
        return new ListGroup.BackgroundRepeat(x, y == null ? x : y);
    }
}
