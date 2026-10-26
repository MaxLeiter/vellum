package dev.vellum.engine.inspect;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.FontFamilies;
import dev.vellum.engine.host.FontSpec;
import dev.vellum.engine.layout.Box;
import dev.vellum.engine.paint.Affine;
import dev.vellum.engine.paint.Canvas;
import dev.vellum.engine.paint.Coordinates;
import dev.vellum.engine.paint.HitResult;

import java.util.List;
import java.util.Locale;

/**
 * Highlights an element like browser devtools (margin orange, border yellow, padding green, content blue) and labels
 * it with its tag, id, classes and size. The boxes are drawn where the element is painted, through its transforms and
 * scrolling.
 */
public final class BoxHighlight {
    private static final int MARGIN = 0x80F6B26B, BORDER = 0x80FFE08A, PADDING = 0x8093C47D, CONTENT = 0x806FA8DC;
    private static final FontSpec LABEL_FONT = new FontSpec(List.of(FontFamilies.DEFAULT), 8, false, false);

    private BoxHighlight() {}

    /** Highlights the element at ({@code x}, {@code y}) in viewport px. */
    public static void paintAt(Canvas canvas, Document document, float x, float y) {
        HitResult hit = document == null ? null : document.hitTest(x, y);
        if (hit == null) return;
        Element element = hit.element();
        paint(canvas, element, element == null || element.box == null ? hit.box() : element.box);
    }

    /** Highlights {@code element}, if it has a box. */
    public static void paint(Canvas canvas, Element element) {
        if (element != null) paint(canvas, element, element.box);
    }

    private static void paint(Canvas canvas, Element element, Box box) {
        if (box == null) return;
        Document document = element != null ? element.ownerDocument() : null;
        Affine toViewport = Coordinates.toViewport(box, new Affine());
        canvas.save();
        canvas.transform(toViewport.a, toViewport.b, toViewport.c, toViewport.d, toViewport.e, toViewport.f);
        float[] border = {0, 0, box.width, box.height};
        float[] margin = {-box.marginLeft, -box.marginTop, box.width + box.marginRight, box.height + box.marginBottom};
        float[] padding = inset(border, box.borderLeft, box.borderTop, box.borderRight, box.borderBottom);
        float[] content = inset(padding, box.paddingLeft, box.paddingTop, box.paddingRight, box.paddingBottom);
        ring(canvas, margin, border, MARGIN);
        ring(canvas, border, padding, BORDER);
        ring(canvas, padding, content, PADDING);
        canvas.fillRect(content[0], content[1], content[2] - content[0], content[3] - content[1], CONTENT);
        canvas.restore();
        if (document == null) return;

        float[] bounds = toViewport.mapBounds(margin[0], margin[1], margin[2] - margin[0], margin[3] - margin[1], new float[4]);
        String text = element + "  " + format(box.width) + " × " + format(box.height);
        float bottom = bounds[1] + bounds[3];
        float labelY = bottom + 12 <= document.viewportHeight() ? bottom + 2 : Math.max(0, bounds[1] - 12);
        float width = document.host().fonts().width(text, LABEL_FONT);
        float labelX = Math.max(0, Math.min(bounds[0], document.viewportWidth() - width - 4));
        canvas.fillRect(labelX, labelY, width + 4, 11, 0xE0101010);
        canvas.drawText(text, labelX + 2, labelY + 1, LABEL_FONT, 0xFFFFFFFF, 0, false);
    }

    private static float[] inset(float[] r, float left, float top, float right, float bottom) {
        return new float[] {r[0] + left, r[1] + top, Math.max(r[0] + left, r[2] - right), Math.max(r[1] + top, r[3] - bottom)};
    }

    /** Fills the area between an outer and an inner rectangle ({x0, y0, x1, y1}) with four bars. */
    private static void ring(Canvas canvas, float[] outer, float[] inner, int color) {
        canvas.fillRect(outer[0], outer[1], outer[2] - outer[0], inner[1] - outer[1], color);
        canvas.fillRect(outer[0], inner[3], outer[2] - outer[0], outer[3] - inner[3], color);
        canvas.fillRect(outer[0], inner[1], inner[0] - outer[0], inner[3] - inner[1], color);
        canvas.fillRect(inner[2], inner[1], outer[2] - inner[2], inner[3] - inner[1], color);
    }

    private static String format(float px) {
        return px == Math.round(px) ? Integer.toString(Math.round(px)) : String.format(Locale.ROOT, "%.1f", px);
    }
}
