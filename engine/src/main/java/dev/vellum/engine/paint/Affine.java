package dev.vellum.engine.paint;

import dev.vellum.engine.style.TransformFunction;

import java.util.List;

/**
 * A mutable 2D affine transform {@code [a c e; b d f; 0 0 1]}, in the CSS {@code matrix(a, b, c, d, e, f)}
 * convention. Painting and hit testing share it, so CSS transforms resolve to the same matrix in both.
 *
 * <p>Operations post-multiply like {@link Canvas#transform}: after {@code m.translate(tx, ty)} the translation is
 * applied to points first, then the rest of {@code m}. That is the order of a CSS transform list read left to right.
 */
public final class Affine {
    /** Indices into the array written by {@link #decompose}. */
    static final int TX = 0, TY = 1, ROTATE = 2, SCALE_X = 3, SCALE_Y = 4, SKEW = 5;

    public float a = 1, b, c, d = 1, e, f;

    /** Scratch for interpolated transform lists, created on first use so steady-state painting does not allocate. */
    private Affine from, to;
    private float[] parts;

    public Affine identity() {
        return set(1, 0, 0, 1, 0, 0);
    }

    public Affine set(float a, float b, float c, float d, float e, float f) {
        this.a = a;
        this.b = b;
        this.c = c;
        this.d = d;
        this.e = e;
        this.f = f;
        return this;
    }

    public Affine set(Affine m) {
        return set(m.a, m.b, m.c, m.d, m.e, m.f);
    }

    /** {@code this = this · m}. */
    public Affine multiply(float ma, float mb, float mc, float md, float me, float mf) {
        return set(a * ma + c * mb, b * ma + d * mb,
                a * mc + c * md, b * mc + d * md,
                a * me + c * mf + e, b * me + d * mf + f);
    }

    public Affine multiply(Affine m) {
        return multiply(m.a, m.b, m.c, m.d, m.e, m.f);
    }

    public Affine translate(float tx, float ty) {
        e += a * tx + c * ty;
        f += b * tx + d * ty;
        return this;
    }

    public Affine scale(float sx, float sy) {
        a *= sx;
        b *= sx;
        c *= sy;
        d *= sy;
        return this;
    }

    /** Rotates clockwise on screen (y points down), as CSS {@code rotate()} does. */
    public Affine rotate(float degrees) {
        double r = Math.toRadians(degrees);
        float cos = (float) Math.cos(r), sin = (float) Math.sin(r);
        return multiply(cos, sin, -sin, cos, 0, 0);
    }

    public Affine skew(float xDegrees, float yDegrees) {
        return multiply(1, (float) Math.tan(Math.toRadians(yDegrees)), (float) Math.tan(Math.toRadians(xDegrees)), 1, 0, 0);
    }

    public float determinant() {
        return a * d - b * c;
    }

    public boolean isIdentity() {
        return a == 1 && b == 0 && c == 0 && d == 1 && e == 0 && f == 0;
    }

    /** Inverts in place; returns false (leaving the matrix unchanged) when it is singular, e.g. {@code scale(0)}. */
    public boolean invert() {
        float det = determinant();
        if (det == 0 || !Float.isFinite(det)) return false;
        float ia = d / det, ib = -b / det, ic = -c / det, id = a / det;
        set(ia, ib, ic, id, -(ia * e + ic * f), -(ib * e + id * f));
        return true;
    }

    public float mapX(float x, float y) {
        return a * x + c * y + e;
    }

    public float mapY(float x, float y) {
        return b * x + d * y + f;
    }

    // ---- CSS transform lists ----

    /**
     * Post-multiplies the transform functions in order. Percentages in translations resolve against
     * {@code refWidth}/{@code refHeight} (the border box).
     */
    public Affine concat(List<TransformFunction> functions, float refWidth, float refHeight) {
        for (TransformFunction fn : functions) {
            switch (fn) {
                case TransformFunction.Translate t -> translate(t.x().resolve(refWidth), t.y().resolve(refHeight));
                case TransformFunction.Scale s -> scale(s.x(), s.y());
                case TransformFunction.Rotate r -> rotate(r.degrees());
                case TransformFunction.Skew s -> skew(s.xDegrees(), s.yDegrees());
                case TransformFunction.Matrix m -> multiply(m.a(), m.b(), m.c(), m.d(), m.e(), m.f());
                case TransformFunction.Interpolated i -> {
                    if (from == null) {
                        from = new Affine();
                        to = new Affine();
                    }
                    from.identity().concat(i.from(), refWidth, refHeight);
                    to.identity().concat(i.to(), refWidth, refHeight);
                    // from/to are fully consumed here, so nested Interpolated lists can reuse their own scratch.
                    multiply(from.interpolate(from, to, i.t()));
                }
            }
        }
        return this;
    }

