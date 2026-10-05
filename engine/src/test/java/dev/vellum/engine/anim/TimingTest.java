package dev.vellum.engine.anim;

import dev.vellum.engine.style.AnimationSpec.Direction;
import dev.vellum.engine.style.AnimationSpec.FillMode;
import dev.vellum.engine.style.TimingFunction;
import dev.vellum.engine.style.TimingFunction.CubicBezier;
import dev.vellum.engine.style.TimingFunction.Steps;
import dev.vellum.engine.style.TimingFunction.Steps.Jump;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimingTest {
    private static final double EPS = 1e-3;

    // ---- Timing functions ----

    @Test
    void namedCubicBeziers() {
        assertEquals(0.8024, TimingFunction.EASE.apply(0.5f), EPS);
        assertEquals(0.4085, TimingFunction.EASE.apply(0.25f), EPS);
        assertEquals(0.3154, TimingFunction.EASE_IN.apply(0.5f), EPS);
        assertEquals(0.6846, TimingFunction.EASE_OUT.apply(0.5f), EPS);
        assertEquals(0.5, TimingFunction.EASE_IN_OUT.apply(0.5f), EPS);
        assertEquals(0.3, TimingFunction.LINEAR.apply(0.3f), EPS);
        for (TimingFunction f : new TimingFunction[] {TimingFunction.EASE, TimingFunction.EASE_IN_OUT}) {
            assertEquals(0, f.apply(0));
            assertEquals(1, f.apply(1));
        }
    }

    @Test
    void overshootingBezier() {
        TimingFunction back = new CubicBezier(0.3f, 1.6f, 0.7f, 1.6f);
        assertTrue(back.apply(0.6f) > 1, "overshoots past 1");
        assertEquals(0.5, new CubicBezier(0.5f, -0.5f, 0.5f, 1.5f).apply(0.5f), EPS); // symmetric
    }

    @Test
    void steps() {
        Steps end = new Steps(4, Jump.END);
        assertEquals(0, end.apply(0));
        assertEquals(0.25, end.apply(0.3f), EPS);
        assertEquals(0.75, end.apply(0.99f), EPS);
        assertEquals(1, end.apply(1));

        Steps start = new Steps(4, Jump.START);
        assertEquals(0.25, start.apply(0), EPS);
        assertEquals(0.5, start.apply(0.3f), EPS);

        Steps none = new Steps(5, Jump.NONE);
        assertEquals(0, none.apply(0.1f), EPS);
        assertEquals(0.5, none.apply(0.5f), EPS);
        assertEquals(1, none.apply(0.9f), EPS);

        Steps both = new Steps(3, Jump.BOTH);
        assertEquals(0.25, both.apply(0), EPS);
        assertEquals(0.5, both.apply(0.5f), EPS);
        assertEquals(0.75, both.apply(0.9f), EPS);
    }

    // ---- The timing model ----

    private static Timing timing(double delay, double duration, double iterations, Direction direction,
                                 FillMode fill) {
        return new Timing(delay, duration, iterations, direction, fill, TimingFunction.LINEAR);
    }

    @Test
    void phasesAndFill() {
        Timing none = timing(100, 200, 1, Direction.NORMAL, FillMode.NONE);
        assertEquals(new Timing.Sample(Timing.Phase.BEFORE, Double.NaN, Double.NaN), none.sample(50, false));
        assertEquals(Timing.Phase.ACTIVE, none.sample(100, false).phase());
        assertEquals(0.5, none.sample(200, false).progress(), EPS);
        assertFalse(none.sample(300, false).hasEffect());
        assertEquals(Timing.Phase.AFTER, none.sample(300, false).phase());

        Timing both = timing(100, 200, 1, Direction.NORMAL, FillMode.BOTH);
        assertEquals(0, both.sample(50, false).progress());
        assertEquals(1, both.sample(1000, false).progress());
    }

    @Test
    void reversedPlaybackEndsBeforeTheStart() {
        Timing t = timing(0, 100, 1, Direction.NORMAL, FillMode.NONE);
        assertEquals(Timing.Phase.ACTIVE, t.sample(0, false).phase());
        assertEquals(Timing.Phase.BEFORE, t.sample(0, true).phase());
        assertEquals(Timing.Phase.ACTIVE, t.sample(100, true).phase());
    }

    @Test
    void iterationsAndDirections() {
        Timing normal = timing(0, 100, 3, Direction.NORMAL, FillMode.FORWARDS);
        assertEquals(1, normal.sample(150, false).iteration());
        assertEquals(0.5, normal.sample(150, false).progress(), EPS);
        assertEquals(1, normal.sample(300, false).progress()); // ends on the last frame of the last iteration
        assertEquals(2, normal.sample(300, false).iteration());

        assertEquals(0.75, timing(0, 100, 1, Direction.REVERSE, FillMode.NONE).sample(25, false).progress(), EPS);

        Timing alternate = timing(0, 100, 2, Direction.ALTERNATE, FillMode.FORWARDS);
        assertEquals(0.25, alternate.sample(25, false).progress(), EPS);
        assertEquals(0.75, alternate.sample(125, false).progress(), EPS);
        assertEquals(0, alternate.sample(200, false).progress(), EPS);

        Timing alternateReverse = timing(0, 100, 2, Direction.ALTERNATE_REVERSE, FillMode.NONE);
        assertEquals(0.75, alternateReverse.sample(25, false).progress(), EPS);
        assertEquals(0.25, alternateReverse.sample(125, false).progress(), EPS);
    }

    @Test
    void fractionalAndInfiniteIterations() {
        Timing half = timing(0, 100, 1.5, Direction.NORMAL, FillMode.FORWARDS);
        assertEquals(150, half.activeDuration());
        assertEquals(0.5, half.sample(500, false).progress(), EPS);
        assertEquals(1, half.sample(500, false).iteration());

        Timing infinite = timing(0, 100, Double.POSITIVE_INFINITY, Direction.NORMAL, FillMode.NONE);
        assertEquals(Double.POSITIVE_INFINITY, infinite.endTime());
        assertEquals(0.5, infinite.sample(1_000_050, false).progress(), EPS);
        assertEquals(10_000, infinite.sample(1_000_050, false).iteration());
    }

    @Test
    void zeroDurationJumpsToTheEnd() {
        Timing t = timing(0, 0, 1, Direction.NORMAL, FillMode.FORWARDS);
        assertEquals(Timing.Phase.AFTER, t.sample(0, false).phase());
        assertEquals(1, t.sample(0, false).progress());
        Timing infiniteAlternate = timing(0, 0, Double.POSITIVE_INFINITY, Direction.ALTERNATE, FillMode.BOTH);
        assertEquals(1, infiniteAlternate.sample(0, false).progress());
        assertEquals(0, infiniteAlternate.activeDuration());
    }

    @Test
    void negativeDelayStartsPartWay() {
        Timing t = timing(-50, 200, 1, Direction.NORMAL, FillMode.NONE);
        assertEquals(0.25, t.sample(0, false).progress(), EPS);
        assertEquals(50, t.intervalStart());
        assertEquals(200, t.intervalEnd());
        assertEquals(150, t.endTime());
    }

    @Test
    void effectEasingAppliesPerIteration() {
        Timing t = new Timing(0, 100, 2, Direction.NORMAL, FillMode.NONE, TimingFunction.EASE);
        assertEquals(0.8024, t.sample(150, false).progress(), EPS);
    }
}
