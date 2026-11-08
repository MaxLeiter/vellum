package dev.vellum.engine.paint;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.ReplacedContent;
import dev.vellum.engine.input.Controls;
import dev.vellum.engine.layout.Box;
import dev.vellum.engine.layout.Fragment;
import dev.vellum.engine.style.BackgroundLayer;
import dev.vellum.engine.style.BorderStyle;
import dev.vellum.engine.style.Colors;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.ShapeRendering;
import dev.vellum.engine.style.Shadow;
import dev.vellum.engine.style.Visibility;

import java.util.Arrays;
import java.util.List;

/**
 * The painting side of {@link StackingOrder}: draws each step of the walk onto a {@link Canvas}. Per box, in order:
 * outer box-shadows, background, inset box-shadows, border, form control ({@link Controls#paint}), replaced content
 * ({@link ReplacedContent#paint}); after its content: scrollbars and outline. Rectangle edges are snapped to device
 * pixels.
 */
final class BoxPainter implements StackingOrder.Visitor {
    private final Painter painter;
    private final QuadBatch batch = new QuadBatch();
    private final Geometry geometry = new Geometry();
    private final Backgrounds backgrounds;
    private final Borders borders = new Borders();

    private Canvas canvas;
    /** GUI px per device pixel in the current transform, saved per transformed context. */
    private float dp;
    private float[] dpStack = new float[8];
    private int depth;

    private final float[] rect = new float[4], replacedRect = new float[4], radii = new float[8], widths = new float[4];
    /** The border or padding box a box-shadow is drawn from, for {@link Canvas#fillBoxShadow}. */
    private final float[] shadowBox = new float[4], shadowRadii = new float[8];
    private final BorderStyle[] styles = new BorderStyle[4];
    private final int[] colors = new int[4];

    BoxPainter(Painter painter) {
        this.painter = painter;
        this.backgrounds = new Backgrounds(painter.document());
    }

    void begin(Canvas canvas) {
        this.canvas = canvas;
        dp = PixelGrid.devicePixel(canvas);
        depth = 0;
    }

    void end() {
        canvas = null;
    }

    private float snap(float v) {
        return PixelGrid.snap(v, dp);
    }

    // ---- State ----

    @Override
    public boolean enterContext(Box box, ComputedStyle style, float x, float y, Affine transform) {
        // Overshooting easings can push animated opacity outside 0..1.
        float opacity = Math.min(1, style.opacity);
        if (!(opacity > 0)) return false;
        canvas.save();
        if (depth == dpStack.length) dpStack = Arrays.copyOf(dpStack, depth * 2);
        dpStack[depth++] = dp;
        if (transform != null) {
            // PixelGrid's third rule: put the context's origin on a device pixel, since everything inside snaps
            // relative to it (a rotated context has no grid to keep).
            float e = transform.e, f = transform.f;
            if (transform.b == 0 && transform.c == 0) {
                e = snap(x + e) - x;
                f = snap(y + f) - y;
            }
            canvas.translate(x, y);
            canvas.transform(transform.a, transform.b, transform.c, transform.d, e, f);
            dp = PixelGrid.devicePixel(canvas);
        }
        if (opacity < 1) canvas.multiplyAlpha(opacity);
        return true;
    }

    @Override
    public void exitContext() {
        dp = dpStack[--depth];
        canvas.restore();
    }

    @Override
    public boolean pushClip(float x, float y, float width, float height) {
        if (width <= 0 || height <= 0) return false;
        canvas.save();
        PixelGrid.rect(x, y, width, height, dp, rect);
        canvas.clipRect(rect[0], rect[1], rect[2], rect[3]);
        return true;
    }

    @Override
    public void popClip() {
        canvas.restore();
    }

    // ---- Boxes ----

    @Override
    public void box(Box box, ComputedStyle s, float x, float y) {
        if (s.visibility != Visibility.VISIBLE || box.kind == Box.Kind.ANONYMOUS) return;
        decorations(s, geometry.box(box, s, x, y, dp));
        Element element = box.element;
        if (element == null || box.kind == Box.Kind.PSEUDO) return;
        if (Controls.isControl(element.tagName())) {
            canvas.save();
            // Controls snap what they draw relative to their origin: PixelGrid's third rule.
            canvas.translate(snap(x), snap(y));
            Controls.paint(canvas, box, s);
            canvas.restore();
        }
        if (box.kind == Box.Kind.REPLACED && element.replaced != null) replaced(box, s, element.replaced, x, y);
    }

    @Override
    public void inlineBox(Box block, Fragment.InlineBox fragment, ComputedStyle s, float x, float y) {
        if (s.visibility != Visibility.VISIBLE) return;
        decorations(s, geometry.fragment(fragment, s, x, y, dp));
    }

