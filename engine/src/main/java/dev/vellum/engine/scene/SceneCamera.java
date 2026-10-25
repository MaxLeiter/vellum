package dev.vellum.engine.scene;

/**
 * How a {@code <scene>} is seen: an orthographic view, as the inventory sees blocks, of the scene turned
 * {@code yaw} degrees about the vertical axis through its centre and seen from {@code tilt} degrees above, a block
 * being {@code pixelsPerBlock} px, with the centre in the middle of the painted box {@code (x, y, width, height)}
 * (relative to the scene's border box).
 *
 * <p>{@link #matrix()} is the one mapping hosts draw with: scene coordinates (x east, y up, z south, a block per unit)
 * to painted-box px, x right, y down, and z away from the viewer (so nearer is smaller). At {@code -mc-yaw} and
 * {@code -mc-pitch} 0 the tilt is 30° and the turn 225°, which is how the inventory shows a block.
 */
public record SceneCamera(float yaw, float tilt, float pixelsPerBlock, float centerX, float centerY, float centerZ,
                          float x, float y, float width, float height) {
    /** The inventory's view of blocks: 30° from above, turned 225°. */
    static final float BASE_TILT = 30, BASE_YAW = 225;
    /** Room left around the scene's bounds, as a fraction of the box. */
    static final float MARGIN = 0.06f;

    /**
     * The camera for a scene with {@code bounds} ({minX, minY, minZ, maxX, maxY, maxZ}) in a painted box: turned by
     * {@code yaw} and tilted by {@code pitch} more than the inventory view (the total tilt held within ±90°), and
     * sized so the bounds fit at any turn, times {@code scale}. {@code center} is the point in the middle; NaN parts
     * are the bounds' middle.
     */
    static SceneCamera fit(float[] bounds, float[] center, float yaw, float pitch, float scale, float x, float y,
                           float width, float height) {
        float tilt = Math.max(-90, Math.min(90, BASE_TILT + pitch));
        float sx = bounds[3] - bounds[0], sy = bounds[4] - bounds[1], sz = bounds[5] - bounds[2];
        double a = Math.toRadians(tilt);
        float sweep = (float) Math.sqrt(sx * sx + sz * sz);
        float roomHeight = (float) (sy * Math.abs(Math.cos(a)) + sweep * Math.abs(Math.sin(a)));
        float fit = Math.min(width * (1 - 2 * MARGIN) / Math.max(sweep, 1e-3f),
                height * (1 - 2 * MARGIN) / Math.max(roomHeight, 1e-3f));
        float cx = Float.isNaN(center[0]) ? (bounds[0] + bounds[3]) / 2 : center[0];
        float cy = Float.isNaN(center[1]) ? (bounds[1] + bounds[4]) / 2 : center[1];
        float cz = Float.isNaN(center[2]) ? (bounds[2] + bounds[5]) / 2 : center[2];
        return new SceneCamera(yaw, tilt, Math.max(0, fit * scale), cx, cy, cz, x, y, width, height);
    }

    /** Scene coordinates to painted-box px (3×4, row-major), as described above. */
    public float[] matrix() {
        double b = Math.toRadians(BASE_YAW - yaw), a = Math.toRadians(tilt);
        float cb = (float) Math.cos(b), sb = (float) Math.sin(b), ca = (float) Math.cos(a), sa = (float) Math.sin(a);
        float s = pixelsPerBlock;
        // The view turns about y, then tilts about x (y up, z toward the viewer); then y and z flip to point down and
        // away, and it scales and moves the centre to the middle of the box.
        float[] m = {
                s * cb, 0, s * sb, 0,
                -s * sa * sb, -s * ca, s * sa * cb, 0,
                s * ca * sb, -s * sa, -s * ca * cb, 0};
        m[3] = width / 2 - (m[0] * centerX + m[1] * centerY + m[2] * centerZ);
        m[7] = height / 2 - (m[4] * centerX + m[5] * centerY + m[6] * centerZ);
        m[11] = -(m[8] * centerX + m[9] * centerY + m[10] * centerZ);
        return m;
    }

    /** Where the scene point lands in the painted box: {x, y, depth} in px, depth growing away from the viewer. */
    public float[] project(float px, float py, float pz) {
        return Mat.apply(matrix(), px, py, pz, new float[3]);
    }
}
