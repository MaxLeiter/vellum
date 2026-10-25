package dev.vellum.engine.scene;

/**
 * 3D affine transforms as 3×4 row-major arrays ({@code [m00 m01 m02 m03, m10 ... m13, m20 ... m23]}): a point
 * {@code p} maps to {@code M · (p, 1)}.
 */
final class Mat {
    static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};

    private Mat() {}

    /** {@code a · b}: b applies first. */
    static float[] multiply(float[] a, float[] b) {
        float[] out = new float[12];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 4; c++) {
                float v = a[r * 4] * b[c] + a[r * 4 + 1] * b[4 + c] + a[r * 4 + 2] * b[8 + c];
                out[r * 4 + c] = c == 3 ? v + a[r * 4 + 3] : v;
            }
        }
        return out;
    }

    static float[] translation(float x, float y, float z) {
        return new float[] {1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z};
    }

    /** A turn of {@code degrees} about axis 0 (x), 1 (y) or 2 (z), right-handed. */
    static float[] rotation(int axis, float degrees) {
        double r = Math.toRadians(degrees);
        float c = (float) Math.cos(r), s = (float) Math.sin(r);
        return switch (axis) {
            case 0 -> new float[] {1, 0, 0, 0, 0, c, -s, 0, 0, s, c, 0};
            case 1 -> new float[] {c, 0, s, 0, 0, 1, 0, 0, -s, 0, c, 0};
            default -> new float[] {c, -s, 0, 0, s, c, 0, 0, 0, 0, 1, 0};
        };
    }

    /** {@code m · p} into {@code out} (x, y, z). */
    static float[] apply(float[] m, float x, float y, float z, float[] out) {
        out[0] = m[0] * x + m[1] * y + m[2] * z + m[3];
        out[1] = m[4] * x + m[5] * y + m[6] * z + m[7];
        out[2] = m[8] * x + m[9] * y + m[10] * z + m[11];
        return out;
    }

    /** {@code m}'s linear part times the direction {@code d}. */
    static float[] applyDirection(float[] m, float x, float y, float z, float[] out) {
        out[0] = m[0] * x + m[1] * y + m[2] * z;
        out[1] = m[4] * x + m[5] * y + m[6] * z;
        out[2] = m[8] * x + m[9] * y + m[10] * z;
        return out;
    }

    /** The inverse, or null when {@code m} collapses space (a zero scale). */
    static float[] invert(float[] m) {
        double a = m[0], b = m[1], c = m[2], d = m[4], e = m[5], f = m[6], g = m[8], h = m[9], i = m[10];
        double ca = e * i - f * h, cb = -(d * i - f * g), cc = d * h - e * g;
        double det = a * ca + b * cb + c * cc;
        if (Math.abs(det) < 1e-12) return null;
        double k = 1 / det;
        float[] out = {
                (float) (ca * k), (float) (-(b * i - c * h) * k), (float) ((b * f - c * e) * k), 0,
                (float) (cb * k), (float) ((a * i - c * g) * k), (float) (-(a * f - c * d) * k), 0,
                (float) (cc * k), (float) (-(a * h - b * g) * k), (float) ((a * e - b * d) * k), 0};
        for (int r = 0; r < 3; r++) {
            out[r * 4 + 3] = -(out[r * 4] * m[3] + out[r * 4 + 1] * m[7] + out[r * 4 + 2] * m[11]);
        }
        return out;
    }
}
