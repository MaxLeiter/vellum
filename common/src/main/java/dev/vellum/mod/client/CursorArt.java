package dev.vellum.mod.client;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/**
 * The tour cursor's shapes ({@link TourCursor}), drawn with Java2D at the exact device-pixel size they are shown at,
 * so they stay crisp at any GUI scale: a white shape with a black outline over a soft drop shadow, in the style of
 * the macOS cursors. Shapes are laid out in points (a 1x macOS cursor's units) with the hot spot at the origin.
 * Pure AWT on a headless {@link BufferedImage}, which Minecraft's client allows.
 */
final class CursorArt {
    /** The cursor shapes the tour shows for the CSS {@code cursor} a page asks for. */
    enum Kind { ARROW, HAND, IBEAM, GRAB, GRABBING }

    /** Pixels in ARGB, row by row, and where the hot spot is in them. */
    record Image(int width, int height, int[] argb, double hotX, double hotY) {}

    /** The black outline's width outside the white shape, in points. */
    private static final double OUTLINE = 1.0;
    /** The drop shadow: how far down it falls and how far it spreads, in points, and how dark it is. */
    private static final double SHADOW_DROP = 1.1, SHADOW_BLUR = 1.4, SHADOW_ALPHA = 0.42;
    /** The inner detail lines (a hand's fingers), in points. */
    private static final double DETAIL = 0.75;

    private CursorArt() {}

    /** Draws {@code kind} at {@code pixelsPerPoint} device px per point. */
    static Image draw(Kind kind, double pixelsPerPoint) {
        Area body = body(kind);
        Shape[] details = details(kind);
        double s = pixelsPerPoint;
        double margin = OUTLINE + SHADOW_DROP + SHADOW_BLUR * 2.5;
        Rectangle2D bounds = body.getBounds2D();
        int width = (int) Math.ceil((bounds.getWidth() + margin * 2) * s);
        int height = (int) Math.ceil((bounds.getHeight() + margin * 2) * s);
        // Points to pixels: the body's box starts a margin in from the image's corner.
        AffineTransform toPixels = AffineTransform.getScaleInstance(s, s);
        toPixels.translate(margin - bounds.getX(), margin - bounds.getY());

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Area silhouette = new Area(body);
        silhouette.add(new Area(stroke(OUTLINE * 2).createStrokedShape(body)));
        int[] shadow = shadow(silhouette, toPixels, width, height, s);
        image.setRGB(0, 0, width, height, shadow, 0, width);

        Graphics2D g = graphics(image);
        g.transform(toPixels);
        g.setColor(Color.BLACK);
        g.fill(silhouette);
        g.setColor(Color.WHITE);
        g.fill(body);
        g.setColor(Color.BLACK);
        g.setStroke(stroke(DETAIL));
        for (Shape detail : details) g.draw(detail);
        g.dispose();

        int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
        double[] hot = {0, 0};
        toPixels.transform(hot, 0, hot, 0, 1);
        return new Image(width, height, argb, hot[0], hot[1]);
    }

    // ---- Shapes ----

    /** The white part of the cursor, in points around its hot spot. */
    private static Area body(Kind kind) {
        return switch (kind) {
            case ARROW -> new Area(arrow());
            case HAND -> pointingHand();
            case IBEAM -> new Area(stroke(1.6).createStrokedShape(ibeam()));
            case GRAB -> openHand();
            case GRABBING -> closedHand();
        };
    }

    /** Black lines inside the white: the gaps between a hand's fingers. */
    private static Shape[] details(Kind kind) {
        return switch (kind) {
            case HAND -> new Shape[] {
                    new Line2D.Double(1.5, 8.8, 1.5, 10.8), new Line2D.Double(4.25, 9.4, 4.25, 11.4),
                    new Line2D.Double(6.85, 10.2, 6.85, 12.0)};
            case GRABBING -> new Shape[] {
                    new Line2D.Double(-2.45, 0.4, -2.45, 2.6), new Line2D.Double(0.25, 0.4, 0.25, 2.6),
                    new Line2D.Double(2.95, 0.8, 2.95, 2.8)};
            default -> new Shape[0];
        };
    }

    /** The classic arrow, tip at the hot spot. */
    private static Shape arrow() {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(0, 0);
        p.lineTo(0, 15.4);
        p.lineTo(3.7, 12.0);
        p.lineTo(6.0, 17.3);
        p.lineTo(8.6, 16.2);
        p.lineTo(6.3, 11.0);
        p.lineTo(11.2, 11.0);
        p.closePath();
        return p;
    }

    /** A hand pointing up with its index finger, the fingertip at the hot spot. */
    private static Area pointingHand() {
        Area hand = new Area(new RoundRectangle2D.Double(-1.6, 0, 3.2, 12, 3.2, 3.2)); // index finger
        hand.add(new Area(new RoundRectangle2D.Double(1.4, 7.6, 3.0, 6, 2.6, 2.6))); // middle
        hand.add(new Area(new RoundRectangle2D.Double(4.1, 8.2, 2.9, 6, 2.6, 2.6))); // ring
        hand.add(new Area(new RoundRectangle2D.Double(6.7, 9.0, 2.8, 6, 2.6, 2.6))); // little finger
        hand.add(new Area(new RoundRectangle2D.Double(-1.6, 10.4, 11.1, 7.6, 4.6, 4.6))); // palm
        hand.add(new Area(new RoundRectangle2D.Double(-0.6, 15.0, 9.0, 5.2, 1.6, 1.6))); // wrist
        hand.add(capsule(-4.6, 10.4, -0.4, 15.0, 1.45)); // thumb
        return hand;
    }

