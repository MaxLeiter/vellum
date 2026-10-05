package dev.vellum.engine.layout;

import dev.vellum.engine.style.Align;
import dev.vellum.engine.style.BoxSizing;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Display;
import dev.vellum.engine.style.FlexDirection;
import dev.vellum.engine.style.FlexWrap;
import dev.vellum.engine.style.GridAutoFlow;
import dev.vellum.engine.style.GridLine;
import dev.vellum.engine.style.GridTrack;
import dev.vellum.engine.style.Length;
import dev.vellum.engine.style.Overflow;
import dev.vellum.engine.style.Position;
import dev.vellum.engine.style.TextAlign;
import dev.vellum.engine.style.TextOverflow;
import dev.vellum.engine.style.TextTransform;
import dev.vellum.engine.style.VerticalAlign;
import dev.vellum.engine.style.WhiteSpace;
import dev.vellum.engine.style.WordBreak;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Test-only stand-in for the CSS engine: turns an inline-style-like string ({@code "display: flex; width: 10px"})
 * into a {@link ComputedStyle} inheriting from a parent. Supports just the properties the layout tests use.
 */
final class TestStyles {
    private TestStyles() {}

    /** A style for an element with UA-like defaults per tag (block for div/p..., none for head), then {@code css}. */
    static ComputedStyle forTag(String tag, String css, ComputedStyle parent) {
        ComputedStyle s = ComputedStyle.inheritFrom(parent);
        s.boxSizing = BoxSizing.BORDER_BOX;
        s.display = switch (tag) {
            case "html", "body", "div", "p", "section", "ul", "li", "h1", "h2", "h3" -> Display.BLOCK;
            case "head", "script", "style" -> Display.NONE;
            default -> Display.INLINE;
        };
        apply(s, css);
        return s;
    }

    /** Applies semicolon-separated declarations. */
    static ComputedStyle apply(ComputedStyle s, String css) {
        if (css == null) return s;
        for (String decl : css.split(";")) {
            int colon = decl.indexOf(':');
            if (colon < 0) continue;
            set(s, decl.substring(0, colon).trim().toLowerCase(Locale.ROOT), decl.substring(colon + 1).trim());
        }
        return s;
    }

