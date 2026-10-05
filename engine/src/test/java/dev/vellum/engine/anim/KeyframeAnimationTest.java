package dev.vellum.engine.anim;

import dev.vellum.engine.style.AnimationSpec;
import dev.vellum.engine.style.AnimationSpec.Direction;
import dev.vellum.engine.style.AnimationSpec.FillMode;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Length;
import dev.vellum.engine.style.Prop;
import dev.vellum.engine.style.TimingFunction;
import dev.vellum.engine.style.TimingFunction.Steps;
import dev.vellum.engine.style.TransitionSpec;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.vellum.engine.anim.AnimFixture.keyframe;
import static dev.vellum.engine.anim.AnimFixture.style;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyframeAnimationTest {
    private static final double EPS = 1e-4;
    private final AnimFixture f = new AnimFixture();

    @BeforeEach
    void keyframes() {
        f.keyframes.put("fade", List.of(keyframe(0, null, Prop.OPACITY, 0f), keyframe(1, null, Prop.OPACITY, 1f)));
        f.keyframes.put("grow", List.of(keyframe(0, null, Prop.WIDTH, Length.ZERO),
                keyframe(1, null, Prop.WIDTH, Length.px(100))));
    }

    private static AnimationSpec spec(String name, float duration, float delay, float iterations,
                                      Direction direction, FillMode fill) {
        return new AnimationSpec(name, duration, delay, TimingFunction.LINEAR, iterations, direction, fill, false);
    }

    private static AnimationSpec spec(String name, float duration) {
        return spec(name, duration, 0, 1, Direction.NORMAL, FillMode.NONE);
    }

    /** A style with opacity 0.5 running {@code specs}. */
    private static ComputedStyle animated(AnimationSpec... specs) {
        return style(Prop.OPACITY, 0.5f, Prop.ANIMATION, List.of(specs));
    }

    @Test
    void playsKeyframesThenRevertsWithoutFill() {
        f.restyle(0, animated(spec("fade", 100)));
        assertEquals(0, f.opacity()); // the first frame applies at once
        f.tick(0);
        assertEquals(List.of("animationstart:fade@0.0"), f.takeEvents());
        f.tick(50);
        assertEquals(0.5, f.opacity(), EPS);
        assertTrue(f.engine.isAnimating());
        f.tick(100);
        assertEquals(List.of("animationend:fade@0.1"), f.takeEvents());
        assertSame(f.el.baseStyle, f.el.style);
        assertFalse(f.engine.isAnimating());
    }

    @Test
    void missingEndpointsUseTheBaseValue() {
        f.keyframes.put("dip", List.of(keyframe(0.5f, null, Prop.OPACITY, 0f)));
        f.restyle(0, style(Prop.OPACITY, 1f, Prop.ANIMATION, List.of(spec("dip", 100))));
        f.tick(0);
        f.tick(25);
        assertEquals(0.5, f.opacity(), EPS);
        f.tick(50);
        assertEquals(0, f.opacity(), EPS);
        f.tick(75);
        assertEquals(0.5, f.opacity(), EPS);
    }

    @Test
    void propertiesOnlyAnimateBetweenTheKeyframesThatSetThem() {
        f.keyframes.put("mixed", List.of(
                keyframe(0, null, Prop.OPACITY, 0f),
                keyframe(0.5f, null, Prop.WIDTH, Length.px(100)),
                keyframe(1, null, Prop.OPACITY, 1f)));
        f.restyle(0, style(Prop.WIDTH, Length.ZERO, Prop.ANIMATION, List.of(spec("mixed", 100))));
        f.tick(0);
        f.tick(25);
        assertEquals(0.25, f.opacity(), EPS);
        assertEquals(Length.px(50), f.el.style.width);
        f.tick(75);
        assertEquals(Length.px(50), f.el.style.width); // back towards the base width
    }

    @Test
    void iterationsFireIterationEvents() {
        f.restyle(0, animated(spec("fade", 100, 0, 2, Direction.NORMAL, FillMode.NONE)));
        f.tick(0);
        f.tick(150);
        assertEquals(0.5, f.opacity(), EPS);
        f.tick(200);
        assertEquals(List.of("animationstart:fade@0.0", "animationiteration:fade@0.1", "animationend:fade@0.2"),
                f.takeEvents());
    }

    @Test
    void directions() {
        f.restyle(0, animated(spec("fade", 100, 0, 2, Direction.ALTERNATE, FillMode.NONE)));
        f.tick(0);
        f.tick(25);
        assertEquals(0.25, f.opacity(), EPS);
        f.tick(125);
        assertEquals(0.75, f.opacity(), EPS);

        AnimFixture g = new AnimFixture();
        g.keyframes.putAll(f.keyframes);
        g.restyle(0, animated(spec("fade", 100, 0, 1, Direction.REVERSE, FillMode.NONE)));
        g.tick(0);
        g.tick(25);
        assertEquals(0.75, g.opacity(), EPS);
    }

    @Test
    void fillForwardsKeepsTheLastFrame() {
        f.restyle(0, animated(spec("fade", 100, 0, 1.5f, Direction.NORMAL, FillMode.FORWARDS)));
        f.tick(0);
        f.tick(1000);
        assertEquals(0.5, f.opacity(), EPS); // ended half-way through the second iteration
        assertFalse(f.engine.isAnimating());

        AnimFixture g = new AnimFixture();
        g.keyframes.putAll(f.keyframes);
        g.restyle(0, animated(spec("fade", 100, 0, 1, Direction.NORMAL, FillMode.FORWARDS)));
        g.tick(0);
        g.tick(100);
        assertEquals(1, g.opacity());
    }

    @Test
    void fillBackwardsAppliesDuringTheDelay() {
        f.restyle(0, animated(spec("fade", 100, 50, 1, Direction.NORMAL, FillMode.BACKWARDS)));
        f.tick(0);
        assertEquals(0, f.opacity());
        assertEquals(List.of(), f.takeEvents());
        f.tick(50);
        assertEquals(List.of("animationstart:fade@0.0"), f.takeEvents());

        AnimFixture g = new AnimFixture();
        g.keyframes.putAll(f.keyframes);
        g.restyle(0, animated(spec("fade", 100, 50, 1, Direction.NORMAL, FillMode.NONE)));
        g.tick(25);
        assertEquals(0.5, g.opacity()); // the base value
    }

    @Test
    void negativeDelayStartsPartWay() {
        f.restyle(0, animated(spec("fade", 100, -25, 1, Direction.NORMAL, FillMode.NONE)));
        f.tick(0);
        assertEquals(0.25, f.opacity(), EPS);
        assertEquals(List.of("animationstart:fade@0.025"), f.takeEvents());
        f.tick(75);
        assertEquals(List.of("animationend:fade@0.1"), f.takeEvents());
    }

    @Test
    void keyframeTimingFunctionsEaseTheirSegment() {
        f.keyframes.put("stepped", List.of(keyframe(0, new Steps(2, Steps.Jump.END), Prop.OPACITY, 0f),
                keyframe(1, null, Prop.OPACITY, 1f)));
        f.restyle(0, animated(spec("stepped", 100)));
        f.tick(0);
        f.tick(30);
        assertEquals(0, f.opacity());
        f.tick(60);
        assertEquals(0.5, f.opacity());

        // Keyframes without their own timing function use the animation's.
        AnimFixture g = new AnimFixture();
        g.keyframes.putAll(f.keyframes);
        g.restyle(0, animated(new AnimationSpec("fade", 100, 0, TimingFunction.EASE, 1, Direction.NORMAL,
                FillMode.NONE, false)));
        g.tick(0);
        g.tick(50);
        assertEquals(0.8024, g.opacity(), 1e-3);
    }

    @Test
    void pausedAnimationsHoldTheirTime() {
        AnimationSpec paused = new AnimationSpec("fade", 100, 0, TimingFunction.LINEAR, 1, Direction.NORMAL,
                FillMode.NONE, true);
        f.restyle(0, animated(paused));
        f.tick(0);
        f.tick(50);
        assertEquals(0, f.opacity());
        assertFalse(f.engine.isAnimating());
        f.restyle(50, animated(spec("fade", 100)));
        f.tick(50);
        f.tick(100);
        assertEquals(0.5, f.opacity(), EPS);
        assertEquals(List.of("animationstart:fade@0.0"), f.takeEvents());
    }

    @Test
    void zeroDurationEndsImmediately() {
        f.restyle(0, animated(spec("fade", 0, 0, 1, Direction.NORMAL, FillMode.FORWARDS)));
        f.tick(0);
        assertEquals(1, f.opacity());
        assertEquals(List.of("animationstart:fade@0.0", "animationend:fade@0.0"), f.takeEvents());
    }

    @Test
    void infiniteAnimationsKeepRunning() {
        f.restyle(0, animated(spec("fade", 100, 0, Float.POSITIVE_INFINITY, Direction.NORMAL, FillMode.NONE)));
        f.tick(0);
        f.tick(250);
        assertEquals(0.5, f.opacity(), EPS);
        assertTrue(f.engine.isAnimating());
        assertEquals(List.of("animationstart:fade@0.0", "animationiteration:fade@0.2"), f.takeEvents());
    }

    @Test
    void extendingAFinishedAnimationRunsItOn() {
        f.restyle(0, animated(spec("fade", 100)));
        f.tick(0);
        f.tick(150);
        assertSame(f.el.baseStyle, f.el.style);
        f.restyle(150, animated(spec("fade", 100, 0, Float.POSITIVE_INFINITY, Direction.NORMAL, FillMode.NONE)));
        f.tick(150);
        f.tick(175);
        assertEquals(0.25, f.opacity(), EPS); // on from where it stopped: 25ms into the second iteration
        assertTrue(f.engine.isAnimating());
    }

    @Test
    void unrelatedRestylesKeepAnimationsRunningAndReresolveKeyframes() {
        f.restyle(0, animated(spec("fade", 100)));
        f.tick(0);
        f.tick(50);
        ComputedStyle recoloured = animated(spec("fade", 100));
        recoloured.color = 0xFFFF0000;
        f.restyle(50, recoloured);
        f.tick(75);
        assertEquals(0.75, f.opacity(), EPS);
        assertEquals(List.of("fade", "fade"), f.resolved); // once per base style

        f.engine.styleChanged(f.el, recoloured, recoloured); // same base: cached
        assertEquals(2, f.resolved.size());
    }

    @Test
    void changingTheNameListStartsAndCancels() {
        f.restyle(0, style(Prop.ANIMATION, List.of(spec("fade", 100), spec("grow", 100))));
        f.tick(0);
        f.tick(50);
        f.takeEvents();
        f.restyle(50, style(Prop.ANIMATION, List.of(spec("grow", 100)))); // fade removed; grow keeps going
        f.tick(75);
        assertEquals(1, f.opacity()); // the base again
        assertEquals(Length.px(75), f.el.style.width);
        assertEquals(List.of("animationcancel:fade@0.05"), f.takeEvents());

        f.restyle(75, style(Prop.ANIMATION, List.of(spec("grow", 100), spec("fade", 100)))); // fade restarts
        f.tick(75);
        assertEquals(0, f.opacity());
        assertEquals(List.of("animationstart:fade@0.0"), f.takeEvents());
    }

    @Test
    void unknownKeyframesDoNotRun() {
        f.restyle(0, animated(spec("missing", 100)));
        f.tick(0);
        assertNull(f.el.animationState);
        assertSame(f.el.baseStyle, f.el.style);
        assertEquals(List.of(), f.events);
    }

    @Test
    void animationsOverrideTransitions() {
        List<TransitionSpec> t = List.of(new TransitionSpec("width", 100, 0, TimingFunction.LINEAR));
        f.restyle(0, style(Prop.WIDTH, Length.ZERO, Prop.TRANSITION, t));
        f.restyle(0, style(Prop.WIDTH, Length.px(200), Prop.TRANSITION, t,
                Prop.ANIMATION, List.of(spec("grow", 100))));
        f.tick(0);
        f.tick(50);
        assertEquals(Length.px(50), f.el.style.width); // grow's 50px, not the transition's 100px
        f.tick(100);
        assertSame(f.el.baseStyle, f.el.style);
    }

    @Test
    void reducedMotionJumpsToTheEnd() {
        AnimFixture g = new AnimFixture(new TestHost() {
            @Override
            public boolean prefersReducedMotion() { return true; }
        });
        g.keyframes.putAll(f.keyframes);
        g.restyle(0, animated(spec("fade", 1000, 500, 1, Direction.NORMAL, FillMode.FORWARDS)));
        g.tick(0);
        assertEquals(1, g.opacity());
        assertEquals(List.of("animationstart:fade@0.0", "animationend:fade@0.0"), g.takeEvents());
        assertFalse(g.engine.isAnimating());
    }

    @Test
    void layoutInvalidatesOnlyForLayoutProperties() {
        f.restyle(0, animated(spec("fade", 100)));
        f.tick(0);
        f.clearLayoutDirty();
        f.tick(50);
        assertFalse(f.doc.needsLayout());
        f.restyle(50, style(Prop.ANIMATION, List.of(spec("grow", 100))));
        f.tick(50);
        f.clearLayoutDirty();
        f.tick(60);
        assertTrue(f.doc.needsLayout());
    }
}