    /** An open hand, fingers spread, for {@code cursor: grab}; the hot spot mid-palm. */
    private static Area openHand() {
        Area hand = new Area(new RoundRectangle2D.Double(-5.0, -1.5, 10.6, 8.6, 5, 5)); // palm
        hand.add(new Area(new RoundRectangle2D.Double(-3.6, 4.0, 7.8, 4.6, 2, 2))); // wrist
        hand.add(capsule(-3.6, -1.5, -4.0, -6.4, 1.3)); // index
        hand.add(capsule(-1.0, -1.5, -1.0, -8.0, 1.3)); // middle
        hand.add(capsule(1.6, -1.5, 1.8, -7.2, 1.3)); // ring
        hand.add(capsule(4.0, -0.5, 4.8, -5.0, 1.2)); // little finger
        hand.add(capsule(-4.4, 2.6, -7.8, -1.0, 1.35)); // thumb
        return hand;
    }

    /** A closed hand gripping, knuckles up, for {@code cursor: grabbing}; the hot spot mid-palm. */
    private static Area closedHand() {
        Area hand = new Area(new RoundRectangle2D.Double(-5.2, 1.2, 10.4, 6.8, 5, 5)); // fist
        hand.add(new Area(new RoundRectangle2D.Double(-3.6, 5.0, 7.8, 4.4, 2, 2))); // wrist
        hand.add(new Area(new RoundRectangle2D.Double(-5.2, -0.6, 2.8, 4.4, 2.6, 2.6))); // index knuckle
        hand.add(new Area(new RoundRectangle2D.Double(-2.5, -1.0, 2.8, 4.4, 2.6, 2.6))); // middle
        hand.add(new Area(new RoundRectangle2D.Double(0.2, -0.8, 2.8, 4.4, 2.6, 2.6))); // ring
        hand.add(new Area(new RoundRectangle2D.Double(2.9, -0.1, 2.5, 4.0, 2.4, 2.4))); // little finger
        hand.add(capsule(-5.4, 5.6, -7.0, 2.4, 1.3)); // thumb
        return hand;
    }

    /** The text cursor's centre line: a bar with curved serifs, the hot spot at its middle. */
    private static Shape ibeam() {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(-3.0, -8.6);
        p.quadTo(0, -8.6, 0, -6.8);
        p.quadTo(0, -8.6, 3.0, -8.6);
        p.moveTo(0, -6.8);
        p.lineTo(0, 6.8);
        p.moveTo(-3.0, 8.6);
        p.quadTo(0, 8.6, 0, 6.8);
        p.quadTo(0, 8.6, 3.0, 8.6);
        return p;
    }

    /** A rounded bar from (x0, y0) to (x1, y1), {@code radius} wide each side. */
    private static Area capsule(double x0, double y0, double x1, double y1, double radius) {
        return new Area(new BasicStroke((float) (radius * 2), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                .createStrokedShape(new Line2D.Double(x0, y0, x1, y1)));
    }

    private static BasicStroke stroke(double width) {
        return new BasicStroke((float) width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    }

    // ---- Pixels ----

    private static Graphics2D graphics(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    /** The silhouette, dropped and blurred (three box blurs, close to a Gaussian), as translucent black pixels. */
    private static int[] shadow(Area silhouette, AffineTransform toPixels, int width, int height, double s) {
        BufferedImage mask = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(mask);
        g.translate(0, SHADOW_DROP * s);
        g.transform(toPixels);
        g.setColor(Color.BLACK);
        g.fill(silhouette);
        g.dispose();
        int[] argb = mask.getRGB(0, 0, width, height, null, 0, width);
        float[] alpha = new float[argb.length];
        for (int i = 0; i < argb.length; i++) alpha[i] = (argb[i] >>> 24) / 255f;
        int radius = Math.max(1, (int) Math.round(SHADOW_BLUR * s / 1.7));
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(alpha, width, height, radius, true);
            boxBlur(alpha, width, height, radius, false);
        }
        for (int i = 0; i < argb.length; i++) argb[i] = Math.round(Math.min(1, alpha[i]) * (float) SHADOW_ALPHA * 255) << 24;
        return argb;
    }

    /** One box blur pass along rows ({@code horizontal}) or columns, in place. */
    private static void boxBlur(float[] a, int width, int height, int radius, boolean horizontal) {
        int lines = horizontal ? height : width, length = horizontal ? width : height;
        float[] line = new float[length];
        float scale = 1f / (radius * 2 + 1);
        for (int l = 0; l < lines; l++) {
            for (int i = 0; i < length; i++) line[i] = a[index(l, i, width, horizontal)];
            float sum = 0;
            for (int i = -radius; i <= radius; i++) sum += i >= 0 && i < length ? line[i] : 0;
            for (int i = 0; i < length; i++) {
                a[index(l, i, width, horizontal)] = sum * scale;
                int out = i - radius, in = i + radius + 1;
                if (out >= 0) sum -= line[out];
                if (in < length) sum += line[in];
            }
        }
    }

    private static int index(int line, int i, int width, boolean horizontal) {
        return horizontal ? line * width + i : i * width + line;
    }
}
