package dev.vellum.preview.render;

import dev.vellum.engine.paint.SmoothShape;

import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

/**
 * Draws {@link SmoothShape}s straight into an ARGB pixel array the way Minecraft's shape shader does: every device
 * pixel under the shape's quad samples {@link SmoothShape#coverage} at its centre, and the colour is blended
 * source-over. The previewer then shows the same smooth edges and blurs as the game.
 */
final class ShapeRasterizer {
    private final int[] pixels;
    private final int stride;
    private final Rectangle2D.Float quad = new Rectangle2D.Float();
    private final Point2D.Double point = new Point2D.Double();

    ShapeRasterizer(int[] pixels, int stride) {
        this.pixels = pixels;
        this.stride = stride;
    }

    /**
     * Fills {@code shape} (in GUI px, placed by {@code guiToDevice}) in {@code argb}, which already carries the
     * canvas's opacity, inside {@code clip}. {@code devicePixel} is a device pixel's size in GUI px.
     */
    void fill(SmoothShape shape, AffineTransform guiToDevice, Rectangle clip, float devicePixel, int argb) {
        AffineTransform deviceToGui;
        try {
            deviceToGui = guiToDevice.createInverse();
        } catch (NoninvertibleTransformException e) {
            return;
        }
        float edge = devicePixel;
        quad.setRect(shape.x0() - edge, shape.y0() - edge, shape.x1() - shape.x0() + 2 * edge, shape.y1() - shape.y0() + 2 * edge);
        Rectangle area = guiToDevice.createTransformedShape(quad).getBounds().intersection(clip);
        int alpha = argb >>> 24, rgb = argb & 0xFFFFFF;
        for (int y = area.y; y < area.y + area.height; y++) {
            for (int x = area.x; x < area.x + area.width; x++) {
                point.setLocation(x + 0.5, y + 0.5);
                deviceToGui.transform(point, point);
                float coverage = shape.coverage((float) point.x - shape.cx(), (float) point.y - shape.cy(), devicePixel);
                int a = Math.round(alpha * coverage);
                if (a == 0) continue;
                int i = y * stride + x;
                pixels[i] = TriangleRasterizer.blend(pixels[i], a << 24 | rgb);
            }
        }
    }
}
