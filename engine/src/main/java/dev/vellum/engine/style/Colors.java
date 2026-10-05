package dev.vellum.engine.style;

/** Helpers for ARGB colours, the colour representation used everywhere in the engine (as in Minecraft). */
public final class Colors {
    public static final int TRANSPARENT = 0;
    public static final int BLACK = 0xFF000000;
    public static final int WHITE = 0xFFFFFFFF;

    private Colors() {}

    public static int alpha(int argb) { return argb >>> 24; }
    public static int red(int argb) { return (argb >> 16) & 0xFF; }
    public static int green(int argb) { return (argb >> 8) & 0xFF; }
    public static int blue(int argb) { return argb & 0xFF; }

    public static int argb(int a, int r, int g, int b) {
        return (clamp(a) << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    /** Multiplies the alpha channel by {@code factor} (0..1). */
    public static int withAlphaFactor(int argb, float factor) {
        if (factor >= 1f) return argb;
        int a = Math.round(alpha(argb) * Math.max(0f, factor));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    public static boolean isTransparent(int argb) {
        return alpha(argb) == 0;
    }

    /** Interpolates in premultiplied space, as browsers do, so fading to transparent does not darken. */
    public static int lerp(int a, int b, float t) {
        if (t <= 0) return a;
        if (t >= 1) return b;
        float aa = alpha(a) / 255f, ba = alpha(b) / 255f;
        float oa = aa + (ba - aa) * t;
        if (oa <= 0) return 0;
        float r = (red(a) * aa + (red(b) * ba - red(a) * aa) * t) / oa;
        float g = (green(a) * aa + (green(b) * ba - green(a) * aa) * t) / oa;
        float bl = (blue(a) * aa + (blue(b) * ba - blue(a) * aa) * t) / oa;
        return argb(Math.round(oa * 255), Math.round(r), Math.round(g), Math.round(bl));
    }

    /** Scales the RGB channels, for bevel borders (inset/outset) and the like. */
    public static int shade(int argb, float factor) {
        return argb(alpha(argb), Math.round(red(argb) * factor), Math.round(green(argb) * factor),
                Math.round(blue(argb) * factor));
    }

    private static int clamp(int c) {
        return c < 0 ? 0 : Math.min(255, c);
    }
}
