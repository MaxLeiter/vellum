package dev.vellum.engine.scene;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Display;
import dev.vellum.engine.style.Visibility;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * A set of a scene's blocks that shows, fades and moves as one, and that hosts draw as one cached mesh: a
 * {@code <block-group>}, or the scene's base (the blocks no group claims), which has no element and never moves.
 * {@link #version()} changes exactly when its blocks do, so a host rebuilds its mesh only then; how it shows
 * ({@link #visible()}, {@link #opacity()}, {@link #matrix()}) follows the group's style every frame.
 */
public final class SceneGroup {
    private final Element element;
    /** The box it claims ({x1, y1, z1, x2, y2, z2}, inclusive, ordered), or null. */
    int[] select;
    private List<SceneBlock> blocks = List.of();
    private Map<Long, SceneBlock> byPosition = Map.of();
    private int version;
    private boolean visible = true;
    private float opacity = 1;
    private float[] matrix = Mat.IDENTITY, inverse = Mat.IDENTITY;
    /** Its blocks' bounds, {minX, minY, minZ, maxX, maxY, maxZ} (max exclusive), or null when it has none. */
    int[] bounds;

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

    float[] inverse() { return inverse; }

    Map<Long, SceneBlock> byPosition() { return byPosition; }

    /** Takes these blocks (sorted, positions unique); the version changes when they differ from the last ones. */
    void setBlocks(List<SceneBlock> sorted, Map<Long, SceneBlock> positions) {
        if (sorted.equals(blocks)) return;
        blocks = List.copyOf(sorted);
        byPosition = positions;
        version++;
        int[] b = null;
        for (SceneBlock s : blocks) {
            if (b == null) b = new int[] {s.x(), s.y(), s.z(), s.x() + 1, s.y() + 1, s.z() + 1};
            b[0] = Math.min(b[0], s.x());
            b[1] = Math.min(b[1], s.y());
            b[2] = Math.min(b[2], s.z());
            b[3] = Math.max(b[3], s.x() + 1);
            b[4] = Math.max(b[4], s.y() + 1);
            b[5] = Math.max(b[5], s.z() + 1);
        }
        bounds = b;
    }

    /** Reads the group's used style; returns whether how it shows changed. */
    boolean readStyle() {
        if (element == null) return false;
        ComputedStyle s = element.style;
        boolean shows = s != null && s.display != Display.NONE && s.visibility == Visibility.VISIBLE && s.opacity > 0;
        float alpha = s == null ? 1 : Math.max(0, Math.min(1, s.opacity));
        float[] m = s == null ? Mat.IDENTITY : transform(s);
        boolean changed = shows != visible || alpha != opacity || !Arrays.equals(m, matrix);
        visible = shows;
        opacity = alpha;
        if (!Arrays.equals(m, matrix)) {
            matrix = m;
            float[] inv = Mat.invert(m);
            inverse = inv;
        }
        return changed;
    }

    /** {@code translate(centre + t) · rotate z · rotate y · rotate x · translate(-centre)}, or the identity. */
    private float[] transform(ComputedStyle s) {
        if (s.translateX == 0 && s.translateY == 0 && s.translateZ == 0 && s.rotateX == 0 && s.rotateY == 0
                && s.rotateZ == 0) {
            return Mat.IDENTITY;
        }
        float[] c = centre();
        float[] m = Mat.translation(-c[0], -c[1], -c[2]);
        if (s.rotateX != 0) m = Mat.multiply(Mat.rotation(0, s.rotateX), m);
        if (s.rotateY != 0) m = Mat.multiply(Mat.rotation(1, s.rotateY), m);
        if (s.rotateZ != 0) m = Mat.multiply(Mat.rotation(2, s.rotateZ), m);
        return Mat.multiply(Mat.translation(c[0] + s.translateX, c[1] + s.translateY, c[2] + s.translateZ), m);
    }

    /** What it turns about: the middle of its blocks, else of the box it claims, else the origin. */
    float[] centre() {
        int[] b = bounds;
        if (b != null) return new float[] {(b[0] + b[3]) / 2f, (b[1] + b[4]) / 2f, (b[2] + b[5]) / 2f};
        if (select != null) {
            return new float[] {(select[0] + select[3] + 1) / 2f, (select[1] + select[4] + 1) / 2f,
                    (select[2] + select[5] + 1) / 2f};
        }
        return new float[3];
    }

    boolean claims(int x, int y, int z) {
        int[] s = select;
        return s != null && x >= s[0] && x <= s[3] && y >= s[1] && y <= s[4] && z >= s[2] && z <= s[5];
    }

    /**
     * Where a ray in scene coordinates first meets one of its blocks: the ray's parameter, or NaN for none.
     * {@code hit[0]} gets the block. Walks the grid cell by cell (Amanatides and Woo) in the group's own coordinates;
     * blocks count as whole cubes.
     */
    float cast(float[] origin, float[] direction, SceneBlock[] hit) {
        if (bounds == null || inverse == null) return Float.NaN;
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
