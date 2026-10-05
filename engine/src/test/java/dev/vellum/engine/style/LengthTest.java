package dev.vellum.engine.style;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Math on lengths: what folds into {@code px + percent}, what waits for the reference, and interpolation. */
class LengthTest {
    private static final Length CLAMP = Length.clamp(Length.px(72), Length.percent(25), Length.px(100));

    @Test
    void comparisonsOfOneKindFold() {
        assertEquals(Length.px(10), Length.min(List.of(Length.px(10), Length.px(20))));
        assertEquals(Length.percent(20), Length.max(List.of(Length.percent(10), Length.percent(20))));
        assertEquals(Length.px(20), Length.clamp(Length.px(1), Length.px(50), Length.px(20)));
        assertEquals(Length.px(30), Length.clamp(Length.px(30), Length.px(5), Length.px(20)), "the minimum wins");
        assertEquals(Length.px(10), Length.max(List.of(Length.ZERO, Length.px(10))), "zero is either kind");
        assertTrue(Length.min(List.of(Length.px(10), Length.percent(5))).hasPercent());
        assertFalse(Length.min(List.of(Length.px(10), Length.percent(5))).isLinear());
    }

    @Test
    void mixedComparisonsResolveAgainstEachReference() {
        assertEquals(72, CLAMP.resolve(200));
        assertEquals(80, CLAMP.resolve(320));
        assertEquals(100, CLAMP.resolve(800));
        assertEquals(-5, CLAMP.resolve(Float.NaN, -5), "an unknown reference falls back, as for any percentage");
        Length min = Length.min(List.of(Length.px(40), Length.of(-4, 50)));
        assertEquals(16, min.resolve(40));
        assertEquals(40, min.resolve(400));
    }

    @Test
    void sumsAndProductsKeepTheTerms() {
        Length l = Length.sum(CLAMP.times(2), Length.of(1, 10)); // calc(10% + 1px + 2 * clamp(...))
        assertEquals(2 * 80 + 1 + 32, l.resolve(320));
        assertEquals("calc(10% + 1px + 2 * clamp(72px, 25%, 100px))", l.toString());
        assertEquals(Length.of(1, 10), Length.sum(l, CLAMP.times(-2)), "equal terms merge and cancel");
        assertSame(Length.ZERO, CLAMP.times(0));
        assertEquals("calc(-1 * clamp(72px, 25%, 100px))", CLAMP.times(-1).toString());
        assertEquals("calc(10px - clamp(72px, 25%, 100px))", Length.sum(Length.px(10), CLAMP.times(-1)).toString());
        assertEquals("calc(100% - 12px)", Length.of(-12, 100).toString());
    }

    @Test
    void equalityCoversTheTerms() {
        assertEquals(CLAMP, Length.clamp(Length.px(72), Length.percent(25), Length.px(100)));
        assertEquals(CLAMP.hashCode(), Length.clamp(Length.px(72), Length.percent(25), Length.px(100)).hashCode());
        assertNotEquals(CLAMP, Length.clamp(Length.px(72), Length.percent(26), Length.px(100)));
        assertNotEquals(Length.ZERO, Length.min(List.of(Length.px(0), Length.of(-1, 1))));
    }

    @Test
    void interpolationMixesTheTwoExpressions() {
        Length from = Length.px(40), to = CLAMP;
        Length half = Length.lerp(from, to, 0.5f); // calc(20px + 0.5 * clamp(...))
        for (float reference : new float[] {100, 320, 800}) {
            assertEquals((40 + to.resolve(reference)) / 2, half.resolve(reference), 1e-4f, "at " + reference);
            assertEquals(to.resolve(reference), Length.lerp(from, to, 1).resolve(reference), 1e-4f);
            assertEquals(40, Length.lerp(from, to, 0).resolve(reference), 1e-4f);
        }
        // Retargeting from a value part-way through does not pile up terms: equal terms merge.
        Length back = Length.lerp(half, from, 0.5f);
        assertEquals("calc(30px + 0.25 * clamp(72px, 25%, 100px))", back.toString());
        assertEquals(Length.px(40), Length.lerp(back, from, 1));
        assertSame(Length.AUTO, Length.lerp(Length.AUTO, CLAMP, 0.4f), "keywords flip at the midpoint");
        assertSame(CLAMP, Length.lerp(Length.AUTO, CLAMP, 0.5f));
    }

    @Test
    void plainLengthsStayLinear() {
        assertTrue(Length.lerp(Length.px(10), Length.percent(50), 0.5f).isLinear());
        assertEquals(Length.of(5, 25), Length.lerp(Length.px(10), Length.percent(50), 0.5f));
        assertTrue(Length.sum(Length.px(1), Length.percent(1)).isLinear());
        assertTrue(Length.of(-1, -5).isNegative());
        assertFalse(Length.of(-12, 100).isNegative());
        assertFalse(CLAMP.times(-1).isNegative(), "terms are only known at layout");
    }
}
