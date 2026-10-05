package dev.vellum.engine.paint;

import dev.vellum.engine.host.FontSpec;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A canvas that records every call with the state it ran in: the transform, the alpha and the clip (as a viewport
 * rectangle). Shapes ({@code fillRoundedRect}, {@code fillBorder}) are recorded as such, not tessellated, unless
 * {@link #tessellate} is set.
 */
final class RecordingCanvas implements Canvas {
    /**
     * One call. {@code bounds} is the viewport-space bounding box {x, y, w, h} of what it draws; {@code args} its
     * raw arguments (rect, radii, UVs...); {@code quads}/{@code quadColors} for fillQuads, in local coordinates.
     */
    record Call(String op, float[] bounds, float[] args, int color, String text, Affine matrix, float alpha,
                float[] clip, float[] quads, int[] quadColors, int[] colors, int decorations, boolean shadow) {
        float x() { return bounds[0]; }
        float y() { return bounds[1]; }
        float w() { return bounds[2]; }
        float h() { return bounds[3]; }
    }

    final List<Call> calls = new ArrayList<>();
    float devicePixel = 1;
    boolean tessellate;

    private Affine matrix = new Affine();
    private float alpha = 1;
    private float[] clip;
    private final Deque<Object[]> stack = new ArrayDeque<>();

    // ---- Queries ----

    List<Call> ops(String op) {
        return calls.stream().filter(c -> c.op.equals(op)).toList();
    }

    /** Ops that draw something, in order, as "op:#color" or "op:text" strings. */
    List<String> trace() {
        return calls.stream().map(c -> c.text != null ? c.op + ":" + c.text : c.op + ":" + Integer.toHexString(c.color)).toList();
    }

    /** Colours of fillRect / fillRoundedRect calls, in order. */
    List<Integer> fills() {
        return calls.stream().filter(c -> c.op.equals("fillRect") || c.op.equals("fillRoundedRect")).map(Call::color).toList();
    }

    Stream<float[]> quadsOf(Call c) {
        return java.util.stream.IntStream.range(0, c.quads.length / 8).mapToObj(i -> Arrays.copyOfRange(c.quads, i * 8, i * 8 + 8));
    }

    /** Total area of all recorded quads (local coordinates), after checking they are finite and wound like vanilla. */
    double quadArea() {
        double area = 0;
        for (Call c : ops("fillQuads")) {
            for (int i = 0; i < c.quads.length; i += 8) {
                float[] q = Arrays.copyOfRange(c.quads, i, i + 8);
                for (float v : q) assertTrue(Float.isFinite(v), "non-finite vertex " + Arrays.toString(q));
                double a = signedArea(q);
                assertFalse(a > 1e-4, "quad wound the wrong way: " + Arrays.toString(q));
                area -= a;
            }
        }
        return area;
    }

    int quadCount() {
        return ops("fillQuads").stream().mapToInt(c -> c.quads.length / 8).sum();
    }

    /** The signed area of a quad (not twice it). */
    static double signedArea(float[] q) {
        return Shapes.signedArea(q, 0, 4) / 2.0;
    }

    // ---- State ----

    @Override
    public void save() {
        stack.push(new Object[] {new Affine().set(matrix), alpha, clip});
    }

    @Override
    public int saveCount() {
        return stack.size();
    }

    @Override
    public void restore() {
        Object[] s = stack.pop();
        matrix = (Affine) s[0];
        alpha = (Float) s[1];
        clip = (float[]) s[2];
    }

    @Override
    public void translate(float dx, float dy) {
        matrix.translate(dx, dy);
    }

    @Override
    public void transform(float a, float b, float c, float d, float e, float f) {
        matrix.multiply(a, b, c, d, e, f);
    }

    @Override
    public void multiplyAlpha(float a) {
        alpha *= a;
    }

    @Override
    public void clipRect(float x, float y, float width, float height) {
        float[] r = bounds(x, y, width, height);
        if (clip != null) {
            float x0 = Math.max(r[0], clip[0]), y0 = Math.max(r[1], clip[1]);
            float x1 = Math.min(r[0] + r[2], clip[0] + clip[2]), y1 = Math.min(r[1] + r[3], clip[1] + clip[3]);
            r = new float[] {x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0)};
        }
        clip = r;
    }

    @Override
    public float devicePixel() {
        return devicePixel;
    }

    /** True when every save has been restored. */
    boolean balanced() {
        return stack.isEmpty();
    }

    // ---- Drawing ----

    private void record(String op, float[] bounds, float[] args, int color, String text, float[] quads, int[] quadColors,
                        int[] colors, int decorations, boolean shadow) {
        calls.add(new Call(op, bounds, args, color, text, new Affine().set(matrix), alpha, clip, quads, quadColors, colors,
                decorations, shadow));
    }

    private float[] bounds(float x, float y, float w, float h) {
        float[] xs = {matrix.mapX(x, y), matrix.mapX(x + w, y), matrix.mapX(x, y + h), matrix.mapX(x + w, y + h)};
        float[] ys = {matrix.mapY(x, y), matrix.mapY(x + w, y), matrix.mapY(x, y + h), matrix.mapY(x + w, y + h)};
        float x0 = Math.min(Math.min(xs[0], xs[1]), Math.min(xs[2], xs[3]));
        float x1 = Math.max(Math.max(xs[0], xs[1]), Math.max(xs[2], xs[3]));
        float y0 = Math.min(Math.min(ys[0], ys[1]), Math.min(ys[2], ys[3]));
        float y1 = Math.max(Math.max(ys[0], ys[1]), Math.max(ys[2], ys[3]));
        return new float[] {x0, y0, x1 - x0, y1 - y0};
    }

    @Override
    public void fillRect(float x, float y, float width, float height, int argb) {
        record("fillRect", bounds(x, y, width, height), new float[] {x, y, width, height}, argb, null, null, null, null, 0, false);
    }

    @Override
    public void fillQuads(float[] xy, int[] colors, int quadCount) {
        float[] q = Arrays.copyOf(xy, quadCount * 8);
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        for (int i = 0; i < q.length; i += 2) {
            x0 = Math.min(x0, q[i]);
            x1 = Math.max(x1, q[i]);
            y0 = Math.min(y0, q[i + 1]);
            y1 = Math.max(y1, q[i + 1]);
        }
        record("fillQuads", bounds(x0, y0, x1 - x0, y1 - y0), null, colors[0], null, q, Arrays.copyOf(colors, quadCount * 4),
                null, 0, false);
    }

    @Override
    public void drawText(String text, float x, float y, FontSpec font, int argb, int decorations, boolean shadow) {
        record("drawText", bounds(x, y, 0, 0), new float[] {x, y}, argb, text, null, null, null, decorations, shadow);
    }

    @Override
    public void drawImage(String url, float x, float y, float width, float height, float u0, float v0, float u1, float v1,
                          int tint, boolean smooth) {
        record("drawImage", bounds(x, y, width, height), new float[] {x, y, width, height, u0, v0, u1, v1}, tint, url,
                null, null, null, 0, smooth);
    }

    @Override
    public void drawSprite(String spriteId, float x, float y, float width, float height, int tint) {
        record("drawSprite", bounds(x, y, width, height), new float[] {x, y, width, height}, tint, spriteId, null, null,
                null, 0, false);
    }

    @Override
    public void fillRoundedRect(float x, float y, float width, float height, float[] radii, int argb) {
        if (tessellate) {
            Canvas.super.fillRoundedRect(x, y, width, height, radii, argb);
            return;
        }
        float[] args = new float[12];
        args[0] = x;
        args[1] = y;
        args[2] = width;
        args[3] = height;
        System.arraycopy(radii, 0, args, 4, 8);
        record("fillRoundedRect", bounds(x, y, width, height), args, argb, null, null, null, null, 0, false);
    }

    @Override
    public void fillBorder(float[] outer, float[] radii, float[] widths, int[] colors) {
        if (tessellate) {
            Canvas.super.fillBorder(outer, radii, widths, colors);
            return;
        }
        float[] args = new float[16];
        System.arraycopy(outer, 0, args, 0, 4);
        System.arraycopy(radii, 0, args, 4, 8);
        System.arraycopy(widths, 0, args, 12, 4);
        record("fillBorder", bounds(outer[0], outer[1], outer[2], outer[3]), args, colors[0], null, null, null,
                colors.clone(), 0, false);
    }
}
