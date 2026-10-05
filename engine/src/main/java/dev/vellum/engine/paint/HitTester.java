package dev.vellum.engine.paint;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Text;
import dev.vellum.engine.layout.TextMeasure;
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
    private final TextMeasure measure;
    private final Affine inverse = new Affine();
    private final float[] radii = new float[8], bar = new float[4];
    /** The point in the current space, and the points saved by enclosing contexts. */
    private float px, py;
    private float[] saved = new float[16];
    private int depth;

    private Box hitBox;
    private Element hitElement;
    private float hitX, hitY;
    private HitResult.Scrollbar hitBar;
    private Fragment.TextRun hitRun;
    private ComputedStyle hitRunStyle;
    private float hitRunX;

    HitTester(Painter painter, TextMeasure measure) {
        this.painter = painter;
        this.measure = measure;
    }

    void begin(float x, float y) {
        px = x;
        py = y;
        depth = 0;
        clear();
    }

    HitResult result() {
        HitResult r = hitBox == null ? null
                : new HitResult(hitElement, hitBox, hitX, hitY, hitBar, hitRun, hitRunStyle, hitRunX, measure);
        clear();
        return r;
    }

    private void clear() {
        hitBox = null;
        hitElement = null;
        hitBar = null;
        hitRun = null;
        hitRunStyle = null;
    }

    private void hit(Box box, Element element, float localX, float localY) {
        clear();
        hitBox = box;
        hitElement = element;
        hitX = localX;
        hitY = localY;
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
        return inside(x, y, width, height);
    }

    @Override
    public void popClip() {}

    // ---- Steps ----

    @Override
    public void box(Box box, ComputedStyle s, float x, float y) {
        if (box.element == null || !hittable(s) || !inside(x, y, box.width, box.height)) return;
        float[] r = Shapes.radii(s, box.width, box.height, radii) ? radii : null;
        if (r == null || Shapes.contains(x, y, box.width, box.height, r, px, py)) hit(box, box.element, px - x, py - y);
    }

    @Override
    public void inlineBox(Box block, Fragment.InlineBox f, ComputedStyle s, float x, float y) {
        Box box = f.box();
        if (!hittable(s) || !inside(x + f.x(), y + f.y(), f.width(), f.height())) return;
        hit(box, box.element, px - x - box.x, py - y - box.y);
    }

    @Override
    public void textRun(Box block, Fragment.TextRun run, float x, float y) {
        ComputedStyle s = StackingOrder.styleOf(run);
        if (!hittable(s) || !inside(x + run.x(), y + run.y(), run.width(), run.height())) return;
        Text node = run.node();
        Element element = node != null && node.parentElement() != null ? node.parentElement()
                : run.styleSource() != null ? run.styleSource() : block.element;
        if (element == null) return;
        // The text belongs to its element's inline box when it has one on these lines, else to the block.
        Box box = element.box != null && element.box.parent == block && element.box.kind == Box.Kind.INLINE
                ? element.box : block;
        hit(box, element, px - x - (box == block ? 0 : box.x), py - y - (box == block ? 0 : box.y));
        hitRun = run;
        hitRunStyle = s;
        hitRunX = px - x - run.x();
    }

    @Override
    public void inlineOutline(Fragment.InlineBox fragment, ComputedStyle style, float x, float y) {}

    @Override
    public void after(Box box, ComputedStyle s, float x, float y) {
        if (!box.isScrollContainer() || !hittable(s)) return;
        for (int axis = 0; axis < 2; axis++) {
            boolean vertical = axis == 0;
            if (Scrollbars.track(box, vertical, painter.scrollbarHovered(box, vertical), bar)
                    && inside(x + bar[0], y + bar[1], bar[2], bar[3])) {
                hit(box, box.element, px - x, py - y);
                hitBar = new HitResult.Scrollbar(box, vertical);
                return;
            }
        }
    }

    /** Whether the point is in the rectangle; the cheap test before anything else. */
    private boolean inside(float x, float y, float width, float height) {
        return px >= x && py >= y && px < x + width && py < y + height;
    }
}
