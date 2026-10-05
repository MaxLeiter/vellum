package dev.vellum.engine.style;

/**
 * One 2D transform function. Minecraft's GUI pose is a 2D affine matrix, so 3D functions are not supported
 * ({@code rotateZ} is accepted as {@code rotate}). Translations keep {@link Length}s because percentages refer to
 * the element's own border box; angles are degrees.
 */
public sealed interface TransformFunction {
    record Translate(Length x, Length y) implements TransformFunction {}
    record Scale(float x, float y) implements TransformFunction {}
    record Rotate(float degrees) implements TransformFunction {}
    record Skew(float xDegrees, float yDegrees) implements TransformFunction {}
    /** {@code matrix(a, b, c, d, e, f)}. */
    record Matrix(float a, float b, float c, float d, float e, float f) implements TransformFunction {}
}
