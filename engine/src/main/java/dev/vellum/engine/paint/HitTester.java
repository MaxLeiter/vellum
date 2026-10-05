package dev.vellum.engine.paint;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Text;
import dev.vellum.engine.host.FontMetrics;
import dev.vellum.engine.host.FontSpec;
import dev.vellum.engine.layout.Box;
import dev.vellum.engine.layout.Fragment;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.PointerEvents;
import dev.vellum.engine.style.Visibility;

import java.util.Arrays;

/**
 * The hit-testing side of {@link StackingOrder}: follows the paint walk with the point mapped into the current
 * space (through inverse transforms and scroll offsets) and keeps the last thing painted under it, which is the
 * topmost. Subtrees whose clip excludes the point are skipped. Boxes with {@code pointer-events: none} or hidden
 * visibility are not hit themselves, but their descendants still can be.
 */
final class HitTester implements StackingOrder.Visitor {
    private final Painter painter;
    private final FontMetrics fonts;
    private final Affine inverse = new Affine();
    private final float[] radii = new float[8], bar = new float[4];
    /** The point in the current space, and the points saved by enclosing contexts. */
    private float px, py;
    private float[] saved = new float[16];
    private int depth;

    private Box hitBox;
    private Element hitElement;
    private float hitX, hitY;
    private Text hitText;
    private int hitOffset;

    HitTester(Painter painter, FontMetrics fonts) {
        this.painter = painter;
        this.fonts = fonts;
    }

    void begin(float x, float y) {
        px = x;
        py = y;
        depth = 0;
        hitBox = null;
        hitElement = null;
        hitText = null;
    }

    HitResult result() {
        HitResult r = hitBox == null ? null : new HitResult(hitElement, hitBox, hitX, hitY, hitText, hitOffset);
        hitBox = null;
        hitElement = null;
        hitText = null;
        return r;
    }

    private void hit(Box box, Element element, float localX, float localY, Text text, int offset) {
        hitBox = box;
        hitElement = element;
        hitX = localX;
        hitY = localY;
        hitText = text;
        hitOffset = offset;
    }

    private static boolean hittable(ComputedStyle s) {
        return s.pointerEvents != PointerEvents.NONE && s.visibility == Visibility.VISIBLE;
    }

    // ---- State ----

    @Override
    public boolean enterContext(Box box, ComputedStyle style, float x, float y, Affine transform) {
        if (transform != null) {
            inverse.identity().translate(x, y).multiply(transform);
            if (!inverse.invert()) return false; // collapsed (scale(0)): nothing to hit
        }
        if (depth + 2 > saved.length) saved = Arrays.copyOf(saved, saved.length * 2);
        saved[depth++] = px;
        saved[depth++] = py;
        if (transform != null) {
            float x0 = px;
            px = inverse.mapX(x0, py);
            py = inverse.mapY(x0, py);
        }
        return true;
    }

    @Override
    public void exitContext() {
        py = saved[--depth];
        px = saved[--depth];
    }

    @Override
    public boolean pushClip(float x, float y, float width, float height) {
        return px >= x && py >= y && px < x + width && py < y + height;
    }

    @Override
    public void popClip() {}

    // ---- Steps ----

    @Override
    public void box(Box box, ComputedStyle s, float x, float y) {
        if (box.element == null || !hittable(s)) return;
        float[] r = Shapes.radii(s, box.width, box.height, radii) ? radii : null;
        if (Shapes.contains(x, y, box.width, box.height, r, px, py)) hit(box, box.element, px - x, py - y, null, -1);
    }

    @Override
    public void inlineBox(Box block, Fragment.InlineBox f, float x, float y) {
        if (f.element() == null || !hittable(StackingOrder.styleOf(f))) return;
        if (Shapes.contains(x + f.x(), y + f.y(), f.width(), f.height(), null, px, py)) {
            hit(block, f.element(), px - x, py - y, null, -1);
        }
    }

    @Override
    public void textRun(Box block, Fragment.TextRun run, float x, float y) {
        ComputedStyle s = StackingOrder.styleOf(run);
        if (!hittable(s) || !Shapes.contains(x + run.x(), y + run.y(), run.width(), run.height(), null, px, py)) return;
        Text node = run.node();
        Element element = node != null && node.parentElement() != null ? node.parentElement()
                : run.styleSource() != null ? run.styleSource() : block.element;
        if (element == null) return;
        int offset = offsetAt(run, s, px - x - run.x());
        hit(block, element, px - x, py - y, node, node == null ? -1 : Math.min(run.start() + offset, run.end()));
    }

    /** The caret position (in chars of the run's text) nearest to {@code localX}, using the host's glyph widths. */
    private int offsetAt(Fragment.TextRun run, ComputedStyle s, float localX) {
        FontSpec font = FontSpec.of(s);
        String text = run.text();
        float pos = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            float advance = fonts.charWidth(cp, font) + s.letterSpacing;
            if (localX < pos + advance / 2) return i;
            pos += advance;
            i += Character.charCount(cp);
        }
        return text.length();
    }

    @Override
    public void after(Box box, ComputedStyle s, float x, float y) {
        if (!box.isScrollContainer() || !hittable(s)) return;
        for (int axis = 0; axis < 2; axis++) {
            boolean vertical = axis == 0;
            if (Scrollbars.track(box, vertical, painter.scrollbarHovered(box, vertical), bar)
                    && Shapes.contains(x + bar[0], y + bar[1], bar[2], bar[3], null, px, py)) {
                hit(box, box.element, px - x, py - y, null, -1);
                return;
            }
        }
    }
}