    // ---- Decomposition (CSS Transforms: interpolation of 2D matrices) ----

    /**
     * Decomposes into {@code translate · rotate · skewX · scale} and writes {translateX, translateY, rotation (rad),
     * scaleX, scaleY, skewX (rad)} to {@code out}. A flip (negative determinant) is put on the axis the CSS spec
     * picks, so {@code scale(-1, 1)} decomposes without a rotation.
     */
    public void decompose(float[] out) {
        double det = (double) a * d - (double) b * c;
        double sx = Math.hypot(a, b);
        if (det < 0 && a < d) sx = -sx;
        double rotation, sy, shear;
        if (sx == 0) {
            // The x axis collapsed: no rotation can be read from it; keep what the y axis says.
            rotation = 0;
            sy = d;
            shear = d != 0 ? c / (double) d : 0;
        } else {
            rotation = Math.atan2(b / sx, a / sx);
            sy = det / sx;
            shear = sy != 0 ? (a * (double) c + b * (double) d) / (sx * sy) : 0;
        }
        out[TX] = e;
        out[TY] = f;
        out[ROTATE] = (float) rotation;
        out[SCALE_X] = (float) sx;
        out[SCALE_Y] = (float) sy;
        out[SKEW] = (float) Math.atan(shear);
    }

    /** Sets this matrix from a decomposition written by {@link #decompose}. */
    public Affine recompose(float[] p) {
        return identity().translate(p[TX], p[TY])
                .multiply((float) Math.cos(p[ROTATE]), (float) Math.sin(p[ROTATE]),
                        -(float) Math.sin(p[ROTATE]), (float) Math.cos(p[ROTATE]), 0, 0)
                .multiply(1, 0, (float) Math.tan(p[SKEW]), 1, 0, 0)
                .scale(p[SCALE_X], p[SCALE_Y]);
    }

    /**
     * Sets this to the blend of {@code m0} and {@code m1} at {@code t}: both are decomposed, each component is
     * interpolated (rotation the short way round), and the result recomposed. {@code m0}/{@code m1} may be this.
     */
    public Affine interpolate(Affine m0, Affine m1, float t) {
        if (parts == null) parts = new float[12];
        float[] p = parts;
        m0.decompose(p);
        System.arraycopy(p, 0, p, 6, 6);
        m1.decompose(p);
        // p[0..5] = to, p[6..11] = from
        int fromBase = 6;
        if ((p[fromBase + SCALE_X] < 0 && p[SCALE_Y] < 0) || (p[fromBase + SCALE_Y] < 0 && p[SCALE_X] < 0)) {
            // One flips x and the other y: flip both axes of the start, which is a half turn instead.
            p[fromBase + SCALE_X] = -p[fromBase + SCALE_X];
            p[fromBase + SCALE_Y] = -p[fromBase + SCALE_Y];
            p[fromBase + ROTATE] += p[fromBase + ROTATE] < 0 ? (float) Math.PI : -(float) Math.PI;
        }
        float r0 = p[fromBase + ROTATE], r1 = p[ROTATE];
        if (Math.abs(r0 - r1) > Math.PI) {
            if (r0 > r1) r0 -= (float) (2 * Math.PI);
            else r1 -= (float) (2 * Math.PI);
        }
        p[fromBase + ROTATE] = r0;
        p[ROTATE] = r1;
        for (int i = 0; i < 6; i++) p[i] = p[fromBase + i] + (p[i] - p[fromBase + i]) * t;
        return recompose(p);
    }

    @Override
    public String toString() {
        return "matrix(" + a + ", " + b + ", " + c + ", " + d + ", " + e + ", " + f + ")";
    }
}
