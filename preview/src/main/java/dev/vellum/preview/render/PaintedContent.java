package dev.vellum.preview.render;

import dev.vellum.engine.host.ReplacedContent;
import dev.vellum.engine.paint.Canvas;

/** Replaced content that draws itself with ordinary canvas calls; {@link ImageCanvas#drawReplaced} delegates to it. */
public interface PaintedContent extends ReplacedContent {
    /** Paints into the content box (GUI px). */
    void paint(Canvas canvas, float x, float y, float width, float height);
}
