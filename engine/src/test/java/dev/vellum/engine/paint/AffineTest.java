package dev.vellum.engine.paint;

import dev.vellum.engine.style.Length;
import dev.vellum.engine.style.TransformFunction;
import dev.vellum.engine.style.TransformFunction.Interpolated;
import dev.vellum.engine.style.TransformFunction.Rotate;
import dev.vellum.engine.style.TransformFunction.Scale;
import dev.vellum.engine.style.TransformFunction.Translate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AffineTest {
    private static final float EPS = 1e-4f;

    static void assertMatrix(Affine expected, Affine actual) {
        float[] e = {expected.a, expected.b, expected.c, expected.d, expected.e, expected.f};
        float[] a = {actual.a, actual.b, actual.c, actual.d, actual.e, actual.f};
        for (int i = 0; i < 6; i++) assertEquals(e[i], a[i], 1e-3f, "expected " + expected + " but was " + actual);
    }

    @Test
    void operationsPostMultiply() {
        Affine m = new Affine().translate(10, 5).scale(2, 3);
        assertEquals(12, m.mapX(1, 1), EPS);
        assertEquals(8, m.mapY(1, 1), EPS);
    }

    @Test
    void rotationIsClockwiseOnScreen() {
        Affine m = new Affine().rotate(90);
        assertEquals(0, m.mapX(1, 0), EPS);
        assertEquals(1, m.mapY(1, 0), EPS);
    }

    @Test
    void skewMatchesCss() {
        Affine m = new Affine().skew(45, 0); // x' = x + tan(45°)·y
        assertEquals(1, m.mapX(0, 1), EPS);
        assertEquals(1, m.mapY(0, 1), EPS);
    }

    @Test
    void inverseUndoes() {
        Affine m = new Affine().translate(7, -3).rotate(33).skew(10, 5).scale(2, 0.5f);
        Affine inv = new Affine().set(m);
        assertTrue(inv.invert());
        float x = m.mapX(4, 9), y = m.mapY(4, 9);
        assertEquals(4, inv.mapX(x, y), 1e-3f);
        assertEquals(9, inv.mapY(x, y), 1e-3f);
        assertMatrix(new Affine(), new Affine().set(m).multiply(inv));
    }

    @Test
    void singularMatrixDoesNotInvert() {
        Affine m = new Affine().scale(0, 1);
        assertFalse(m.invert());
        assertEquals(0, m.a);
    }

    @Test
    void decomposeRecomposeRoundTrips() {
        Affine[] cases = {
                new Affine(),
                new Affine().translate(12, -4),
                new Affine().translate(3, 4).rotate(30).scale(2, 3),
                new Affine().rotate(-120).skew(20, 0).scale(1.5f, 0.75f),
                new Affine().scale(-1, 1),
                new Affine().scale(1, -2).rotate(45),
                new Affine().skew(15, 25),
        };
        float[] parts = new float[6];
        for (Affine m : cases) {
            m.decompose(parts);
            assertMatrix(m, new Affine().recompose(parts));
        }
    }

    @Test
    void decompositionReadsTheComponents() {
        float[] p = new float[6];
        new Affine().translate(5, 6).rotate(30).skew(20, 0).scale(2, 3).decompose(p);
        assertEquals(5, p[Affine.TX], EPS);
        assertEquals(6, p[Affine.TY], EPS);
        assertEquals(Math.toRadians(30), p[Affine.ROTATE], 1e-4);
        assertEquals(2, p[Affine.SCALE_X], EPS);
        assertEquals(3, p[Affine.SCALE_Y], EPS);
        assertEquals(Math.toRadians(20), p[Affine.SKEW], 1e-4);
    }

    @Test
    void interpolatesRotation() {
        Affine mid = new Affine().interpolate(new Affine(), new Affine().rotate(90), 0.5f);
        assertMatrix(new Affine().rotate(45), mid);
    }

    @Test
    void interpolatesRotationTheShortWay() {
        Affine mid = new Affine().interpolate(new Affine().rotate(170), new Affine().rotate(-170), 0.5f);
        assertMatrix(new Affine().rotate(180), mid);
    }

    @Test
    void interpolatesAFlipAsAScaleNotARotation() {
        Affine mid = new Affine().interpolate(new Affine(), new Affine().scale(-1, 1), 0.25f);
        assertMatrix(new Affine().scale(0.5f, 1), mid);
    }

    @Test
    void interpolationEndsMatchInputs() {
        Affine a = new Affine().translate(3, 1).rotate(20).scale(2, 1);
        Affine b = new Affine().translate(-5, 9).rotate(200).skew(10, 0);
        assertMatrix(a, new Affine().interpolate(a, b, 0));
        assertMatrix(b, new Affine().interpolate(a, b, 1));
    }

    @Test
    void translatePercentagesUseTheReferenceBox() {
        Affine m = new Affine().concat(List.of(new Translate(Length.percent(50), Length.of(2, 25))), 40, 20);
        assertEquals(20, m.e, EPS);
        assertEquals(7, m.f, EPS);
    }

    @Test
    void interpolatedListsBlendTheirMatrices() {
        List<TransformFunction> from = List.of(new Translate(Length.px(10), Length.ZERO));
        List<TransformFunction> to = List.of(new Scale(2, 2));
        Affine m = new Affine().concat(List.of(new Interpolated(from, to, 0.5f)), 100, 100);
        assertMatrix(new Affine().set(1.5f, 0, 0, 1.5f, 5, 0), m);
    }

    @Test
    void nestedInterpolatedListsResolve() {
        TransformFunction inner = new Interpolated(List.of(new Rotate(0)), List.of(new Rotate(90)), 0.5f);
        Affine m = new Affine().concat(List.of(new Interpolated(List.of(inner), List.of(new Rotate(45)), 0.5f)), 10, 10);
        assertMatrix(new Affine().rotate(45), m);
        // The same instance resolves again without stale scratch state.
        assertMatrix(new Affine().rotate(45), m.identity().concat(List.of(new Interpolated(List.of(inner), List.of(new Rotate(45)), 0.5f)), 10, 10));
    }
}
