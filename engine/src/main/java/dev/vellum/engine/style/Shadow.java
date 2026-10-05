package dev.vellum.engine.style;

/**
 * A box-shadow or text-shadow layer. Lengths are resolved px. {@code color} is ARGB.
 * For text shadows {@code spread} and {@code inset} are unused.
 */
public record Shadow(float offsetX, float offsetY, float blur, float spread, int color, boolean inset) {
    /**
     * {@code text-shadow: minecraft}: the host's native text shadow (Minecraft's 1px shadow at 25% brightness).
     * Marked by NaN offsets; test with {@link #isNative()}.
     */
    public static final Shadow MINECRAFT = new Shadow(Float.NaN, Float.NaN, 0, 0, 0, false);

    public boolean isNative() {
        return Float.isNaN(offsetX);
    }

    public static Shadow lerp(Shadow a, Shadow b, float t) {
        if (a.isNative() || b.isNative()) return t < 0.5f ? a : b;
        return new Shadow(
                a.offsetX + (b.offsetX - a.offsetX) * t,
                a.offsetY + (b.offsetY - a.offsetY) * t,
                a.blur + (b.blur - a.blur) * t,
                a.spread + (b.spread - a.spread) * t,
                Colors.lerp(a.color, b.color, t),
                t < 0.5f ? a.inset : b.inset);
    }
}
