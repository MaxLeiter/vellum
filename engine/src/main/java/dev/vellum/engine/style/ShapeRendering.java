package dev.vellum.engine.style;

/**
 * The {@code shape-rendering} property, which picks how rounded boxes, borders and box-shadows are drawn where the
 * backend has a smoother way to draw them (the shape shader on Minecraft 26.3). {@code crispEdges} and
 * {@code optimizeSpeed} keep hard pixel edges; {@code auto} and {@code geometricPrecision} allow smooth ones.
 */
public enum ShapeRendering {
    AUTO, CRISP_EDGES
}
