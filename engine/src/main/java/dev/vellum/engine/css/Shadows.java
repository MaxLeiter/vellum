package dev.vellum.engine.css;

import dev.vellum.engine.css.Token.Type;
import dev.vellum.engine.style.Shadow;

import java.util.ArrayList;
import java.util.List;

/** {@code box-shadow} and {@code text-shadow} lists; {@code text-shadow: minecraft} is the host's native shadow. */
final class Shadows {
    private Shadows() {}

    static List<Shadow> read(ValueReader r, ValueContext ctx, boolean box) {
        if (r.ident("none")) return List.of();
        List<Shadow> out = new ArrayList<>(1);
        do {
            Shadow s = !box && r.ident("minecraft") ? Shadow.MINECRAFT : shadow(r, ctx, box);
            if (s == null) return null;
            out.add(s);
        } while (r.comma());
        return List.copyOf(out);
    }

    /** {@code [inset] <offset-x> <offset-y> [<blur> [<spread>]] [<color>]}, parts in any order. */
    private static Shadow shadow(ValueReader r, ValueContext ctx, boolean box) {
        boolean inset = false;
        Integer color = null;
        float[] lengths = new float[4];
        int count = 0;
        while (!r.atEnd() && !(r.peek() instanceof Token t && t.is(Type.COMMA))) {
            if (box && !inset && r.ident("inset")) {
                inset = true;
                continue;
            }
            if (color == null) {
                color = CssColors.read(r, ctx);
                if (color != null) continue;
            }
            Float px = Numeric.px(r, ctx, true);
            if (px == null || count == (box ? 4 : 3) || (count == 2 && px < 0)) return null;
            lengths[count++] = px;
        }
        if (count < 2) return null;
        return new Shadow(lengths[0], lengths[1], lengths[2], lengths[3], color == null ? ctx.currentColor() : color,
                inset);
    }

    static String serialize(List<Shadow> shadows, boolean box) {
        if (shadows.isEmpty()) return "none";
        return CssText.join(shadows, ", ", s -> {
            if (s.isNative()) return "minecraft";
            String text = CssColors.serialize(s.color()) + " " + CssText.px(s.offsetX()) + " " + CssText.px(s.offsetY())
                    + " " + CssText.px(s.blur());
            return box ? text + " " + CssText.px(s.spread()) + (s.inset() ? " inset" : "") : text;
        });
    }
}
