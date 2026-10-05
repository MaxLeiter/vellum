package dev.vellum.engine.css;

import dev.vellum.engine.css.ComponentValue.Func;
import dev.vellum.engine.css.Numeric.Kind;
import dev.vellum.engine.css.Numeric.Quantity;
import dev.vellum.engine.css.Token.Type;
import dev.vellum.engine.style.Colors;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Colour values: {@code #rgb #rgba #rrggbb #rrggbbaa}, {@code rgb()/rgba()} and {@code hsl()/hsla()} (comma and space
 * syntax, {@code / alpha}), the CSS named colours, Minecraft's chat colours ({@code mc-gold}...), {@code transparent},
 * {@code currentColor} and {@code color-mix()} (always mixed in sRGB, whatever space is named).
 */
final class CssColors {
    private static final Map<String, Integer> NAMED = new HashMap<>();

    static {
        String css = "aliceblue f0f8ff antiquewhite faebd7 aqua 00ffff aquamarine 7fffd4 azure f0ffff beige f5f5dc "
                + "bisque ffe4c4 black 000000 blanchedalmond ffebcd blue 0000ff blueviolet 8a2be2 brown a52a2a "
                + "burlywood deb887 cadetblue 5f9ea0 chartreuse 7fff00 chocolate d2691e coral ff7f50 "
                + "cornflowerblue 6495ed cornsilk fff8dc crimson dc143c cyan 00ffff darkblue 00008b darkcyan 008b8b "
                + "darkgoldenrod b8860b darkgray a9a9a9 darkgreen 006400 darkgrey a9a9a9 darkkhaki bdb76b "
                + "darkmagenta 8b008b darkolivegreen 556b2f darkorange ff8c00 darkorchid 9932cc darkred 8b0000 "
                + "darksalmon e9967a darkseagreen 8fbc8f darkslateblue 483d8b darkslategray 2f4f4f "
                + "darkslategrey 2f4f4f darkturquoise 00ced1 darkviolet 9400d3 deeppink ff1493 deepskyblue 00bfff "
                + "dimgray 696969 dimgrey 696969 dodgerblue 1e90ff firebrick b22222 floralwhite fffaf0 "
                + "forestgreen 228b22 fuchsia ff00ff gainsboro dcdcdc ghostwhite f8f8ff gold ffd700 goldenrod daa520 "
                + "gray 808080 green 008000 greenyellow adff2f grey 808080 honeydew f0fff0 hotpink ff69b4 "
                + "indianred cd5c5c indigo 4b0082 ivory fffff0 khaki f0e68c lavender e6e6fa lavenderblush fff0f5 "
                + "lawngreen 7cfc00 lemonchiffon fffacd lightblue add8e6 lightcoral f08080 lightcyan e0ffff "
                + "lightgoldenrodyellow fafad2 lightgray d3d3d3 lightgreen 90ee90 lightgrey d3d3d3 lightpink ffb6c1 "
                + "lightsalmon ffa07a lightseagreen 20b2aa lightskyblue 87cefa lightslategray 778899 "
                + "lightslategrey 778899 lightsteelblue b0c4de lightyellow ffffe0 lime 00ff00 limegreen 32cd32 "
                + "linen faf0e6 magenta ff00ff maroon 800000 mediumaquamarine 66cdaa mediumblue 0000cd "
                + "mediumorchid ba55d3 mediumpurple 9370db mediumseagreen 3cb371 mediumslateblue 7b68ee "
                + "mediumspringgreen 00fa9a mediumturquoise 48d1cc mediumvioletred c71585 midnightblue 191970 "
                + "mintcream f5fffa mistyrose ffe4e1 moccasin ffe4b5 navajowhite ffdead navy 000080 oldlace fdf5e6 "
                + "olive 808000 olivedrab 6b8e23 orange ffa500 orangered ff4500 orchid da70d6 palegoldenrod eee8aa "
                + "palegreen 98fb98 paleturquoise afeeee palevioletred db7093 papayawhip ffefd5 peachpuff ffdab9 "
                + "peru cd853f pink ffc0cb plum dda0dd powderblue b0e0e6 purple 800080 rebeccapurple 663399 "
                + "red ff0000 rosybrown bc8f8f royalblue 4169e1 saddlebrown 8b4513 salmon fa8072 sandybrown f4a460 "
                + "seagreen 2e8b57 seashell fff5ee sienna a0522d silver c0c0c0 skyblue 87ceeb slateblue 6a5acd "
                + "slategray 708090 slategrey 708090 snow fffafa springgreen 00ff7f steelblue 4682b4 tan d2b48c "
                + "teal 008080 thistle d8bfd8 tomato ff6347 turquoise 40e0d0 violet ee82ee wheat f5deb3 "
                + "white ffffff whitesmoke f5f5f5 yellow ffff00 yellowgreen 9acd32 "
                // Minecraft's chat formatting colours (ChatFormatting).
                + "mc-black 000000 mc-dark-blue 0000aa mc-dark-green 00aa00 mc-dark-aqua 00aaaa mc-dark-red aa0000 "
                + "mc-dark-purple aa00aa mc-gold ffaa00 mc-gray aaaaaa mc-dark-gray 555555 mc-blue 5555ff "
                + "mc-green 55ff55 mc-aqua 55ffff mc-red ff5555 mc-light-purple ff55ff mc-yellow ffff55 mc-white ffffff";
        String[] parts = css.split(" ");
        for (int i = 0; i < parts.length; i += 2) NAMED.put(parts[i], 0xFF000000 | Integer.parseInt(parts[i + 1], 16));
        NAMED.put("transparent", Colors.TRANSPARENT);
    }

