package dev.vellum.engine.paint;

import dev.vellum.engine.style.BackgroundLayer;
import dev.vellum.engine.style.Colors;
import dev.vellum.engine.style.Shadow;

import java.util.Arrays;

/**
 * {@code box-shadow}. The blur is approximated with concentric rounded-rect rings whose alpha ramps along a
 * smoothstep over ±blur around the shadow's edge (CSS: a Gaussian with σ = blur / 2), each ring shading linearly
 * between its edges. Distances below are offsets {@code e} from the shadow shape's edge, positive outwards.
 *
 * <p>Outer shadows go under the box; when the shadow is not offset, the part under the border box is left out
 * exactly, so translucent boxes do not darken. (An offset shadow is drawn whole: only visible through translucent
 * backgrounds.) Inset shadows fill the padding box outside the offset, shrunk shape, clipped to the padding box.
 */
final class Shadows {
    private final float[] shape = new float[8], ringRadii = new float[8], widths = new float[4];
    private final float[] padding = new float[4], paddingRadii = new float[8], path = new float[2 * 72];
    private final int[] outerColors = new int[4], innerColors = new int[4];
    /** The shadow shape being drawn: border-box rect offset and spread, radii in {@link #shape}. */
    private float sx, sy, sw, sh;
    private int color;
    private boolean inward;
    private float blur;

    void outer(QuadBatch batch, Shadow shadow, Geometry g, float dp) {
        if (Colors.isTransparent(shadow.color())) return;
        float spread = shadow.spread();
        if (!begin(shadow, g.x + shadow.offsetX() - spread, g.y + shadow.offsetY() - spread,
                g.width + 2 * spread, g.height + 2 * spread, g.radii, -spread)) {
            return;
        }
        boolean concentric = shadow.offsetX() == 0 && shadow.offsetY() == 0;
        // The border box's edge as an offset from the shadow's: nothing inside it needs drawing.
        float hole = concentric ? -spread : Float.NEGATIVE_INFINITY;
        float lo = -Math.min(blur, Math.min(sw, sh) / 2);
        if (hole >= blur) return;
        if (hole < lo) {
            if (concentric) {
                band(batch, hole, lo, dp);
            } else {
                offsetRadii(lo, ringRadii);
                batch.roundedRect(sx - lo, sy - lo, sw + 2 * lo, sh + 2 * lo, ringRadii, dp, alphaAt(lo));
            }
        }
        if (blur > 0) ramp(batch, Math.max(lo, hole), blur, dp);
    }

    void inset(QuadBatch batch, Shadow shadow, Geometry g, float dp) {
        if (Colors.isTransparent(shadow.color())) return;
        g.area(BackgroundLayer.Box.PADDING_BOX, padding, paddingRadii);
        if (padding[2] <= 0 || padding[3] <= 0) return;
        batch.setClip(padding[0], padding[1], padding[2], padding[3], paddingRadii, dp);
        float spread = shadow.spread();
        if (!begin(shadow, padding[0] + shadow.offsetX() + spread, padding[1] + shadow.offsetY() + spread,
                padding[2] - 2 * spread, padding[3] - 2 * spread, paddingRadii, spread)) {
            batch.rect(padding[0], padding[1], padding[2], padding[3], shadow.color()); // shrunk to nothing: all shadow
        } else {
            if (blur > 0) ramp(batch, -Math.min(blur, Math.min(sw, sh) / 2), blur, dp);
            outside(batch, blur, dp);
        }
        batch.clearClip();
    }

    /** Sets up the shadow shape; false when it is empty. {@code spreadInset} shrinks the box radii (negative grows). */
    private boolean begin(Shadow shadow, float x, float y, float w, float h, float[] radii, float spreadInset) {
        if (w <= 0 || h <= 0) return false;
        sx = x;
        sy = y;
        sw = w;
        sh = h;
        color = shadow.color();
        inward = shadow.inset();
        blur = Math.max(0, shadow.blur());
        Shapes.insetRadii(radii, spreadInset, spreadInset, spreadInset, spreadInset, shape);
        return true;
    }

    /** Rings from e0 to e1 with alpha following the blur profile. */
    private void ramp(QuadBatch batch, float e0, float e1, float dp) {
        int rings = Math.clamp(Math.round((e1 - e0) / dp / 4), 2, 6);
        for (int i = 0; i < rings; i++) {
            band(batch, e0 + (e1 - e0) * i / rings, e0 + (e1 - e0) * (i + 1) / rings, dp);
        }
    }

    /** The ring of the shadow between offsets e0 < e1, coloured by the profile at each edge. */
    private void band(QuadBatch batch, float e0, float e1, float dp) {
        offsetRadii(e1, ringRadii);
        Arrays.fill(widths, e1 - e0);
        Arrays.fill(outerColors, alphaAt(e1));
        Arrays.fill(innerColors, alphaAt(e0));
        batch.ring(sx - e1, sy - e1, sw + 2 * e1, sh + 2 * e1, ringRadii, widths, outerColors, innerColors, Shapes.ALL_SIDES, dp);
    }

    /**
     * For inset shadows, the fully shadowed region: the clip region (padding box) outside the shape grown by the
     * blur. Its bounding box leaves four strips; the grown shape's rounded corners leave the bits between each arc
     * and its bounding corner, fanned from that corner.
     */
    private void outside(QuadBatch batch, float e, float dp) {
        float x0 = sx - e, y0 = sy - e, x1 = sx + sw + e, y1 = sy + sh + e;
        float px0 = padding[0], py0 = padding[1], px1 = padding[0] + padding[2], py1 = padding[1] + padding[3];
        if (y0 > py0) batch.rect(px0, py0, px1 - px0, y0 - py0, color);
        if (py1 > y1) batch.rect(px0, y1, px1 - px0, py1 - y1, color);
        if (x0 > px0) batch.rect(px0, y0, x0 - px0, y1 - y0, color);
        if (px1 > x1) batch.rect(x1, y0, px1 - x1, y1 - y0, color);
        offsetRadii(e, ringRadii);
        Shapes.roundedPath(x0, y0, x1 - x0, y1 - y0, ringRadii, dp, path, 0);
        int v = 0;
        for (int k = 0; k < 4; k++) {
            int ri = Shapes.CORNER_RADIUS[k];
            int n = Shapes.segments(Math.max(ringRadii[ri], ringRadii[ri + 1]), dp);
            float cx = Shapes.isRight(k) ? x1 : x0, cy = Shapes.isBottom(k) ? y1 : y0;
            for (int i = 0; i < n; i++) {
                int a = 2 * (v + i), b = a + 2;
                batch.quad(cx, cy, path[a], path[a + 1], path[b], path[b + 1], path[b], path[b + 1], color);
            }
            v += n + 1;
        }
    }

    /** Shape radii at offset e: the blur rounds square corners as it grows (the offset curve of the shape). */
    private void offsetRadii(float e, float[] out) {
        for (int i = 0; i < 8; i++) out[i] = Math.max(0, shape[i] + e);
    }

    /** The shadow colour at offset e: full inside the ramp's inner edge, clear past its outer edge (reversed for inset). */
    private int alphaAt(float e) {
        float t = blur > 0 ? Math.clamp((e + blur) / (2 * blur), 0, 1) : (e <= 0 ? 0 : 1);
        float coverage = 1 - t * t * (3 - 2 * t);
        return Colors.withAlphaFactor(color, inward ? 1 - coverage : coverage);
    }
}
