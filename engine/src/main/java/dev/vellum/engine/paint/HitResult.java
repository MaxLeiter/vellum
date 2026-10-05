package dev.vellum.engine.paint;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Text;
import dev.vellum.engine.layout.Box;

/**
 * The result of a hit test: the element hit (for text, the text's parent element), the box, and the point in the
 * box's local border-box coordinates (after transforms and scrolling). {@code text}/{@code textOffset} are set when
 * the point is over a text run, for caret placement.
 */
public record HitResult(Element element, Box box, float localX, float localY, Text text, int textOffset) {}
