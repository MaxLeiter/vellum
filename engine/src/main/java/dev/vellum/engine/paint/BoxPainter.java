package dev.vellum.engine.paint;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.FontMetrics;
import dev.vellum.engine.host.FontSpec;
import dev.vellum.engine.host.ReplacedContent;
import dev.vellum.engine.input.Controls;
import dev.vellum.engine.layout.Box;
import dev.vellum.engine.layout.Fragment;
import dev.vellum.engine.style.BorderStyle;
import dev.vellum.engine.style.Colors;
import dev.vellum.engine.style.ComputedStyle;
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
    private final FontMetrics fonts;
    private final QuadBatch batch = new QuadBatch();
    private final Geometry geometry = new Geometry();
    private final Backgrounds backgrounds;
    private final Borders borders = new Borders();
    private final Shadows shadows = new Shadows();

    private Canvas canvas;
    /** GUI px per device pixel in the current transform, saved per transformed context. */
    private float dp;
    private float[] dpStack = new float[8];
    private int depth;

    private final float[] rect = new float[4], radii = new float[8], widths = new float[4];
    private final BorderStyle[] styles = new BorderStyle[4];
    private final int[] colors = new int[4];
    /** The font of the last text run, rebuilt only when the style changes. */
    private ComputedStyle fontStyle;
    private FontSpec font;

    BoxPainter(Painter painter, FontMetrics fonts) {
        this.painter = painter;
        this.fonts = fonts;
        this.backgrounds = new Backgrounds(painter.document());
    }

    void begin(Canvas canvas) {
        this.canvas = canvas;
        dp = devicePixel(canvas);
        depth = 0;
    }

    void end() {
        canvas = null;
        fontStyle = null;
        font = null;
    }

    private static float devicePixel(Canvas canvas) {
        float dp = canvas.devicePixel();
        return dp > 0 && Float.isFinite(dp) ? dp : 1;
    }

    private float snap(float v) {
        return Geometry.snap(v, dp);
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
            canvas.translate(x, y);
            canvas.transform(transform.a, transform.b, transform.c, transform.d, transform.e, transform.f);
            dp = devicePixel(canvas);
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
        float x0 = snap(x), y0 = snap(y);
        canvas.clipRect(x0, y0, snap(x + width) - x0, snap(y + height) - y0);
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
            canvas.translate(x, y);
            Controls.paint(canvas, box);
            canvas.restore();
        }
        if (box.kind == Box.Kind.REPLACED && element.replaced != null) replaced(box, s, element.replaced, x, y);
    }

    @Override
    public void inlineBox(Box block, Fragment.InlineBox fragment, float x, float y) {
        ComputedStyle s = StackingOrder.styleOf(fragment);
        if (s.visibility != Visibility.VISIBLE) return;
        decorations(s, geometry.fragment(fragment, s, x, y, dp));
    }

    /** Outer shadows, background, inset shadows and border (CSS paints the first shadow on top). */
    private void decorations(ComputedStyle s, Geometry g) {
        List<Shadow> boxShadows = s.boxShadow;
        for (int i = boxShadows.size() - 1; i >= 0; i--) if (!boxShadows.get(i).inset()) shadows.outer(batch, boxShadows.get(i), g, dp);
        batch.flush(canvas);
        backgrounds.paint(canvas, batch, g, s, dp);
        for (int i = boxShadows.size() - 1; i >= 0; i--) if (boxShadows.get(i).inset()) shadows.inset(batch, boxShadows.get(i), g, dp);
        batch.flush(canvas);
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

    /** Replaced content in the content box, sized by object-fit and centred; clipped when it overflows. */
    private void replaced(Box box, ComputedStyle s, ReplacedContent content, float x, float y) {
        float cx = x + box.contentX(), cy = y + box.contentY(), cw = box.contentWidth(), ch = box.contentHeight();
        float iw = content.intrinsicWidth(), ih = content.intrinsicHeight(), w = cw, h = ch;
        if (iw > 0 && ih > 0) {
            float scale = switch (s.objectFit) {
                case FILL -> Float.NaN;
                case CONTAIN -> Math.min(cw / iw, ch / ih);
                case COVER -> Math.max(cw / iw, ch / ih);
                case NONE -> 1;
                case SCALE_DOWN -> Math.min(1, Math.min(cw / iw, ch / ih));
            };
            if (!Float.isNaN(scale)) {
                w = iw * scale;
                h = ih * scale;
            }
        }
        boolean overflows = w > cw + 0.01f || h > ch + 0.01f;
        float x0 = snap(cx + (cw - w) / 2), y0 = snap(cy + (ch - h) / 2);
        float width = snap(cx + (cw + w) / 2) - x0, height = snap(cy + (ch + h) / 2) - y0;
        if (width <= 0 || height <= 0 || overflows && !pushClip(cx, cy, cw, ch)) return;
        content.paint(canvas, x0, y0, width, height);
        if (overflows) popClip();
    }

    // ---- Text ----

    @Override
    public void textRun(Box block, Fragment.TextRun run, float x, float y) {
        ComputedStyle s = StackingOrder.styleOf(run);
        if (s.visibility != Visibility.VISIBLE || run.text().isEmpty()) return;
        if (s != fontStyle) {
            fontStyle = s;
            font = FontSpec.of(s);
        }
        int decorations = (s.underline ? Canvas.UNDERLINE : 0) | (s.lineThrough ? Canvas.STRIKETHROUGH : 0);
        float tx = x + run.x(), ty = y + run.y();
        // CSS paints the first shadow on top; the native shadow is drawn by the host with the text itself.
        boolean nativeShadow = false;
        for (int i = s.textShadow.size() - 1; i >= 0; i--) {
            Shadow shadow = s.textShadow.get(i);
            if (shadow.isNative()) nativeShadow = true;
            else if (!Colors.isTransparent(shadow.color())) {
                text(run.text(), tx + shadow.offsetX(), ty + shadow.offsetY(), shadow.color(), decorations, false, s.letterSpacing);
            }
        }
        if (!Colors.isTransparent(s.color)) text(run.text(), tx, ty, s.color, decorations, nativeShadow, s.letterSpacing);
    }

    /** Draws one line; with letter-spacing, glyph by glyph advancing by the host's widths plus the spacing. */
    private void text(String text, float x, float y, int argb, int decorations, boolean shadow, float letterSpacing) {
        if (letterSpacing == 0) {
            canvas.drawText(text, snap(x), snap(y), font, argb, decorations, shadow);
            return;
        }
        float cx = x, ty = snap(y);
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int next = i + Character.charCount(cp);
            canvas.drawText(text.substring(i, next), snap(cx), ty, font, argb, decorations, shadow);
            cx += fonts.charWidth(cp, font) + letterSpacing;
            i = next;
        }
    }

    // ---- After the content ----

    @Override
    public void after(Box box, ComputedStyle s, float x, float y) {
        if (s.visibility != Visibility.VISIBLE) return;
        if (box.isScrollContainer()) {
            scrollbar(box, s, x, y, true);
            scrollbar(box, s, x, y, false);
        }
        if (s.outlineStyle.isVisible() && s.outlineWidth > 0 && !Colors.isTransparent(s.outlineColor)) outline(box, s, x, y);
    }

    private void scrollbar(Box box, ComputedStyle s, float x, float y, boolean vertical) {
        boolean hovered = painter.scrollbarHovered(box, vertical);
        if (!Scrollbars.track(box, vertical, hovered, rect)) return;
        fill(x + rect[0], y + rect[1], rect[2], rect[3], s.scrollbarTrackColor);
        Scrollbars.thumb(box, vertical, hovered, rect);
        fill(x + rect[0], y + rect[1], rect[2], rect[3], s.scrollbarThumbColor);
    }

    /** The outline around the border box, outline-offset away, following the border radius. */
    private void outline(Box box, ComputedStyle s, float x, float y) {
        float out = s.outlineOffset + s.outlineWidth;
        float x0 = snap(x - out), y0 = snap(y - out);
        rect[0] = x0;
        rect[1] = y0;
        rect[2] = snap(x + box.width + out) - x0;
        rect[3] = snap(y + box.height + out) - y0;
        if (rect[2] <= 0 || rect[3] <= 0) return;
        Shapes.radii(s, box.width, box.height, radii);
        Shapes.insetRadii(radii, -out, -out, -out, -out, radii);
        Arrays.fill(widths, Math.max(dp, snap(s.outlineWidth)));
        Arrays.fill(styles, s.outlineStyle);
        Arrays.fill(colors, s.outlineColor);
        borders.paint(canvas, batch, dp, rect, radii, widths, styles, colors);
    }

    private void fill(float x, float y, float w, float h, int argb) {
        if (Colors.isTransparent(argb)) return;
        float x0 = snap(x), y0 = snap(y);
        canvas.fillRect(x0, y0, snap(x + w) - x0, snap(y + h) - y0, argb);
    }
}
