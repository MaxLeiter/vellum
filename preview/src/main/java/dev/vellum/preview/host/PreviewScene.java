package dev.vellum.preview.host;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.paint.Canvas;
import dev.vellum.engine.scene.Mat;
import dev.vellum.engine.scene.SceneBlock;
import dev.vellum.engine.scene.SceneContent;
import dev.vellum.engine.scene.SceneEntity;
import dev.vellum.engine.scene.SceneGroup;
import dev.vellum.engine.scene.SceneModel;
import dev.vellum.preview.render.MinecraftAssets;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The previewer's {@code <scene>}: the same {@link SceneModel} and camera as in game, drawn with Java2D. Each block's
 * model faces ({@link BlockModels}) are projected (the view is orthographic, so a face is a parallelogram and its
 * texture an affine map onto it), shaded by the side they face as the world shades blocks, hidden when a neighbour in
 * their group covers them or when they face away, and painted far to near. A group's uncovered faces are found once
 * for each version of its blocks; a frame only projects them. Groups show, fade and move as in game. Entities are
 * silhouettes; block entities (chests, beds, signs) are not drawn.
 */
final class PreviewScene extends SceneContent implements NamedContent {
    /** The world's face shading: down, up, north, south, west, east. */
    private static final float[] SHADE = {0.5f, 1, 0.8f, 0.8f, 0.6f, 0.6f};

    private final BlockModels models;
    private final Map<SceneGroup, Quads> quads = new IdentityHashMap<>();
    /** The groups {@link #quads} was last trimmed to. */
    private List<SceneGroup> trimmed = List.of();

    /** Something to paint, at its depth (larger is further). */
    private record Item(float depth, Runnable paint) {}

    /** A face no neighbour covers: its corners in the group's coordinates, its texture and its shaded tint. */
    private record Quad(float[] corners, String texture, float[] uv, int tint) {}

    /** A group's quads, for the version of its blocks they were found for. */
    private record Quads(int version, List<Quad> quads) {}

    PreviewScene(Element element, MinecraftAssets assets) {
        super(element);
        this.models = new BlockModels(assets);
    }

    /** The hovered block, as in game. */
    @Override
    public String nameId() {
        SceneBlock block = scene().tooltipBlock();
        return block == null ? null : block.id();
    }

    @Override
    public boolean namesBlock() {
        return true;
    }

    @Override
    public void paint(Canvas canvas, float x, float y, float width, float height) {
        float[] view = scene().cameraMatrix();
        if (view == null) return;
        List<SceneGroup> groups = scene().groups();
        if (groups != trimmed) {
            quads.keySet().retainAll(new HashSet<>(groups));
            trimmed = groups;
        }
        List<Item> items = new ArrayList<>();
        for (SceneGroup group : groups) {
            if (group.visible()) addGroup(quads(group), Mat.multiply(view, group.matrix()), group.opacity(), canvas, x, y, items);
        }
        float u = scene().camera().pixelsPerBlock() * 1.8f / 32;
        for (SceneEntity entity : scene().entities()) addEntity(entity, view, u, canvas, x, y, items);
        items.sort(Comparator.comparingDouble(Item::depth).reversed());
        canvas.save();
        canvas.clipRect(x, y, width, height);
        for (Item item : items) item.paint().run();
        canvas.restore();
    }

    /** The group's faces that no neighbour covers, found again only when its blocks changed. */
    private List<Quad> quads(SceneGroup group) {
        Quads cached = quads.get(group);
        if (cached != null && cached.version() == group.version()) return cached.quads();
        List<Quad> out = new ArrayList<>();
        for (SceneBlock block : group.blocks()) {
            for (BlockModels.Face face : models.of(block.state()).faces()) {
                if (face.cullface() >= 0 && covered(group, block, face.cullface())) continue;
                float[] c = face.corners(), corners = new float[12];
                for (int i = 0; i < 12; i += 3) {
                    corners[i] = block.x() + c[i];
                    corners[i + 1] = block.y() + c[i + 1];
                    corners[i + 2] = block.z() + c[i + 2];
                }
                out.add(new Quad(corners, face.texture(), face.uv(), shade(face.tint(), SHADE[face.direction()])));
            }
        }
        quads.put(group, new Quads(group.version(), out));
        return out;
    }

    /** Projects the quads facing the viewer through {@code m}. */
    private static void addGroup(List<Quad> quads, float[] m, float alpha, Canvas canvas, float ox, float oy, List<Item> out) {
        float[] q = new float[3];
        for (Quad quad : quads) {
            float[] c = quad.corners(), p = new float[12];
            float depth = 0;
            for (int i = 0; i < 12; i += 3) {
                System.arraycopy(Mat.apply(m, c[i], c[i + 1], c[i + 2], q), 0, p, i, 3);
                depth += p[i + 2] / 4;
            }
            // Facing the viewer, top-left to top-right runs right of top-left to bottom-left (y is down).
            float ax = p[3] - p[0], ay = p[4] - p[1], bx = p[9] - p[0], by = p[10] - p[1];
            if (ax * by - ay * bx <= 0) continue;
            out.add(new Item(depth, () -> {
                canvas.save();
                if (alpha < 1) canvas.multiplyAlpha(alpha);
                // A hair larger than the face, so neighbouring faces leave no seam.
                float grow = 0.5f / Math.max(1, Math.min((float) Math.hypot(ax, ay), (float) Math.hypot(bx, by)));
                canvas.transform(ax * (1 + 2 * grow), ay * (1 + 2 * grow), bx * (1 + 2 * grow), by * (1 + 2 * grow),
                        ox + p[0] - (ax + bx) * grow, oy + p[1] - (ay + by) * grow);
                float[] uv = quad.uv();
                canvas.drawImage(quad.texture(), 0, 0, 1, 1, uv[0], uv[1], uv[2], uv[3], quad.tint(), false);
                canvas.restore();
            }));
        }
    }

    /** Whether the neighbour on {@code direction} in the same group hides a face, as solid blocks do. */
    private boolean covered(SceneGroup group, SceneBlock block, int direction) {
        float[] n = BlockModels.NORMALS[direction];
        SceneBlock neighbour = group.at(block.x() + (int) n[0], block.y() + (int) n[1], block.z() + (int) n[2]);
        return neighbour != null && models.of(neighbour.state()).opaque();
    }

    /**
     * A player-shaped silhouette standing at the entity's point, {@code u} px a model unit, as the previewer's
     * {@code <entity>} is drawn.
     */
    private static void addEntity(SceneEntity entity, float[] m, float u, Canvas canvas, float ox, float oy, List<Item> out) {
        float[] p = Mat.apply(m, entity.x(), entity.y(), entity.z(), new float[3]);
        float depth = Mat.apply(m, entity.x(), entity.y() + 0.9f, entity.z(), new float[3])[2];
        out.add(new Item(depth, () -> ReplacedElements.figure(canvas, ox + p[0], oy + p[1], u, 0xE0)));
    }

    /** {@code tint} (white for none) darkened by {@code shade}, keeping its alpha. */
    private static int shade(int tint, float shade) {
        int a = tint >>> 24, r = Math.round((tint >> 16 & 0xFF) * shade), g = Math.round((tint >> 8 & 0xFF) * shade),
                b = Math.round((tint & 0xFF) * shade);
        return a << 24 | r << 16 | g << 8 | b;
    }
}