    static void set(ComputedStyle s, String prop, String v) {
        switch (prop) {
            case "display" -> s.display = enumValue(Display.class, v);
            case "position" -> s.position = enumValue(Position.class, v);
            case "box-sizing" -> s.boxSizing = enumValue(BoxSizing.class, v);
            case "width" -> s.width = length(v, s);
            case "height" -> s.height = length(v, s);
            case "min-width" -> s.minWidth = length(v, s);
            case "min-height" -> s.minHeight = length(v, s);
            case "max-width" -> s.maxWidth = length(v, s);
            case "max-height" -> s.maxHeight = length(v, s);
            case "top" -> s.top = length(v, s);
            case "right" -> s.right = length(v, s);
            case "bottom" -> s.bottom = length(v, s);
            case "left" -> s.left = length(v, s);
            case "inset" -> {
                Length[] e = edges(v, s);
                s.top = e[0]; s.right = e[1]; s.bottom = e[2]; s.left = e[3];
            }
            case "margin" -> {
                Length[] e = edges(v, s);
                s.marginTop = e[0]; s.marginRight = e[1]; s.marginBottom = e[2]; s.marginLeft = e[3];
            }
            case "margin-top" -> s.marginTop = length(v, s);
            case "margin-right" -> s.marginRight = length(v, s);
            case "margin-bottom" -> s.marginBottom = length(v, s);
            case "margin-left" -> s.marginLeft = length(v, s);
            case "padding" -> {
                Length[] e = edges(v, s);
                s.paddingTop = e[0]; s.paddingRight = e[1]; s.paddingBottom = e[2]; s.paddingLeft = e[3];
            }
            case "padding-top" -> s.paddingTop = length(v, s);
            case "padding-right" -> s.paddingRight = length(v, s);
            case "padding-bottom" -> s.paddingBottom = length(v, s);
            case "padding-left" -> s.paddingLeft = length(v, s);
            case "border", "border-width" -> {
                Length[] e = edges(v.split("\\s+")[0].equals("solid") ? v.split("\\s+")[1] : v.split("\\s+")[0], s);
                s.borderTopWidth = e[0].px; s.borderRightWidth = e[1].px;
                s.borderBottomWidth = e[2].px; s.borderLeftWidth = e[3].px;
            }
            case "border-top" -> s.borderTopWidth = length(v.split("\\s+")[0], s).px;
            case "border-right" -> s.borderRightWidth = length(v.split("\\s+")[0], s).px;
            case "border-bottom" -> s.borderBottomWidth = length(v.split("\\s+")[0], s).px;
            case "border-left" -> s.borderLeftWidth = length(v.split("\\s+")[0], s).px;
            case "overflow" -> {
                String[] parts = v.split("\\s+");
                s.overflowX = enumValue(Overflow.class, parts[0]);
                s.overflowY = enumValue(Overflow.class, parts[parts.length - 1]);
            }
            case "overflow-x" -> s.overflowX = enumValue(Overflow.class, v);
            case "overflow-y" -> s.overflowY = enumValue(Overflow.class, v);
            case "aspect-ratio" -> {
                String[] parts = v.split("/");
                s.aspectRatio = parts.length == 2
                        ? Float.parseFloat(parts[0].trim()) / Float.parseFloat(parts[1].trim())
                        : v.equals("auto") ? Float.NaN : Float.parseFloat(v);
            }
            case "flex-direction" -> s.flexDirection = enumValue(FlexDirection.class, v);
            case "flex-wrap" -> s.flexWrap = enumValue(FlexWrap.class, v);
            case "justify-content" -> s.justifyContent = align(v);
            case "align-items" -> s.alignItems = align(v);
            case "align-content" -> s.alignContent = align(v);
            case "align-self" -> s.alignSelf = align(v);
            case "justify-items" -> s.justifyItems = align(v);
            case "justify-self" -> s.justifySelf = align(v);
            case "flex-grow" -> s.flexGrow = Float.parseFloat(v);
            case "flex-shrink" -> s.flexShrink = Float.parseFloat(v);
            case "flex-basis" -> s.flexBasis = length(v, s);
            case "flex" -> flex(s, v);
            case "order" -> s.order = Integer.parseInt(v);
            case "gap" -> {
                String[] parts = v.split("\\s+");
                s.rowGap = length(parts[0], s);
                s.columnGap = length(parts[parts.length - 1], s);
            }
            case "row-gap" -> s.rowGap = length(v, s);
            case "column-gap" -> s.columnGap = length(v, s);
            case "grid-template-columns" -> s.gridTemplateColumns = tracks(v, s);
            case "grid-template-rows" -> s.gridTemplateRows = tracks(v, s);
            case "grid-auto-columns" -> s.gridAutoColumns = tracks(v, s);
            case "grid-auto-rows" -> s.gridAutoRows = tracks(v, s);
            case "grid-auto-flow" -> s.gridAutoFlow = enumValue(GridAutoFlow.class, v.replace(' ', '-'));
            case "grid-template-areas" -> s.gridTemplateAreas = areas(v);
            case "grid-row-start" -> s.gridRowStart = line(v);
            case "grid-row-end" -> s.gridRowEnd = line(v);
            case "grid-column-start" -> s.gridColumnStart = line(v);
            case "grid-column-end" -> s.gridColumnEnd = line(v);
            case "grid-row" -> {
                String[] parts = v.split("/");
                s.gridRowStart = line(parts[0].trim());
                s.gridRowEnd = parts.length > 1 ? line(parts[1].trim()) : GridLine.AUTO;
            }
            case "grid-column" -> {
                String[] parts = v.split("/");
                s.gridColumnStart = line(parts[0].trim());
                s.gridColumnEnd = parts.length > 1 ? line(parts[1].trim()) : GridLine.AUTO;
            }
            case "grid-area" -> {
                GridLine l = line(v);
                s.gridRowStart = s.gridColumnStart = s.gridRowEnd = s.gridColumnEnd = l;
            }
            case "font-size" -> s.fontSize = length(v, s).px;
            case "font-weight" -> s.fontWeight = v.equals("bold") ? 700 : Integer.parseInt(v);
            case "line-height" -> {
                if (v.equals("normal")) s.lineHeight = Float.NaN;
                else if (v.endsWith("px")) s.lineHeight = Float.parseFloat(v.replace("px", ""));
                else s.lineHeight = Float.parseFloat(v) * s.fontSize;
            }
            case "letter-spacing" -> s.letterSpacing = length(v, s).px;
            case "word-spacing" -> s.wordSpacing = length(v, s).px;
            case "text-indent" -> s.textIndent = length(v, s).px;
            case "text-align" -> s.textAlign = enumValue(TextAlign.class, v);
            case "text-transform" -> s.textTransform = enumValue(TextTransform.class, v);
            case "white-space" -> s.whiteSpace = enumValue(WhiteSpace.class, v);
            case "word-break", "overflow-wrap" ->
                    s.wordBreak = enumValue(WordBreak.class, v.equals("anywhere") ? "break-word" : v);
            case "text-overflow" -> s.textOverflow = enumValue(TextOverflow.class, v);
            case "line-clamp", "-webkit-line-clamp" -> s.lineClamp = Integer.parseInt(v);
            case "vertical-align" -> s.verticalAlign = enumValue(VerticalAlign.class, v);
            case "content" -> s.content = v.replaceAll("^[\"']|[\"']$", "");
            case "direction", "scrollbar-width", "text-decoration" -> { }
            default -> throw new IllegalArgumentException("Unsupported test property: " + prop);
        }
    }

