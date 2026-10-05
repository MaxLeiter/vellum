package dev.vellum.engine.css;

import dev.vellum.engine.css.ComponentValue.Func;
import dev.vellum.engine.css.Token.Type;
import dev.vellum.engine.style.Image;
import dev.vellum.engine.style.Length;

import java.util.ArrayList;
import java.util.List;

/**
 * Image values ({@code url()}, {@code sprite()}, linear and radial gradients) and {@code <position>} values (for
 * {@code background-position}, {@code transform-origin} and gradient centres).
 */
final class Images {
    private Images() {}

    /** Reads one image, or returns null without consuming anything. {@code none} is the caller's business. */
    static Image read(ValueReader r, ValueContext ctx) {
        int m = r.mark();
        ComponentValue v = r.next();
        Image image = null;
        if (v instanceof Token t && t.is(Type.URL)) image = Image.ofUrl(t.value, ctx::resolveUrl);
        else if (v instanceof Func f) image = function(f, ctx);
        if (image == null) r.reset(m);
        return image;
    }

    private static Image function(Func f, ValueContext ctx) {
        return switch (f.name()) {
            case "url" -> f.args().size() == 1 && f.args().get(0) instanceof Token s && s.is(Type.STRING)
                    ? Image.ofUrl(s.value, ctx::resolveUrl) : null;
            case "sprite" -> sprite(f.args());
            case "linear-gradient" -> linear(f, ctx, false);
            case "repeating-linear-gradient" -> linear(f, ctx, true);
            case "radial-gradient" -> radial(f, ctx);
            default -> null;
        };
    }

    /** {@code sprite(minecraft:widget/button)} or {@code sprite("minecraft:widget/button")}. */
    private static Image sprite(List<ComponentValue> args) {
        List<ComponentValue> a = CssParser.trim(args);
        if (a.isEmpty()) return null;
        if (a.size() == 1 && a.get(0) instanceof Token s && s.is(Type.STRING)) return new Image.Sprite(s.value);
        for (ComponentValue v : a) if (!(v instanceof Token t) || t.is(Type.WHITESPACE)) return null;
        return new Image.Sprite(ComponentValue.text(a));
    }

    private static Image linear(Func f, ValueContext ctx, boolean repeating) {
        List<List<ComponentValue>> parts = ValueReader.splitCommas(f.args());
        ValueReader first = new ValueReader(parts.get(0));
        float angle = 180;
        boolean direction = true;
        Float a = Numeric.angle(first, ctx);
        if (a != null) angle = a;
        else if (first.ident("to")) angle = sideAngle(first);
        else direction = false;
        if (direction && (Float.isNaN(angle) || !first.atEnd())) return null;
        List<Image.ColorStop> stops = stops(parts.subList(direction ? 1 : 0, parts.size()), ctx);
        return stops == null ? null : new Image.LinearGradient(angle, stops, repeating);
    }

    /**
     * The angle for {@code to <side-or-corner>}. Corners use 45° multiples (CSS points the line at the corner, which
     * depends on the box's aspect ratio; the approximation is exact for square boxes). NaN if malformed.
     */
    private static float sideAngle(ValueReader r) {
        float x = Float.NaN, y = Float.NaN;
        for (int i = 0; i < 2; i++) {
            String id = r.peekIdent();
            if (id == null) break;
            switch (id) {
                case "left" -> x = 270;
                case "right" -> x = 90;
                case "top" -> y = 0;
                case "bottom" -> y = 180;
                default -> { return Float.NaN; }
            }
            r.next();
        }
        if (Float.isNaN(x)) return y;
        if (Float.isNaN(y)) return x;
        if (y == 0) return x == 90 ? 45 : 315;
        return x == 90 ? 135 : 225;
    }

    /**
     * {@code radial-gradient([circle | ellipse] [<size>] [at <position>], stops)}. Sizes are accepted but the
     * gradient always uses farthest-corner.
     */
    private static Image radial(Func f, ValueContext ctx) {
        List<List<ComponentValue>> parts = ValueReader.splitCommas(f.args());
        ValueReader r = new ValueReader(parts.get(0));
        boolean circle = false, shape = false;
        Length[] center = {Length.PERCENT_50, Length.PERCENT_50};
        while (!r.atEnd() && !"at".equals(r.peekIdent())) {
            String id = r.peekIdent();
            if ("circle".equals(id) || "ellipse".equals(id)) {
                circle = id.equals("circle");
                r.next();
            } else if (id != null && id.matches("(closest|farthest)-(side|corner)")) {
                r.next();
            } else if (Numeric.length(r, ctx, false) == null) {
                break;
            }
            shape = true;
        }
        if (r.ident("at")) {
            center = position(r.rest(), ctx);
            if (center == null) return null;
            shape = true;
        }
        if (shape && !r.atEnd()) return null;
        List<Image.ColorStop> stops = stops(parts.subList(shape ? 1 : 0, parts.size()), ctx);
        return stops == null ? null : new Image.RadialGradient(circle, center[0], center[1], stops);
    }