    private CssColors() {}

    /** Reads one colour, or returns null without consuming anything. */
    static Integer read(ValueReader r, ValueContext ctx) {
        int m = r.mark();
        Integer c = of(r.next(), ctx);
        if (c == null) r.reset(m);
        return c;
    }

    static Integer of(ComponentValue v, ValueContext ctx) {
        if (v instanceof Token t) {
            if (t.is(Type.HASH)) return hex(t.value);
            if (t.isIdent("currentcolor")) return ctx.currentColor();
            if (t.is(Type.IDENT)) return NAMED.get(t.lower);
            return null;
        }
        if (v instanceof Func f) {
            return switch (f.name()) {
                case "rgb", "rgba" -> rgb(f, ctx);
                case "hsl", "hsla" -> hsl(f, ctx);
                case "color-mix" -> mix(f, ctx);
                default -> null;
            };
        }
        return null;
    }

    /** {@code rgb(r, g, b)} or {@code rgba(r, g, b, a)}, the form getComputedStyle uses. */
    static String serialize(int argb) {
        String rgb = Colors.red(argb) + ", " + Colors.green(argb) + ", " + Colors.blue(argb);
        if (Colors.alpha(argb) == 255) return "rgb(" + rgb + ")";
        return "rgba(" + rgb + ", " + CssText.number(Math.round(Colors.alpha(argb) / 255f * 1000) / 1000f) + ")";
    }

    private static Integer hex(String h) {
        for (int i = 0; i < h.length(); i++) if (Character.digit(h.charAt(i), 16) < 0) return null;
        return switch (h.length()) {
            case 3, 4 -> {
                int[] c = new int[4];
                c[3] = 15;
                for (int i = 0; i < h.length(); i++) c[i] = Character.digit(h.charAt(i), 16);
                yield Colors.argb(c[3] * 17, c[0] * 17, c[1] * 17, c[2] * 17);
            }
            case 6 -> 0xFF000000 | Integer.parseInt(h, 16);
            case 8 -> {
                int rgba = (int) Long.parseLong(h, 16);
                yield (rgba >>> 8) | (rgba << 24);
            }
            default -> null;
        };
    }

    /**
     * Splits a colour function's arguments into channel values: the comma syntax {@code (a, b, c[, alpha])} or the
     * space syntax {@code (a b c [/ alpha])}. Returns 3 or 4 values (null for {@code none}), or null if malformed.
     */
    private static List<Quantity> channels(Func f, ValueContext ctx) {
        List<List<ComponentValue>> parts = ValueReader.splitCommas(f.args());
        List<Quantity> out = new ArrayList<>(4);
        if (parts.size() > 1) {
            if (parts.size() > 4) return null;
            for (List<ComponentValue> part : parts) {
                Quantity q = part.size() == 1 ? Numeric.of(part.get(0), ctx) : null;
                if (q == null) return null;
                out.add(q);
            }
        } else {
            ValueReader r = new ValueReader(parts.get(0));
            while (!r.atEnd()) {
                if (out.size() == 4 || (out.size() == 3 && !r.delim('/'))) return null;
                if (r.ident("none")) {
                    out.add(null);
                    continue;
                }
                Quantity q = Numeric.of(r.next(), ctx);
                if (q == null) return null;
                out.add(q);
            }
        }
        return out.size() >= 3 ? out : null;
    }