    private static void flex(ComputedStyle s, String v) {
        switch (v) {
            case "none" -> { s.flexGrow = 0; s.flexShrink = 0; s.flexBasis = Length.AUTO; }
            case "auto" -> { s.flexGrow = 1; s.flexShrink = 1; s.flexBasis = Length.AUTO; }
            default -> {
                String[] parts = v.split("\\s+");
                s.flexGrow = Float.parseFloat(parts[0]);
                s.flexShrink = parts.length > 1 && !parts[1].matches(".*[a-z%].*") ? Float.parseFloat(parts[1]) : 1;
                boolean secondIsBasis = parts.length == 2 && parts[1].matches(".*[a-z%].*");
                String basis = parts.length > 2 ? parts[2] : secondIsBasis ? parts[1] : "0%";
                s.flexBasis = length(basis, s);
            }
        }
    }

    static Length length(String v, ComputedStyle s) {
        v = v.trim();
        return switch (v) {
            case "auto" -> Length.AUTO;
            case "none" -> Length.NONE;
            case "min-content" -> Length.MIN_CONTENT;
            case "max-content" -> Length.MAX_CONTENT;
            case "fit-content" -> Length.FIT_CONTENT;
            default -> {
                if (v.startsWith("calc(")) {
                    // calc(P% - Npx) / calc(P% + Npx)
                    String inner = v.substring(5, v.length() - 1).replace(" ", "");
                    int split = Math.max(inner.lastIndexOf('+'), inner.lastIndexOf('-'));
                    Length a = length(inner.substring(0, split), s);
                    Length b = length(inner.substring(split + 1), s);
                    float sign = inner.charAt(split) == '-' ? -1 : 1;
                    yield Length.of(a.px + sign * b.px, a.percent + sign * b.percent);
                }
                if (v.endsWith("%")) yield Length.percent(Float.parseFloat(v.substring(0, v.length() - 1)));
                if (v.endsWith("em") && !v.endsWith("rem")) {
                    yield Length.px(Float.parseFloat(v.substring(0, v.length() - 2)) * s.fontSize);
                }
                if (v.endsWith("px")) yield Length.px(Float.parseFloat(v.substring(0, v.length() - 2)));
                yield Length.px(Float.parseFloat(v));
            }
        };
    }

