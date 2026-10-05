package dev.vellum.preview;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.FontMetrics;
import dev.vellum.engine.layout.Box;
import dev.vellum.engine.paint.Canvas;
import dev.vellum.engine.paint.HitResult;
import dev.vellum.preview.render.MinecraftFont;

import java.util.Locale;

/**
 * The F12 overlay: highlights the element under the pointer like browser devtools (margin orange, border yellow,
 * padding green, content blue) and labels it with its tag, id, classes and size. Geometry is the box's viewport rect
 * from layout, so transforms are not reflected.
 */
final class Inspector {
    private static final int MARGIN = 0x80F6B26B, BORDER = 0x80FFE08A, PADDING = 0x8093C47D, CONTENT = 0x806FA8DC;

    private Inspector() {}

    /** Paints the overlay for the element at ({@code x}, {@code y}) in viewport px. */
    static void paint(Canvas canvas, FontMetrics fonts, Document document, float x, float y) {
        if (document == null) return;
        HitResult hit;
        try {
            hit = document.painter().hitTest(x, y);
        } catch (RuntimeException e) {
            label(canvas, fonts, document, "Inspector: hit testing failed (" + e + ")", 2, 2);
            return;
        }
        Element element = hit == null ? null : hit.element();
        Box box = element == null ? null : element.box != null ? element.box : hit.box();
        if (box == null) return;

        float left = box.absoluteX(), top = box.absoluteY();
        float[] border = {left, top, left + box.width, top + box.height};
        float[] margin = {border[0] - box.marginLeft, border[1] - box.marginTop, border[2] + box.marginRight, border[3] + box.marginBottom};
        float[] padding = inset(border, box.borderLeft, box.borderTop, box.borderRight, box.borderBottom);
        float[] content = inset(padding, box.paddingLeft, box.paddingTop, box.paddingRight, box.paddingBottom);
        ring(canvas, margin, border, MARGIN);
        ring(canvas, border, padding, BORDER);
        ring(canvas, padding, content, PADDING);
        canvas.fillRect(content[0], content[1], content[2] - content[0], content[3] - content[1], CONTENT);

        String text = element + "  " + format(box.width) + " × " + format(box.height);
        float labelY = margin[3] + 12 <= document.viewportHeight() ? margin[3] + 2 : Math.max(0, margin[1] - 12);
        label(canvas, fonts, document, text, margin[0], labelY);
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

    private static void label(Canvas canvas, FontMetrics fonts, Document document, String text, float x, float y) {
        float width = fonts.width(text, MinecraftFont.NATIVE);
        x = Math.max(0, Math.min(x, document.viewportWidth() - width - 4));
        canvas.fillRect(x, y, width + 4, 11, 0xE0101010);
        canvas.drawText(text, x + 2, y + 1, MinecraftFont.NATIVE, 0xFFFFFFFF, 0, false);
    }

    private static String format(float px) {
        return px == Math.round(px) ? Integer.toString(Math.round(px)) : String.format(Locale.ROOT, "%.1f", px);
    }
}
