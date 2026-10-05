package dev.vellum.engine.testing;

import dev.vellum.engine.host.FontSpec;
import dev.vellum.engine.paint.Affine;
import dev.vellum.engine.paint.Canvas;
import dev.vellum.engine.paint.Shapes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A canvas that records every drawing call and clip with the state it ran in: the transform, the alpha and the clip
 * (as a viewport rectangle). Read it structurally ({@link #calls}, {@link #ops}) or as strings ({@link #trace}).
 * Shapes ({@code fillRoundedRect}, {@code fillBorder}) are recorded as such, not tessellated, unless
 * {@link #tessellate} is set.
 */
public final class RecordingCanvas implements Canvas {
    /**
     * One call. {@code op} is the method name ({@code "clipRect"} for clips); {@code bounds} the viewport-space
     * bounding box {x, y, w, h} of what it draws (or clips to); {@code args} its raw arguments (rect, radii,
     * UVs...); {@code quads}/{@code quadColors} for fillQuads, in local coordinates; {@code clip} the clip it ran
     * under, in viewport space, or null.
     */
    public record Call(String op, float[] bounds, float[] args, int color, String text, Affine matrix, float alpha,
                       float[] clip, float[] quads, int[] quadColors, int[] colors, int decorations, boolean shadow) {
        public float x() { return bounds[0]; }
        public float y() { return bounds[1]; }
        public float w() { return bounds[2]; }
        public float h() { return bounds[3]; }

        /**
         * The call as a line of text, in viewport px: {@code rect 1,2 3x4 #ff0000ff}, {@code text 'hi' 3,6
         * #ffffffff shadow}, {@code sprite id 0,0 8x20}, {@code clip 0,0 50x10}...
         */
        @Override
        public String toString() {
            String at = f(x()) + "," + f(y());
            String size = at + " " + f(w()) + "x" + f(h());
            return switch (op) {
                case "fillRect" -> "rect " + size + " " + hex(color);
                case "fillRoundedRect" -> "round " + size + " " + hex(color);
                case "fillBorder" -> "border " + size + " " + hex(color);
                case "fillQuads" -> "quads " + quads.length / 8 + " " + size;
                case "drawText" -> "text '" + text + "' " + at + " " + hex(color) + (shadow ? " shadow" : "");
                case "drawImage" -> "image " + text + " " + size;
                case "drawSprite" -> "sprite " + text + " " + size;
                case "clipRect" -> "clip " + size;
                default -> op;
            };
        }
    }

    /** Every drawing call and clip, in order. */
    public final List<Call> calls = new ArrayList<>();
    /** What {@link #devicePixel()} reports (1 / GUI scale). */
    public float devicePixel = 1;
    /** Tessellate shapes into quads (through the {@link Canvas} defaults) instead of recording them. */
    public boolean tessellate;

    private Affine matrix = new Affine();
    private float alpha = 1;
    private float[] clip;
    private final Deque<Object[]> stack = new ArrayDeque<>();

    // ---- Queries ----

    /** The calls of one kind ({@code "fillRect"}, {@code "drawText"}...), in order. */
    public List<Call> ops(String op) {
        return calls.stream().filter(c -> c.op.equals(op)).toList();
    }

    /** Every call as a line of text ({@link Call#toString}), in order. */
    public List<String> trace() {
        return calls.stream().map(Call::toString).toList();
    }

    /** The lines of {@link #trace} starting with {@code prefix} ({@code "text"}, {@code "rect"}...). */
    public List<String> trace(String prefix) {
        return trace().stream().filter(line -> line.startsWith(prefix)).toList();
    }

    /** The first fillRect in {@code color}. */
    public Call fill(int color) {
        return ops("fillRect").stream().filter(c -> c.color == color).findFirst()
                .orElseThrow(() -> new AssertionError("no fill in " + hex(color) + ": " + trace()));
    }

    /** Colours of fillRect / fillRoundedRect calls, in order. */
    public List<Integer> fills() {
        return calls.stream().filter(c -> c.op.equals("fillRect") || c.op.equals("fillRoundedRect")).map(Call::color).toList();
    }

    /** The texts drawn, in order. */
    public List<String> texts() {
        return ops("drawText").stream().map(Call::text).toList();
    }

    /** Total area of all recorded quads (local coordinates), after checking they are finite and wound like vanilla. */
    public double quadArea() {
        double area = 0;
        for (Call c : ops("fillQuads")) {
            for (int i = 0; i < c.quads.length; i += 8) {
                float[] q = Arrays.copyOfRange(c.quads, i, i + 8);
                for (float v : q) assertTrue(Float.isFinite(v), "non-finite vertex " + Arrays.toString(q));
                double a = Shapes.signedArea(q, 0, 4) / 2.0;
                assertFalse(a > 1e-4, "quad wound the wrong way: " + Arrays.toString(q));
                area -= a;
            }
        }
        return area;
    }

    public int quadCount() {
        return ops("fillQuads").stream().mapToInt(c -> c.quads.length / 8).sum();
    }

    /** The area of the recorded quads by colour (each quad by its first vertex's). */
    public Map<Integer, Double> quadAreaByColor() {
        Map<Integer, Double> areas = new HashMap<>();
        for (Call c : ops("fillQuads")) {
            for (int i = 0; i < c.quads.length / 8; i++) {
                areas.merge(c.quadColors[i * 4], -Shapes.signedArea(c.quads, i * 8, 4) / 2.0, Double::sum);
            }
        }
        return areas;
    }

    /** A vertex of a recorded quad: its local coordinates and colour. */
    @FunctionalInterface
    public interface VertexVisitor {
        void visit(float x, float y, int color);
    }

    public void forEachVertex(VertexVisitor visitor) {
        for (Call c : ops("fillQuads")) {
            for (int v = 0; v < c.quadColors.length; v++) visitor.visit(c.quads[2 * v], c.quads[2 * v + 1], c.quadColors[v]);
        }
    }

    /** True when every save has been restored. */
    public boolean balanced() {
        return stack.isEmpty();
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
        record("clipRect", r, new float[] {x, y, width, height}, 0, null, null, null, null, 0, false);
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

    private static String f(float v) {
        float r = Math.round(v * 100) / 100f;
        return r == Math.rint(r) ? Integer.toString((int) r) : String.format(Locale.ROOT, "%.2f", r);
    }

    private static String hex(int argb) {
        return String.format("#%08x", argb);
    }
}