    private static Length[] edges(String v, ComputedStyle s) {
        String[] p = v.trim().split("\\s+");
        Length a = length(p[0], s);
        Length b = p.length > 1 ? length(p[1], s) : a;
        Length c = p.length > 2 ? length(p[2], s) : a;
        Length d = p.length > 3 ? length(p[3], s) : b;
        return new Length[] {a, b, c, d};
    }

    static Align align(String v) {
        return switch (v) {
            case "start" -> Align.START;
            case "end" -> Align.END;
            default -> enumValue(Align.class, v);
        };
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String v) {
        return Enum.valueOf(type, v.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }

    // ---- Grid ----

    static List<GridTrack> tracks(String v, ComputedStyle s) {
        List<GridTrack> out = new ArrayList<>();
        if (v.equals("none")) return out;
        for (String token : splitTopLevel(v)) {
            if (token.startsWith("repeat(")) {
                List<String> args = splitArgs(token.substring(7, token.length() - 1));
                List<GridTrack> inner = new ArrayList<>();
                for (String t : splitTopLevel(args.get(1))) inner.add(track(t, s));
                String count = args.get(0).trim();
                if (count.equals("auto-fill") || count.equals("auto-fit")) {
                    out.add(new GridTrack.Repeat(count.equals("auto-fill") ? GridTrack.RepeatKind.AUTO_FILL
                            : GridTrack.RepeatKind.AUTO_FIT, 0, inner, List.of()));
                } else {
                    for (int i = 0; i < Integer.parseInt(count); i++) out.addAll(inner);
                }
            } else {
                out.add(track(token, s));
            }
        }
        return out;
    }

    private static GridTrack track(String t, ComputedStyle s) {
        t = t.trim();
        if (t.endsWith("fr")) return new GridTrack.Flex(Float.parseFloat(t.substring(0, t.length() - 2)));
        if (t.startsWith("minmax(")) {
            List<String> args = splitArgs(t.substring(7, t.length() - 1));
            return new GridTrack.MinMax(track(args.get(0), s), track(args.get(1), s));
        }
        if (t.startsWith("fit-content(")) return new GridTrack.FitContent(length(t.substring(12, t.length() - 1), s));
        Length l = length(t, s);
        return l.isFixed() ? new GridTrack.Fixed(l) : new GridTrack.Keyword(l);
    }

    private static List<String> splitTopLevel(String v) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        v = v.trim();
        for (int i = 0; i <= v.length(); i++) {
            char c = i < v.length() ? v.charAt(i) : ' ';
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ' ' && depth == 0) {
                if (i > start) out.add(v.substring(start, i));
                start = i + 1;
            }
        }
        return out;
    }

    private static List<String> splitArgs(String v) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                out.add(v.substring(start, i).trim());
                start = i + 1;
            }
        }
        out.add(v.substring(start).trim());
        return out;
    }

    private static List<List<String>> areas(String v) {
        List<List<String>> rows = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([^\"]*)\"").matcher(v);
        while (m.find()) rows.add(List.of(m.group(1).trim().split("\\s+")));
        return rows;
    }

    static GridLine line(String v) {
        v = v.trim();
        if (v.equals("auto")) return GridLine.AUTO;
        if (v.startsWith("span")) return GridLine.span(Integer.parseInt(v.substring(4).trim()));
        if (v.matches("-?\\d+")) return GridLine.line(Integer.parseInt(v));
        return GridLine.named(v);
    }
}
