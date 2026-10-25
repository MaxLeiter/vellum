package dev.vellum.preview.host;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.paint.Canvas;
import dev.vellum.engine.scene.SceneBlock;
import dev.vellum.engine.scene.SceneCamera;
import dev.vellum.engine.scene.SceneContent;
import dev.vellum.engine.scene.SceneEntity;
import dev.vellum.engine.scene.SceneGroup;
import dev.vellum.engine.scene.SceneModel;
import dev.vellum.preview.render.MinecraftAssets;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The previewer's {@code <scene>}: the same {@link SceneModel} and camera as in game, drawn with Java2D. Each block's
 * model faces ({@link BlockModels}) are projected (the view is orthographic, so a face is a parallelogram and its
 * texture an affine map onto it), shaded by the side they face as the world shades blocks, hidden when a neighbour in
 * their group covers them or when they face away, and painted far to near. Groups show, fade and move as in game.
 * Entities are silhouettes; block entities (chests, beds, signs) are not drawn.
 */
final class PreviewScene implements SceneContent {
    /** The world's face shading: down, up, north, south, west, east. */
    private static final float[] SHADE = {0.5f, 1, 0.8f, 0.8f, 0.6f, 0.6f};

    private final SceneModel model;
    private final BlockModels models;

    /** Something to paint, at its depth (larger is further). */
    private record Item(float depth, Runnable paint) {}

    PreviewScene(Element element, MinecraftAssets assets) {
        this.model = new SceneModel(element);
        this.models = new BlockModels(assets);
    }

    @Override public SceneModel scene() { return model; }
    @Override public float intrinsicWidth() { return SceneModel.WIDTH; }
    @Override public float intrinsicHeight() { return SceneModel.HEIGHT; }

    /** The hovered block's name shows, as in game ({@code TooltipPainter} names it). */
    @Override
    public boolean showsTooltip() {
        return model.tooltipBlock() != null;
    }

    @Override
    public void paint(Canvas canvas, float x, float y, float width, float height) {
        SceneCamera camera = model.camera();
        if (camera == null) return;
        float[] view = camera.matrix();
        List<Item> items = new ArrayList<>();
        for (SceneGroup group : model.groups()) {
            if (group.visible()) addGroup(group, multiply(view, group.matrix()), group.opacity(), canvas, x, y, items);
        }
        for (SceneEntity entity : model.entities()) addEntity(entity, view, camera.pixelsPerBlock(), canvas, x, y, items);
        items.sort(Comparator.comparingDouble(Item::depth).reversed());
        canvas.save();
        canvas.clipRect(x, y, width, height);
        for (Item item : items) item.paint().run();
        canvas.restore();
    }

    private void addGroup(SceneGroup group, float[] m, float alpha, Canvas canvas, float ox, float oy, List<Item> out) {
        for (SceneBlock block : group.blocks()) {
            for (BlockModels.Face face : models.of(block.state()).faces()) {
                if (face.cullface() >= 0 && covered(group, block, face.cullface())) continue;
                float[] p = new float[12];
                float depth = 0;
                for (int i = 0; i < 4; i++) {
                    float cx = block.x() + face.corners()[i * 3], cy = block.y() + face.corners()[i * 3 + 1],
                            cz = block.z() + face.corners()[i * 3 + 2];
                    p[i * 3] = m[0] * cx + m[1] * cy + m[2] * cz + m[3];
                    p[i * 3 + 1] = m[4] * cx + m[5] * cy + m[6] * cz + m[7];
                    p[i * 3 + 2] = m[8] * cx + m[9] * cy + m[10] * cz + m[11];
                    depth += p[i * 3 + 2] / 4;
                }
                // Facing the viewer, top-left to top-right runs right of top-left to bottom-left (y is down).
                float ax = p[3] - p[0], ay = p[4] - p[1], bx = p[9] - p[0], by = p[10] - p[1];
                if (ax * by - ay * bx <= 0) continue;
                int tint = shade(face.tint(), SHADE[face.direction()]);
                out.add(new Item(depth, () -> {
                    canvas.save();
                    if (alpha < 1) canvas.multiplyAlpha(alpha);
                    // A hair larger than the face, so neighbouring faces leave no seam.
                    float grow = 0.5f / Math.max(1, Math.min((float) Math.hypot(ax, ay), (float) Math.hypot(bx, by)));
                    canvas.transform(ax * (1 + 2 * grow), ay * (1 + 2 * grow), bx * (1 + 2 * grow), by * (1 + 2 * grow),
                            ox + p[0] - (ax + bx) * grow, oy + p[1] - (ay + by) * grow);
                    float[] uv = face.uv();
                    canvas.drawImage(face.texture(), 0, 0, 1, 1, uv[0], uv[1], uv[2], uv[3], tint, false);
                    canvas.restore();
                }));
            }
        }
    }

    /** Whether the neighbour on {@code direction} in the same group hides a face, as solid blocks do. */
    private boolean covered(SceneGroup group, SceneBlock block, int direction) {
        float[] n = BlockModels.NORMALS[direction];
        SceneBlock neighbour = group.at(block.x() + (int) n[0], block.y() + (int) n[1], block.z() + (int) n[2]);
        return neighbour != null && models.of(neighbour.state()).opaque();
    }

    /** A player-shaped silhouette standing at the entity's point, as the previewer's {@code <entity>} is drawn. */
    private static void addEntity(SceneEntity entity, float[] m, float pixelsPerBlock, Canvas canvas, float ox, float oy,
                                  List<Item> out) {
        float px = m[0] * entity.x() + m[1] * entity.y() + m[2] * entity.z() + m[3];
        float py = m[4] * entity.x() + m[5] * entity.y() + m[6] * entity.z() + m[7];
        float depth = m[8] * entity.x() + m[9] * (entity.y() + 0.9f) + m[10] * entity.z() + m[11];
        float u = pixelsPerBlock * 1.8f / 32;
        out.add(new Item(depth, () -> {
            float x = ox + px - 8 * u, y = oy + py - 32 * u;
            float[][] parts = {{4, 0, 8, 8}, {4, 8, 8, 12}, {0, 8, 4, 12}, {12, 8, 4, 12}, {4, 20, 4, 12}, {8, 20, 4, 12}};
            int[] colors = {0xE0505050, 0xE0404040, 0xE0363636, 0xE0363636, 0xE0303030, 0xE02A2A2A};
            for (int i = 0; i < parts.length; i++) {
                float[] p = parts[i];
                canvas.fillRect(x + p[0] * u, y + p[1] * u, p[2] * u, p[3] * u, colors[i]);
            }
        }));
    }

    /** {@code tint} (white for none) darkened by {@code shade}, keeping its alpha. */
    private static int shade(int tint, float shade) {
        int a = tint >>> 24, r = Math.round((tint >> 16 & 0xFF) * shade), g = Math.round((tint >> 8 & 0xFF) * shade),
                b = Math.round((tint & 0xFF) * shade);
        return a << 24 | r << 16 | g << 8 | b;
    }

    /** {@code a · b} for 3×4 row-major affine matrices. */
    private static float[] multiply(float[] a, float[] b) {
        float[] out = new float[12];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 4; c++) {
                float v = a[r * 4] * b[c] + a[r * 4 + 1] * b[4 + c] + a[r * 4 + 2] * b[8 + c];
                out[r * 4 + c] = c == 3 ? v + a[r * 4 + 3] : v;
            }
        }
        return out;
    }
}