    /** Colour stops ({@code color [pos [pos]]}); transition hints (a bare position) are ignored. */
    private static List<Image.ColorStop> stops(List<List<ComponentValue>> parts, ValueContext ctx) {
        List<Image.ColorStop> stops = new ArrayList<>();
        for (List<ComponentValue> part : parts) {
            ValueReader r = new ValueReader(part);
            Length hint = Numeric.length(r, ctx, true);
            if (hint != null && r.atEnd()) continue;
            Integer color = CssColors.read(r, ctx);
            if (color == null) return null;
            Length p1 = Numeric.length(r, ctx, true);
            Length p2 = p1 == null ? null : Numeric.length(r, ctx, true);
            if (!r.atEnd()) return null;
            stops.add(new Image.ColorStop(color, p1));
            if (p2 != null) stops.add(new Image.ColorStop(color, p2));
        }
        return stops.size() >= 2 ? List.copyOf(stops) : null;
    }

    // ---- <position> ----

    /** A whole {@code <position>} as {x, y}, or null. */
    static Length[] position(List<ComponentValue> values, ValueContext ctx) {
        List<List<ComponentValue>> axes = splitPosition(values);
        if (axes == null) return null;
        ValueReader xr = new ValueReader(axes.get(0)), yr = new ValueReader(axes.get(1));
        Length x = axis(xr, ctx, "left", "right"), y = axis(yr, ctx, "top", "bottom");
        return x == null || y == null || !xr.atEnd() || !yr.atEnd() ? null : new Length[] {x, y};
    }

    /**
     * Splits a {@code <position>} (1 to 4 values) into the values for the x axis and for the y axis, by syntax
     * alone, so each axis can be computed as its own longhand. A missing axis gets {@code center}.
     */
    static List<List<ComponentValue>> splitPosition(List<ComponentValue> values) {
        List<ComponentValue> items = ValueReader.items(values);
        if (items.isEmpty() || items.size() > 4) return null;
        List<ComponentValue> x = null, y = null;
        if (items.size() <= 2) {
            ComponentValue a = items.get(0), b = items.size() > 1 ? items.get(1) : null;
            boolean swap = isKeyword(a, "top", "bottom") || isKeyword(b, "left", "right");
            if (b == null) {
                if (swap) y = List.of(a);
                else x = List.of(a);
            } else {
                x = List.of(swap ? b : a);
                y = List.of(swap ? a : b);
            }
        } else {
            // Keyword/offset pairs ("right 10px top", "left 5% bottom 2px"); center takes the axis left free.
            List<ComponentValue> center = null;
            for (int i = 0; i < items.size(); ) {
                ComponentValue kw = items.get(i++);
                List<ComponentValue> entry = new ArrayList<>(List.of(kw));
                if (i < items.size() && !(items.get(i) instanceof Token t && t.is(Type.IDENT))) entry.add(items.get(i++));
                if (isKeyword(kw, "left", "right") && x == null) x = entry;
                else if (isKeyword(kw, "top", "bottom") && y == null) y = entry;
                else if (isKeyword(kw, "center") && entry.size() == 1 && center == null) center = entry;
                else return null;
            }
            if (center != null) {
                if (x == null) x = center;
                else if (y == null) y = center;
                else return null;
            }
        }
        return List.of(x == null ? List.of(CENTER) : x, y == null ? List.of(CENTER) : y);
    }

    private static final ComponentValue CENTER = CssParser.parseComponentValues("center").get(0);

    private static boolean isKeyword(ComponentValue v, String... names) {
        if (!(v instanceof Token t) || !t.is(Type.IDENT)) return false;
        for (String n : names) if (t.lower.equals(n)) return true;
        return false;
    }

    /**
     * One axis of a position: {@code center}, {@code <start> [offset]}, {@code <end> [offset]} or a length.
     * An offset from the end edge becomes {@code calc(100% - offset)}.
     */
    static Length axis(ValueReader r, ValueContext ctx, String startKeyword, String endKeyword) {
        if (r.ident("center")) return Length.PERCENT_50;
        boolean start = r.ident(startKeyword);
        boolean end = !start && r.ident(endKeyword);
        Length offset = Numeric.length(r, ctx, true);
        if (end) return offset == null ? Length.PERCENT_100 : Length.sum(Length.PERCENT_100, offset.times(-1));
        if (start) return offset == null ? Length.ZERO : offset;
        return offset;
    }

    static String serialize(Image image) {
        return switch (image) {
            case Image.Url u -> "url(" + CssText.string(u.url()) + ")";
            case Image.Sprite s -> "sprite(" + CssText.string(s.id()) + ")";
            case Image.Canvas c -> "url(" + CssText.string(Image.CANVAS_SCHEME + c.id()) + ")";
            case Image.LinearGradient g -> (g.repeating() ? "repeating-" : "") + "linear-gradient("
                    + CssText.deg(g.angleDeg()) + ", " + stops(g.stops()) + ")";
            case Image.RadialGradient g -> "radial-gradient(" + (g.circle() ? "circle" : "ellipse") + " at "
                    + g.centerX() + " " + g.centerY() + ", " + stops(g.stops()) + ")";
        };
    }

    private static String stops(List<Image.ColorStop> stops) {
        return CssText.join(stops, ", ",
                s -> CssColors.serialize(s.color()) + (s.position() == null ? "" : " " + s.position()));
    }
}
