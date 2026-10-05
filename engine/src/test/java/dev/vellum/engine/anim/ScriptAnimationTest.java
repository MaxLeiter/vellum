package dev.vellum.engine.anim;

import dev.vellum.engine.anim.Animation.PlayState;
import dev.vellum.engine.css.ResolvedKeyframe;
import dev.vellum.engine.style.AnimationSpec;
import dev.vellum.engine.style.AnimationSpec.Direction;
import dev.vellum.engine.style.AnimationSpec.FillMode;
import dev.vellum.engine.style.Prop;
import dev.vellum.engine.style.TimingFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.vellum.engine.anim.AnimFixture.keyframe;
import static dev.vellum.engine.anim.AnimFixture.style;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptAnimationTest {
    private static final double EPS = 1e-4;
    private static final List<ResolvedKeyframe> FADE_IN =
            List.of(keyframe(0, null, Prop.OPACITY, 0f), keyframe(1, null, Prop.OPACITY, 1f));

    private final AnimFixture f = new AnimFixture();
    private final List<String> log = new ArrayList<>();

    @BeforeEach
    void base() {
        f.restyle(0, style(Prop.OPACITY, 0.5f));
    }

    private Animation fadeIn(AnimationOptions options) {
        Animation a = f.engine.animate(f.el, FADE_IN, options);
        a.onFinish(() -> log.add("finish"));
        a.onCancel(() -> log.add("cancel"));
        return a;
    }

    private static AnimationOptions options(float duration, float delay, float iterations, FillMode fill) {
        return new AnimationOptions(duration, delay, TimingFunction.LINEAR, iterations, Direction.NORMAL, fill);
    }

    @Test
    void playsFromTheNextTickAndRemovesItsEffectWhenFinished() {
        Animation a = fadeIn(AnimationOptions.of(100));
        assertEquals(PlayState.RUNNING, a.playState());
        assertEquals(0, a.currentTime());
        assertTrue(f.engine.isAnimating());
        f.tick(10); // starts now, not when animate() was called
        assertEquals(0, f.opacity());
        f.tick(60);
        assertEquals(0.5, f.opacity(), EPS);
        assertEquals(50, a.currentTime(), EPS);
        f.tick(110);
        assertEquals(PlayState.FINISHED, a.playState());
        assertEquals(List.of("finish"), log);
        assertSame(f.el.baseStyle, f.el.style);
        assertNull(f.el.animationState);
        assertFalse(f.engine.isAnimating());
    }

    @Test
    void fillForwardsKeepsTheEffect() {
        fadeIn(options(100, 0, 1, FillMode.FORWARDS));
        f.tick(0);
        f.tick(500);
        assertEquals(1, f.opacity());
        assertFalse(f.engine.isAnimating());
    }

    @Test
    void delayAndIterations() {
        Animation a = fadeIn(new AnimationOptions(100, 50, TimingFunction.LINEAR, 2, Direction.ALTERNATE,
                FillMode.BOTH));
        f.tick(0);
        assertEquals(0, f.opacity()); // filling backwards during the delay
        f.tick(175);
        assertEquals(0.75, f.opacity(), EPS); // second iteration, alternating back
        f.tick(250);
        assertEquals(0, f.opacity());
        assertEquals(PlayState.FINISHED, a.playState());
    }

    @Test
    void pauseAndPlay() {
        Animation a = fadeIn(AnimationOptions.of(100));
        f.tick(0);
        f.tick(40);
        a.pause();
        assertEquals(PlayState.PAUSED, a.playState());
        f.tick(80);
        assertEquals(0.4, f.opacity(), EPS);
        assertFalse(f.engine.isAnimating());
        a.play();
        f.tick(80);
        f.tick(100);
        assertEquals(0.6, f.opacity(), EPS);
    }

    @Test
    void seekingAndPlaybackRate() {
        Animation a = fadeIn(AnimationOptions.of(100));
        f.tick(0);
        a.setCurrentTime(75);
        f.tick(0);
        assertEquals(0.75, f.opacity(), EPS);

        a.setCurrentTime(0);
        a.setPlaybackRate(2);
        assertEquals(2, a.playbackRate());
        f.tick(25);
        assertEquals(0.5, f.opacity(), EPS);
    }

    @Test
    void reversePlaysBackToTheStart() {
        Animation a = fadeIn(AnimationOptions.of(100));
        f.tick(0);
        f.tick(50);
        a.reverse();
        assertEquals(-1, a.playbackRate());
        f.tick(50);
        f.tick(70);
        assertEquals(0.3, f.opacity(), EPS);
        f.tick(100);
        assertEquals(PlayState.FINISHED, a.playState());
        assertEquals(0, a.currentTime());
        assertSame(f.el.baseStyle, f.el.style); // reversed past the start without backwards fill: no effect
    }

    @Test
    void finishJumpsToTheEnd() {
        Animation a = fadeIn(options(100, 0, 1, FillMode.FORWARDS));
        f.tick(0);
        a.finish();
        assertEquals(PlayState.FINISHED, a.playState());
        assertEquals(List.of(), log); // callbacks run after the next tick
        f.tick(10);
        assertEquals(1, f.opacity());
        assertEquals(List.of("finish"), log);

        a.onFinish(() -> log.add("late"));
        assertEquals(List.of("finish", "late"), log); // already finished: runs at once
    }

    @Test
    void cancelRemovesTheEffect() {
        Animation a = fadeIn(options(100, 0, 1, FillMode.FORWARDS));
        f.tick(0);
        f.tick(50);
        a.cancel();
        assertEquals(PlayState.IDLE, a.playState());
        assertTrue(Double.isNaN(a.currentTime()));
        f.tick(60);
        assertEquals(List.of("cancel"), log);
        assertSame(f.el.baseStyle, f.el.style);
        assertNull(f.el.animationState);

        a.play(); // replays from the start
        f.tick(70);
        assertEquals(0, f.opacity());
    }

    @Test
    void missingKeyframesAnimateFromTheUnderlyingValue() {
        f.engine.animate(f.el, List.of(keyframe(1, null, Prop.OPACITY, 1f)), AnimationOptions.of(100));
        f.tick(0);
        f.tick(50);
        assertEquals(0.75, f.opacity(), EPS);
    }

    @Test
    void easingAppliesToTheWholeIteration() {
        f.engine.animate(f.el, FADE_IN, new AnimationOptions(100, 0, TimingFunction.EASE, 1, Direction.NORMAL,
                FillMode.NONE));
        f.tick(0);
        f.tick(50);
        assertEquals(0.8024, f.opacity(), 1e-3);
    }

    @Test
    void scriptedAnimationsOverrideCssAnimations() {
        f.keyframes.put("fade", FADE_IN);
        f.restyle(0, style(Prop.OPACITY, 0.5f, Prop.ANIMATION, List.of(new AnimationSpec("fade", 100, 0,
                TimingFunction.LINEAR, 1, Direction.NORMAL, FillMode.NONE, false))));
        f.engine.animate(f.el, List.of(keyframe(0, null, Prop.OPACITY, 0.25f), keyframe(1, null, Prop.OPACITY, 0.25f)),
                AnimationOptions.of(100));
        f.tick(0);
        f.tick(50);
        assertEquals(0.25, f.opacity(), EPS);
    }

    @Test
    void laterFillingAnimationsReplaceEarlierOnes() {
        fadeIn(options(50, 0, 1, FillMode.FORWARDS));
        f.engine.animate(f.el, List.of(keyframe(1, null, Prop.OPACITY, 0.2f)), options(100, 0, 1, FillMode.FORWARDS));
        f.tick(0);
        f.tick(50);
        f.tick(100);
        assertEquals(0.2, f.opacity(), EPS);
        f.tick(200);
        assertEquals(0.2, f.opacity(), EPS);
        assertFalse(f.engine.isAnimating());
    }
}