    /** Outer shadows, background, inset shadows and border (CSS paints the first shadow on top). */
    private void decorations(ComputedStyle s, Geometry g) {
        canvas.crispShapes(s.shapeRendering == ShapeRendering.CRISP_EDGES);
        paintShadows(g, s.boxShadow, false, BackgroundLayer.Box.BORDER_BOX);
        backgrounds.paint(canvas, batch, g, s, dp);
        paintShadows(g, s.boxShadow, true, BackgroundLayer.Box.PADDING_BOX);
        if (!s.hasBorder()) return;
        rect[0] = g.x;
        rect[1] = g.y;
        rect[2] = g.width;
        rect[3] = g.height;
        styles[0] = s.borderTopStyle;
        styles[1] = s.borderRightStyle;
        styles[2] = s.borderBottomStyle;
        styles[3] = s.borderLeftStyle;
        colors[0] = s.borderTopColor;
        colors[1] = s.borderRightColor;
        colors[2] = s.borderBottomColor;
        colors[3] = s.borderLeftColor;
        borders.paint(canvas, batch, dp, rect, g.radii, g.border, styles, colors);
    }

    /** The outer or inset shadows, last first, drawn from {@code area}. */
    private void paintShadows(Geometry g, List<Shadow> shadows, boolean inset, BackgroundLayer.Box area) {
        if (shadows.isEmpty()) return;
        g.area(area, shadowBox, shadowRadii);
        for (int i = shadows.size() - 1; i >= 0; i--) {
            if (shadows.get(i).inset() == inset) canvas.fillBoxShadow(shadowBox, shadowRadii, shadows.get(i));
        }
    }

    /** Replaced content in the content box, sized by object-fit and placed by object-position; clipped when it overflows. */
    private void replaced(Box box, ComputedStyle s, ReplacedContent content, float x, float y) {
        float cx = x + box.contentX(), cy = y + box.contentY(), cw = box.contentWidth(), ch = box.contentHeight();
        float[] fitted = Coordinates.objectBox(box, s, content);
        float w = fitted[2], h = fitted[3];
        boolean overflows = w > cw + 0.01f || h > ch + 0.01f;
        float[] r = PixelGrid.rect(x + fitted[0], y + fitted[1], w, h, dp, replacedRect);
        if (r[2] <= 0 || r[3] <= 0 || overflows && !pushClip(cx, cy, cw, ch)) return;
        content.paint(canvas, r[0], r[1], r[2], r[3]);
        if (overflows) popClip();
    }

    // ---- Text ----

    @Override
    public void textRun(Box block, Fragment.TextRun run, float x, float y) {
        ComputedStyle s = StackingOrder.styleOf(run);
        if (s.visibility != Visibility.VISIBLE || run.text().isEmpty()) return;
        TextPainter.draw(canvas, run.text(), run.spaced(), x + run.x(), y + run.y(), s.font(), s, s.color, dp);
    }

    // ---- After the content ----

    @Override
    public void after(Box box, ComputedStyle s, float x, float y) {
        if (s.visibility != Visibility.VISIBLE) return;
        if (box.isScrollContainer()) {
            scrollbar(box, s, x, y, true);
            scrollbar(box, s, x, y, false);
        }
        if (StackingOrder.hasOutline(s)) outline(s, x, y, box.width, box.height);
    }

    @Override
    public void inlineOutline(Fragment.InlineBox fragment, ComputedStyle s, float x, float y) {
        if (s.visibility == Visibility.VISIBLE) outline(s, x + fragment.x(), y + fragment.y(), fragment.width(), fragment.height());
    }

    private void scrollbar(Box box, ComputedStyle s, float x, float y, boolean vertical) {
        boolean hovered = painter.scrollbarHovered(box, vertical);
        if (!Scrollbars.track(box, vertical, hovered, rect)) return;
        fill(x + rect[0], y + rect[1], rect[2], rect[3], s.scrollbarTrackColor);
        Scrollbars.thumb(box, vertical, hovered, rect);
        fill(x + rect[0], y + rect[1], rect[2], rect[3], s.scrollbarThumbColor);
    }

    /** The outline around a border box at (x, y), outline-offset away, following the border radius. */
    private void outline(ComputedStyle s, float x, float y, float width, float height) {
        canvas.crispShapes(s.shapeRendering == ShapeRendering.CRISP_EDGES);
        float out = s.outlineOffset + s.outlineWidth, w = s.outlineWidth;
        float left = x - out, top = y - out, right = x + width + out, bottom = y + height + out;
        PixelGrid.edges(left, top, right, bottom, dp, rect);
        if (rect[2] <= 0 || rect[3] <= 0) return;
        Shapes.radii(s, width, height, radii);
        Shapes.insetRadii(radii, -out, -out, -out, -out, radii);
        // Each side from its snapped outer edge to its own inner edge, so an outline with no offset meets the border.
        widths[0] = PixelGrid.band(PixelGrid.snap(top, dp), top + w, w, dp);
        widths[1] = PixelGrid.band(PixelGrid.snap(right, dp), right - w, w, dp);
        widths[2] = PixelGrid.band(PixelGrid.snap(bottom, dp), bottom - w, w, dp);
        widths[3] = PixelGrid.band(PixelGrid.snap(left, dp), left + w, w, dp);
        Arrays.fill(styles, s.outlineStyle);
        Arrays.fill(colors, s.outlineColor);
        borders.paint(canvas, batch, dp, rect, radii, widths, styles, colors);
    }

    private void fill(float x, float y, float w, float h, int argb) {
        if (Colors.isTransparent(argb)) return;
        PixelGrid.rect(x, y, w, h, dp, rect);
        canvas.fillRect(rect[0], rect[1], rect[2], rect[3], argb);
    }
}
