package dev.vellum.engine.anim;

import dev.vellum.engine.paint.Affine;
import dev.vellum.engine.style.Colors;
import dev.vellum.engine.style.Display;
import dev.vellum.engine.style.Length;
import dev.vellum.engine.style.Prop;
import dev.vellum.engine.style.Shadow;
import dev.vellum.engine.style.TransformFunction.Interpolated;
import dev.vellum.engine.style.TransformFunction.Matrix;
import dev.vellum.engine.style.TransformFunction.Rotate;
import dev.vellum.engine.style.TransformFunction.Scale;
import dev.vellum.engine.style.TransformFunction.Translate;
import dev.vellum.engine.style.TransformFunction;
import dev.vellum.engine.style.Visibility;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.vellum.engine.anim.Interpolate.canInterpolate;
import static dev.vellum.engine.anim.Interpolate.value;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterpolateTest {
    @Test
    void lengthsInterpolatePxAndPercentSeparately() {
        assertEquals(Length.of(15, 25), value(Prop.WIDTH, Length.px(10), Length.of(20, 50), 0.5f));
        assertTrue(canInterpolate(Prop.WIDTH, Length.px(10), Length.percent(50)));
    }

    @Test
    void keywordLengthsFlipAtTheMidpoint() {
        assertFalse(canInterpolate(Prop.WIDTH, Length.AUTO, Length.px(10)));
        assertSame(Length.AUTO, value(Prop.WIDTH, Length.AUTO, Length.px(10), 0.49f));
        assertEquals(Length.px(10), value(Prop.WIDTH, Length.AUTO, Length.px(10), 0.5f));
    }

    @Test
    void floatsAndInts() {
        assertEquals(0.25f, value(Prop.OPACITY, 0f, 1f, 0.25f));
        assertEquals(-0.1f, (Float) value(Prop.OPACITY, 0f, 1f, -0.1f), 1e-6); // overshoot extrapolates
        assertEquals(550, value(Prop.FONT_WEIGHT, 400, 700, 0.5f)); // rounded
        assertEquals(1, value(Prop.ORDER, 0, 3, 0.3f));
    }

    @Test
    void nanFloatsAreDiscrete() {
        assertFalse(canInterpolate(Prop.LINE_HEIGHT, Float.NaN, 12f)); // line-height: normal
        assertEquals(Float.NaN, value(Prop.LINE_HEIGHT, Float.NaN, 12f, 0.25f));
        assertEquals(12f, value(Prop.LINE_HEIGHT, Float.NaN, 12f, 0.75f));
        assertFalse(canInterpolate(Prop.ASPECT_RATIO, 2f, Float.NaN));
    }

    @Test
    void autoZIndexIsDiscrete() {
        assertFalse(canInterpolate(Prop.Z_INDEX, null, 5));
        assertEquals(null, value(Prop.Z_INDEX, null, 5, 0.4f));
        assertEquals(5, value(Prop.Z_INDEX, null, 5, 0.6f));
        assertEquals(3, value(Prop.Z_INDEX, 1, 5, 0.5f));
    }

    @Test
    void colorsInterpolatePremultiplied() {
        // Fading in from transparent black keeps the colour instead of passing through dark red.
        int half = (Integer) value(Prop.COLOR, Colors.TRANSPARENT, 0xFFFF0000, 0.5f);
        assertEquals(0x80, Colors.alpha(half), 1);
        assertEquals(0xFF, Colors.red(half));
        assertEquals(0xFF7F7F7F, (int) (Integer) value(Prop.BACKGROUND_COLOR, Colors.BLACK, 0xFFFEFEFE, 0.5f));
    }

    @Test
    void discretePropertiesFlipAtTheMidpoint() {
        assertFalse(canInterpolate(Prop.DISPLAY, Display.BLOCK, Display.FLEX));
        assertEquals(Display.BLOCK, value(Prop.DISPLAY, Display.BLOCK, Display.FLEX, 0.49f));
        assertEquals(Display.FLEX, value(Prop.DISPLAY, Display.BLOCK, Display.FLEX, 0.5f));
    }

    @Test
    void visibilityStaysVisibleInBetween() {
        assertTrue(canInterpolate(Prop.VISIBILITY, Visibility.HIDDEN, Visibility.VISIBLE));
        assertEquals(Visibility.HIDDEN, value(Prop.VISIBILITY, Visibility.HIDDEN, Visibility.VISIBLE, 0));
        assertEquals(Visibility.VISIBLE, value(Prop.VISIBILITY, Visibility.HIDDEN, Visibility.VISIBLE, 0.01f));
        assertEquals(Visibility.VISIBLE, value(Prop.VISIBILITY, Visibility.VISIBLE, Visibility.HIDDEN, 0.99f));
        assertEquals(Visibility.HIDDEN, value(Prop.VISIBILITY, Visibility.VISIBLE, Visibility.HIDDEN, 1));
        assertFalse(canInterpolate(Prop.VISIBILITY, Visibility.HIDDEN, Visibility.COLLAPSE));
    }

    @Test
    void nonAnimatablePropertiesDoNotInterpolate() {
        assertFalse(canInterpolate(Prop.TRANSITION, List.of(), List.of()));
        assertFalse(canInterpolate(Prop.CONTENT, "a", "b"));
    }

    @Test
    void shadowsPairwiseWithPadding() {
        Shadow a = new Shadow(2, 2, 4, 0, 0xFF000000, false);
        Shadow b = new Shadow(4, 0, 0, 2, 0xFF000000, false);
        assertEquals(List.of(new Shadow(3, 1, 2, 1, 0xFF000000, false)), value(Prop.BOX_SHADOW, List.of(a), List.of(b), 0.5f));

        // The shorter list is padded with transparent zero shadows of the same inset.
        Shadow inset = new Shadow(0, 0, 8, 0, 0xFFFFFFFF, true);
        @SuppressWarnings("unchecked")
        List<Shadow> padded = (List<Shadow>) value(Prop.BOX_SHADOW, List.of(), List.of(inset), 0.5f);
        assertEquals(1, padded.size());
        assertEquals(4, padded.getFirst().blur());
        assertTrue(padded.getFirst().inset());
        assertEquals(0x80, Colors.alpha(padded.getFirst().color()), 1);
    }

    @Test
    void mismatchedOrNativeShadowsAreDiscrete() {
        Shadow outer = new Shadow(1, 1, 0, 0, Colors.BLACK, false);
        Shadow inner = new Shadow(1, 1, 0, 0, Colors.BLACK, true);
        assertFalse(canInterpolate(Prop.BOX_SHADOW, List.of(outer), List.of(inner)));
        assertFalse(canInterpolate(Prop.TEXT_SHADOW, List.of(Shadow.MINECRAFT), List.of()));
        assertEquals(List.of(Shadow.MINECRAFT), value(Prop.TEXT_SHADOW, List.of(Shadow.MINECRAFT), List.of(outer), 0.4f));
        assertEquals(List.of(outer), value(Prop.TEXT_SHADOW, List.of(Shadow.MINECRAFT), List.of(outer), 0.6f));
    }

    @Test
    void matchingTransformsInterpolateFunctionByFunction() {
        List<TransformFunction> from = List.of(new Translate(Length.px(0), Length.percent(0)), new Rotate(0));
        List<TransformFunction> to = List.of(new Translate(Length.px(10), Length.percent(100)), new Rotate(90));
        assertEquals(List.of(new Translate(Length.px(5), Length.percent(50)), new Rotate(45)),
                value(Prop.TRANSFORM, from, to, 0.5f));
    }

    @Test
    void emptyTransformListActsAsIdentityFunctions() {
        List<TransformFunction> to = List.of(new Translate(Length.px(10), Length.px(20)), new Scale(3, 1));
        assertEquals(List.of(new Translate(Length.px(5), Length.px(10)), new Scale(2, 1)),
                value(Prop.TRANSFORM, List.of(), to, 0.5f));
        assertEquals(List.of(new Translate(Length.px(5), Length.px(10)), new Scale(2, 1)),
                value(Prop.TRANSFORM, to, List.of(), 0.5f));
    }

    @Test
    void matricesBlendByDecomposingSoRotationsSurvive() {
        List<TransformFunction> from = List.of(new Matrix(1, 0, 0, 1, 0, 0));
        List<TransformFunction> to = List.of(new Matrix(-1, 0, 0, -1, 0, 0)); // a half turn
        List<?> half = (List<?>) value(Prop.TRANSFORM, from, to, 0.5f);
        assertEquals(List.of(new Interpolated(from, to, 0.5f)), half);
        @SuppressWarnings("unchecked")
        Affine m = new Affine().concat((List<TransformFunction>) half, 10, 10);
        assertEquals(1, Math.abs(m.determinant()), 1e-4, "a quarter turn, not the zero matrix: " + m);
    }

    @Test
    void mismatchedTransformsBlendAtPaintTime() {
        List<TransformFunction> from = List.of(new Rotate(45));
        List<TransformFunction> to = List.of(new Scale(2, 2), new Rotate(10));
        assertTrue(canInterpolate(Prop.TRANSFORM, from, to));
        assertEquals(List.of(new Interpolated(from, to, 0.25f)), value(Prop.TRANSFORM, from, to, 0.25f));
        assertSame(to, value(Prop.TRANSFORM, from, to, 1));
    }
}
