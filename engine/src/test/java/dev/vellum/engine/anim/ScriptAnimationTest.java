package dev.vellum.engine.anim;

import dev.vellum.engine.anim.Animation.PlayState;
import dev.vellum.engine.css.ResolvedKeyframe;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.style.AnimationSpec.Direction;
import dev.vellum.engine.style.AnimationSpec.FillMode;
import dev.vellum.engine.style.TimingFunction;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code element.animate()} on the animation engine: Web Animations timing and playback control, with keyframes
 * computed by the style engine as the script binding computes them. {@code #el} has opacity 0.5.
 */
class ScriptAnimationTest {
    private static final double EPS = 1e-4;

    private final Page page = new TestHost().load("""
            <style>@keyframes fade { from { opacity: 0 } to { opacity: 1 } }</style>
            <div id=el style="opacity: 0.5"></div>""");
    private final Element el = page.byId("el");
    private final AnimationEngine engine = page.doc.animations();
    private final List<ResolvedKeyframe> fadeIn = List.of(keyframe(0, "opacity: 0"), keyframe(1, "opacity: 1"));
    private final List<String> log = new ArrayList<>();

    private ResolvedKeyframe keyframe(float offset, String declarations) {
        ResolvedKeyframe k = page.doc.styleEngine().computeDeclarations(el, declarations, el.baseStyle);
        return new ResolvedKeyframe(offset, null, k.style(), k.props());
    }

    private Animation fadeIn(Timing options) {
        Animation a = engine.animate(el, fadeIn, options);
        a.onFinish(() -> log.add("finish"));
        a.onCancel(() -> log.add("cancel"));
        return a;
    }

    private static Timing options(float duration, float delay, float iterations, FillMode fill) {
        return new Timing(delay, duration, iterations, Direction.NORMAL, fill, TimingFunction.LINEAR);
    }

    private float opacity() {
        return el.style.opacity;
    }

    @Test
    void playsFromTheNextTickAndRemovesItsEffectWhenFinished() {
        Animation a = fadeIn(Timing.of(100));
        assertEquals(PlayState.RUNNING, a.playState());
        assertEquals(0, a.currentTime());
        assertTrue(engine.isAnimating());
        page.frame(10); // starts now, not when animate() was called
        assertEquals(0, opacity());
        page.frame(60);
        assertEquals(0.5, opacity(), EPS);
        assertEquals(50, a.currentTime(), EPS);
        page.frame(110);
        assertEquals(PlayState.FINISHED, a.playState());
        assertEquals(List.of("finish"), log);
        assertSame(el.baseStyle, el.style);
        assertNull(el.animationState);
        assertFalse(engine.isAnimating());
    }

    @Test
    void fillForwardsKeepsTheEffect() {
        fadeIn(options(100, 0, 1, FillMode.FORWARDS));
        page.frame(0);
        page.frame(500);
        assertEquals(1, opacity());
        assertFalse(engine.isAnimating());
    }

    @Test
    void delayAndIterations() {
        Animation a = fadeIn(new Timing(50, 100, 2, Direction.ALTERNATE, FillMode.BOTH, TimingFunction.LINEAR));
        page.frame(0);
        assertEquals(0, opacity()); // filling backwards during the delay
        page.frame(175);
        assertEquals(0.75, opacity(), EPS); // second iteration, alternating back
        page.frame(250);
        assertEquals(0, opacity());
        assertEquals(PlayState.FINISHED, a.playState());
    }

    @Test
    void pauseAndPlay() {
        Animation a = fadeIn(Timing.of(100));
        page.frame(0);
        page.frame(40);
        a.pause();
        assertEquals(PlayState.PAUSED, a.playState());
        page.frame(80);
        assertEquals(0.4, opacity(), EPS);
        assertFalse(engine.isAnimating());
        a.play();
        page.frame(80);
        page.frame(100);
        assertEquals(0.6, opacity(), EPS);
    }

    @Test
    void seekingAndPlaybackRate() {
        Animation a = fadeIn(Timing.of(100));
        page.frame(0);
        a.setCurrentTime(75);
        page.frame(0);
        assertEquals(0.75, opacity(), EPS);

        a.setCurrentTime(0);
        a.setPlaybackRate(2);
        assertEquals(2, a.playbackRate());
        page.frame(25);
        assertEquals(0.5, opacity(), EPS);
    }

    @Test
    void reversePlaysBackToTheStart() {
        Animation a = fadeIn(Timing.of(100));
        page.frame(0);
        page.frame(50);
        a.reverse();
        assertEquals(-1, a.playbackRate());
        page.frame(50);
        page.frame(70);
        assertEquals(0.3, opacity(), EPS);
        page.frame(100);
        assertEquals(PlayState.FINISHED, a.playState());
        assertEquals(0, a.currentTime());
        assertSame(el.baseStyle, el.style); // reversed past the start without backwards fill: no effect
    }

    @Test
    void finishJumpsToTheEnd() {
        Animation a = fadeIn(options(100, 0, 1, FillMode.FORWARDS));
        page.frame(0);
        a.finish();
        assertEquals(PlayState.FINISHED, a.playState());
        assertEquals(List.of(), log); // callbacks run after the next tick
        page.frame(10);
        assertEquals(1, opacity());
        assertEquals(List.of("finish"), log);

        a.onFinish(() -> log.add("late"));
        assertEquals(List.of("finish", "late"), log); // already finished: runs at once
    }

    @Test
    void cancelRemovesTheEffect() {
        Animation a = fadeIn(options(100, 0, 1, FillMode.FORWARDS));
        page.frame(0);
        page.frame(50);
        a.cancel();
        assertEquals(PlayState.IDLE, a.playState());
        assertTrue(Double.isNaN(a.currentTime()));
        page.frame(60);
        assertEquals(List.of("cancel"), log);
        assertSame(el.baseStyle, el.style);
        assertNull(el.animationState);

        a.play(); // replays from the start
        page.frame(70);
        assertEquals(0, opacity());
    }

    @Test
    void missingKeyframesAnimateFromTheUnderlyingValue() {
        engine.animate(el, List.of(keyframe(1, "opacity: 1")), Timing.of(100));
        page.frame(0);
        page.frame(50);
        assertEquals(0.75, opacity(), EPS);
    }

    @Test
    void easingAppliesToTheWholeIteration() {
        engine.animate(el, fadeIn, new Timing(0, 100, 1, Direction.NORMAL, FillMode.NONE, TimingFunction.EASE));
        page.frame(0);
        page.frame(50);
        assertEquals(0.8024, opacity(), 1e-3);
    }

    @Test
    void scriptedAnimationsOverrideCssAnimations() {
        el.setAttribute("style", "opacity: 0.5; animation: fade 100ms linear");
        engine.animate(el, List.of(keyframe(0, "opacity: 0.25"), keyframe(1, "opacity: 0.25")),
                Timing.of(100));
        page.frame(0);
        page.frame(50);
        assertEquals(0.25, opacity(), EPS);
    }

    @Test
    void laterFillingAnimationsReplaceEarlierOnes() {
        fadeIn(options(50, 0, 1, FillMode.FORWARDS));
        engine.animate(el, List.of(keyframe(1, "opacity: 0.2")), options(100, 0, 1, FillMode.FORWARDS));
        page.frame(0);
        page.frame(50);
        page.frame(100);
        assertEquals(0.2, opacity(), EPS);
        page.frame(200);
        assertEquals(0.2, opacity(), EPS);
        assertFalse(engine.isAnimating());
    }
}
