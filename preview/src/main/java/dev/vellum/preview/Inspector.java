package dev.vellum.preview;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.FontMetrics;
import dev.vellum.engine.layout.Box;
import dev.vellum.engine.paint.Affine;
import dev.vellum.engine.paint.Canvas;
import dev.vellum.engine.paint.Coordinates;
import dev.vellum.engine.paint.HitResult;
import dev.vellum.preview.render.MinecraftFont;

import java.util.Locale;

/**
 * The F12 overlay: highlights the element under the pointer like browser devtools (margin orange, border yellow,
 * padding green, content blue) and labels it with its tag, id, classes and size. The boxes are drawn where the element
 * is painted, through its transforms and scrolling.
 */
final class Inspector {
    private static final int MARGIN = 0x80F6B26B, BORDER = 0x80FFE08A, PADDING = 0x8093C47D, CONTENT = 0x806FA8DC;

    private Inspector() {}

    /** Paints the overlay for the element at ({@code x}, {@code y}) in viewport px. */
    static void paint(Canvas canvas, FontMetrics fonts, Document document, float x, float y) {
        HitResult hit = document == null ? null : document.hitTest(x, y);
        if (hit == null) return;
        Element element = hit.element();
        Box box = element == null ? null : element.box != null ? element.box : hit.box();
        if (box == null) return;

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

        float[] bounds = toViewport.mapBounds(margin[0], margin[1], margin[2] - margin[0], margin[3] - margin[1], new float[4]);
        String text = element + "  " + format(box.width) + " × " + format(box.height);
        float bottom = bounds[1] + bounds[3];
        float labelY = bottom + 12 <= document.viewportHeight() ? bottom + 2 : Math.max(0, bounds[1] - 12);
        label(canvas, fonts, document, text, bounds[0], labelY);
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
