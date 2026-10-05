package dev.vellum.preview;

import dev.vellum.engine.paint.Canvas;

/** A dim, world-like backdrop, as behind an in-game menu: sky, grass and dirt, darkened. */
final class Backdrop {
    private static final int SKY_TOP = 0xFF6B8FD6, SKY_HORIZON = 0xFFB4CDF5, GROUND = 0xFF5D4128;
    /** Vanilla darkens the world behind in-game screens about this much. */
    private static final int DIM = 0xA0101010;

    private Backdrop() {}

    static void paint(Canvas canvas, float width, float height, boolean minecraft) {
        float horizon = Math.round(height * 0.6f), ground = height - horizon;
        canvas.fillQuads(new float[] {0, 0, width, 0, width, horizon, 0, horizon},
                new int[] {SKY_TOP, SKY_TOP, SKY_HORIZON, SKY_HORIZON}, 1);
        if (minecraft) {
            // 16px blocks; UVs past 1 repeat the textures across the width.
            float columns = width / 16;
            canvas.drawImage("minecraft:textures/block/grass_block_side.png", 0, horizon, width, 16, 0, 0, columns, 1, -1, false);
            if (ground > 16) {
                canvas.drawImage("minecraft:textures/block/dirt.png", 0, horizon + 16, width, ground - 16,
                        0, 0, columns, (ground - 16) / 16, -1, false);
            }
        } else {
            canvas.fillRect(0, horizon, width, ground, GROUND);
        }
        canvas.fillRect(0, 0, width, height, DIM);
    }
}