    private static Integer rgb(Func f, ValueContext ctx) {
        List<Quantity> ch = channels(f, ctx);
        if (ch == null) return null;
        int[] rgb = new int[3];
        for (int i = 0; i < 3; i++) {
            Quantity q = ch.get(i);
            if (q != null && q.kind() != Kind.NUMBER && !q.isPercentage()) return null;
            rgb[i] = q == null ? 0 : Math.round(q.kind() == Kind.NUMBER ? q.value() : q.percent() * 2.55f);
        }
        Integer a = alpha(ch);
        return a == null ? null : Colors.argb(a, rgb[0], rgb[1], rgb[2]);
    }

    private static Integer hsl(Func f, ValueContext ctx) {
        List<Quantity> ch = channels(f, ctx);
        if (ch == null) return null;
        Quantity hq = ch.get(0);
        if (hq != null && hq.kind() != Kind.NUMBER && hq.kind() != Kind.ANGLE) return null;
        float h = hq == null ? 0 : hq.value();
        float[] sl = new float[2];
        for (int i = 0; i < 2; i++) {
            Quantity q = ch.get(i + 1);
            if (q != null && q.kind() != Kind.NUMBER && !q.isPercentage()) return null;
            sl[i] = q == null ? 0 : Math.max(0, Math.min(100, q.kind() == Kind.NUMBER ? q.value() : q.percent())) / 100f;
        }
        Integer a = alpha(ch);
        if (a == null) return null;
        // CSS Color 4 §7.1 hslToRgb.
        float hue = ((h % 360) + 360) % 360;
        int[] rgb = new int[3];
        int[] n = {0, 8, 4};
        for (int i = 0; i < 3; i++) {
            float k = (n[i] + hue / 30f) % 12;
            float amount = sl[0] * Math.min(sl[1], 1 - sl[1]);
            rgb[i] = Math.round(255 * (sl[1] - amount * Math.max(-1, Math.min(Math.min(k - 3, 9 - k), 1))));
        }
        return Colors.argb(a, rgb[0], rgb[1], rgb[2]);
    }

    /** The alpha channel (0..255) from the optional fourth channel; null if it is not a number or percentage. */
    private static Integer alpha(List<Quantity> ch) {
        if (ch.size() < 4 || ch.get(3) == null) return 255;
        Quantity q = ch.get(3);
        if (q.kind() == Kind.NUMBER) return Math.round(Math.max(0, Math.min(1, q.value())) * 255);
        if (q.isPercentage()) return Math.round(Math.max(0, Math.min(100, q.percent())) * 2.55f);
        return null;
    }

    /** {@code color-mix(in <space>, a [p%], b [q%])}, interpolated in premultiplied sRGB. */
    private static Integer mix(Func f, ValueContext ctx) {
        List<List<ComponentValue>> args = ValueReader.splitCommas(f.args());
        if (args.size() != 3) return null;
        ValueReader space = new ValueReader(args.get(0));
        if (!space.ident("in") || space.next() == null || !space.atEnd()) return null;
        float[] pct = new float[2];
        int[] colors = new int[2];
        for (int i = 0; i < 2; i++) {
            ValueReader r = new ValueReader(args.get(i + 1));
            Float p = percentage(r, ctx);
            Integer c = read(r, ctx);
            if (p == null) p = percentage(r, ctx);
            if (c == null || !r.atEnd()) return null;
            colors[i] = c;
            pct[i] = p == null ? Float.NaN : p;
        }
        if (Float.isNaN(pct[0]) && Float.isNaN(pct[1])) pct[0] = pct[1] = 50;
        else if (Float.isNaN(pct[0])) pct[0] = 100 - pct[1];
        else if (Float.isNaN(pct[1])) pct[1] = 100 - pct[0];
        float sum = pct[0] + pct[1];
        if (sum <= 0) return null;
        int mixed = Colors.lerp(colors[0], colors[1], pct[1] / sum);
        return Colors.withAlphaFactor(mixed, Math.min(sum, 100) / 100f);
    }

    private static Float percentage(ValueReader r, ValueContext ctx) {
        int m = r.mark();
        Quantity q = Numeric.of(r.next(), ctx);
        if (q != null && q.isPercentage() && q.percent() >= 0 && q.percent() <= 100) return q.percent();
        r.reset(m);
        return null;
    }
}
