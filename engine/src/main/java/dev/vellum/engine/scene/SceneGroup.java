package dev.vellum.engine.scene;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Display;
import dev.vellum.engine.style.Visibility;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A set of a scene's blocks that shows, fades and moves as one, and that hosts draw as one cached mesh: a
 * {@code <block-group>}, or the scene's base (the blocks no group claims), which has no element and never moves.
 * {@link #version()} changes exactly when its blocks do, so a host rebuilds its mesh only then; how it shows
 * ({@link #visible()}, {@link #opacity()}, {@link #matrix()}) follows the group's style every frame.
 */
public final class SceneGroup {
    /** The blocks a group's x, y and z ranges claim: inclusive and ordered, an axis left out spanning the int range. */
    record Box(int x1, int y1, int z1, int x2, int y2, int z2) {
        boolean claims(int x, int y, int z) {
            return x >= x1 && x <= x2 && y >= y1 && y <= y2 && z >= z1 && z <= z2;
        }
    }

    private final Element element;
    /** The box it claims, or null. */
    Box box;
    private List<SceneBlock> blocks = List.of();
    private Map<Long, SceneBlock> byPosition = Map.of();
    private int version;
    private boolean visible = true;
    private float opacity = 1;
    private float[] matrix = Mat.IDENTITY, inverse = Mat.IDENTITY;
    /** Changes whenever it shows or hides or its matrix changes: with its version, what picking sees of it. */
    int placement;
    /** Its blocks' bounds, {minX, minY, minZ, maxX, maxY, maxZ} (max exclusive), or null when it has none. */
    int[] bounds;
    /** The last transform built, and what from: translate x, y and z, yaw, pitch and roll, and the bounds. */
    private float[] built = Mat.IDENTITY;
    private final float[] builtFrom = new float[6];
    private int[] builtAbout;

    SceneGroup(Element element) {
        this.element = element;
    }

    /** The {@code <block-group>}, or null for the base. */
    public Element element() { return element; }

    /** Its blocks, in y, z, x order. */
    public List<SceneBlock> blocks() { return blocks; }

    /** The block at a position, or null. */
    public SceneBlock at(int x, int y, int z) { return byPosition.get(SceneModel.key(x, y, z)); }

    /** Changes whenever its blocks do. */
    public int version() { return version; }

    /** Not {@code display: none} or hidden, and not fully transparent. */
    public boolean visible() { return visible; }

    public float opacity() { return opacity; }

    /** From its blocks' coordinates to the scene's (3×4, row-major): its translate and rotation. */
    public float[] matrix() { return matrix.clone(); }

    /** Whether it is moved or turned. */
    public boolean moved() { return matrix != Mat.IDENTITY; }

    /** Takes these blocks (sorted, positions unique); the version changes when they differ from the last ones. */
    void setBlocks(List<SceneBlock> sorted) {
        if (sorted.equals(blocks)) return;
        blocks = List.copyOf(sorted);
        version++;
        Map<Long, SceneBlock> positions = new HashMap<>();
        int[] b = null;
        for (SceneBlock s : blocks) {
            positions.put(SceneModel.key(s.x(), s.y(), s.z()), s);
            if (b == null) b = new int[] {s.x(), s.y(), s.z(), s.x() + 1, s.y() + 1, s.z() + 1};
            b[0] = Math.min(b[0], s.x());
            b[1] = Math.min(b[1], s.y());
            b[2] = Math.min(b[2], s.z());
            b[3] = Math.max(b[3], s.x() + 1);
            b[4] = Math.max(b[4], s.y() + 1);
            b[5] = Math.max(b[5], s.z() + 1);
        }
        byPosition = positions;
        bounds = b;
    }

    /** Reads the group's used style; returns whether how it shows changed. */
    boolean readStyle() {
        if (element == null) return false;
        ComputedStyle s = element.style;
        boolean shows = s != null && s.display != Display.NONE && s.visibility == Visibility.VISIBLE && s.opacity > 0;
        float alpha = s == null ? 1 : Math.max(0, Math.min(1, s.opacity));
        float[] m = s == null ? Mat.IDENTITY : transform(s);
        boolean moved = m != matrix && !Arrays.equals(m, matrix);
        boolean changed = shows != visible || alpha != opacity || moved;
        if (shows != visible || moved) placement++;
        visible = shows;
        opacity = alpha;
        if (moved) {
            matrix = m;
            inverse = Mat.invert(m);
        }
        return changed;
    }

    /**
     * {@code translate(centre + t) · yaw · pitch · roll · translate(-centre)}, or the identity; built again only when
     * those or its bounds change. Each turn is clockwise seen from the positive end of its axis, so a positive
     * {@code -mc-yaw} turns the group as it turns a model and as Minecraft's yaw turns an entity (south to west), and
     * at the default view (from the north-east, above) a positive {@code -mc-pitch} (about x) or {@code -mc-roll}
     * (about z) tips its top toward the viewer.
     */
    private float[] transform(ComputedStyle s) {
        float[] f = builtFrom;
        if (builtAbout == bounds && f[0] == s.translateX && f[1] == s.translateY && f[2] == s.translateZ
                && f[3] == s.modelYaw && f[4] == s.modelPitch && f[5] == s.modelRoll) {
            return built;
        }
        f[0] = s.translateX;
        f[1] = s.translateY;
        f[2] = s.translateZ;
        f[3] = s.modelYaw;
        f[4] = s.modelPitch;
        f[5] = s.modelRoll;
        builtAbout = bounds;
        if (f[0] == 0 && f[1] == 0 && f[2] == 0 && f[3] == 0 && f[4] == 0 && f[5] == 0) return built = Mat.IDENTITY;
        float[] c = centre();
        float[] m = Mat.translation(-c[0], -c[1], -c[2]);
        if (f[5] != 0) m = Mat.multiply(Mat.rotation(2, -f[5]), m);
        if (f[4] != 0) m = Mat.multiply(Mat.rotation(0, -f[4]), m);
        if (f[3] != 0) m = Mat.multiply(Mat.rotation(1, -f[3]), m);
        return built = Mat.multiply(Mat.translation(c[0] + f[0], c[1] + f[1], c[2] + f[2]), m);
    }

    /** What it turns about: the middle of its blocks, or the origin when it has none (and so draws nothing). */
    float[] centre() {
        int[] b = bounds;
        return b == null ? new float[3] : new float[] {(b[0] + b[3]) / 2f, (b[1] + b[4]) / 2f, (b[2] + b[5]) / 2f};
    }

    boolean claims(int x, int y, int z) {
        return box != null && box.claims(x, y, z);
    }

    /**
     * Where a ray in scene coordinates first meets one of its blocks: the ray's parameter, or NaN for none.
     * {@code hit[0]} gets the block. Walks the grid cell by cell (Amanatides and Woo) in the group's own coordinates;
     * blocks count as whole cubes.
     */
    float cast(float[] origin, float[] direction, SceneBlock[] hit) {
        if (bounds == null) return Float.NaN;
        float[] o = Mat.apply(inverse, origin[0], origin[1], origin[2], new float[3]);
        float[] d = Mat.applyDirection(inverse, direction[0], direction[1], direction[2], new float[3]);
        double enter = 0, exit = Double.POSITIVE_INFINITY;
        for (int a = 0; a < 3; a++) {
            if (d[a] == 0) {
                if (o[a] < bounds[a] || o[a] >= bounds[a + 3]) return Float.NaN;
                continue;
            }
            double t1 = (bounds[a] - o[a]) / d[a], t2 = (bounds[a + 3] - o[a]) / d[a];
            enter = Math.max(enter, Math.min(t1, t2));
            exit = Math.min(exit, Math.max(t1, t2));
        }
        if (enter > exit) return Float.NaN;
        double t = enter;
        int[] cell = new int[3], step = new int[3];
        double[] next = new double[3], delta = new double[3];
        for (int a = 0; a < 3; a++) {
            double p = o[a] + d[a] * (t + 1e-6);
            cell[a] = (int) Math.floor(Math.max(bounds[a], Math.min(bounds[a + 3] - 1, p)));
            step[a] = d[a] > 0 ? 1 : -1;
            delta[a] = d[a] == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / d[a]);
            double edge = d[a] > 0 ? cell[a] + 1 : cell[a];
            next[a] = d[a] == 0 ? Double.POSITIVE_INFINITY : (edge - o[a]) / d[a];
        }
        while (t <= exit + 1e-6) {
            SceneBlock b = byPosition.get(SceneModel.key(cell[0], cell[1], cell[2]));
            if (b != null) {
                hit[0] = b;
                return (float) t;
            }
            int a = next[0] < next[1] ? (next[0] < next[2] ? 0 : 2) : (next[1] < next[2] ? 1 : 2);
            t = next[a];
            next[a] += delta[a];
            cell[a] += step[a];
            if (cell[a] < bounds[a] || cell[a] >= bounds[a + 3]) return Float.NaN;
        }
        return Float.NaN;
    }
}
