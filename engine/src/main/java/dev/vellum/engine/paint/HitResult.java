package dev.vellum.engine.paint;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Text;
import dev.vellum.engine.host.FontSpec;
import dev.vellum.engine.layout.Box;
import dev.vellum.engine.layout.Fragment;
import dev.vellum.engine.layout.TextMeasure;
import dev.vellum.engine.style.ComputedStyle;

/**
 * The result of a hit test: the element hit (for text, the text's parent element), the box the point is in, and the
 * point in that box's border-box coordinates (after transforms and scrolling, as {@link Coordinates} maps them). Over
 * a scroll container's scrollbar, {@link #scrollbar()} says which one. Over text, {@link #text()} is the text node and
 * {@link #textOffset()} the caret position nearest the point, worked out only when asked for.
 */
public final class HitResult {
    /** One of the overlay scrollbars of {@code container}: its vertical or its horizontal one. */
    public record Scrollbar(Box container, boolean vertical) {}

    private final Element element;
    private final Box box;
    private final float localX, localY;
    private final Scrollbar scrollbar;
    private final Fragment.TextRun run;
    private final ComputedStyle runStyle;
    private final float runX;
    private final TextMeasure measure;
    private int textOffset = -2;

    /** A hit on an element's box. */
    public HitResult(Element element, Box box, float localX, float localY) {
        this(element, box, localX, localY, null, null, null, 0, null);
    }

    HitResult(Element element, Box box, float localX, float localY, Scrollbar scrollbar, Fragment.TextRun run,
              ComputedStyle runStyle, float runX, TextMeasure measure) {
        this.element = element;
        this.box = box;
        this.localX = localX;
        this.localY = localY;
        this.scrollbar = scrollbar;
        this.run = run;
        this.runStyle = runStyle;
        this.runX = runX;
        this.measure = measure;
    }

    public Element element() { return element; }
    public Box box() { return box; }
    public float localX() { return localX; }
    public float localY() { return localY; }
    /** The scrollbar under the point, or null. */
    public Scrollbar scrollbar() { return scrollbar; }
    /** The text node under the point, or null (also for generated content). */
    public Text text() { return run == null ? null : run.node(); }

    /** The offset in {@link #text()}'s data of the caret position nearest the point, or -1 when no text was hit. */
    public int textOffset() {
        if (textOffset == -2) {
            textOffset = text() == null ? -1
                    : run.sourceIndex(measure.offsetAt(run.text(), FontSpec.of(runStyle), runStyle, runX));
        }
        return textOffset;
    }
}
