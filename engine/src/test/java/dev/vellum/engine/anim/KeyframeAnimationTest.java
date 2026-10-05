package dev.vellum.engine.anim;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.style.Length;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CSS animations from {@code @keyframes} in the page's stylesheet, started by styles and run by frames. */
class KeyframeAnimationTest {
    private static final double EPS = 1e-4;
    private static final String KEYFRAMES = """
            <style>
              @keyframes fade { from { opacity: 0 } to { opacity: 1 } }
              @keyframes grow { from { width: 0 } to { width: 100px } }
              @keyframes grow-em { to { width: 10em } }
              @keyframes dip { 50% { opacity: 0 } }
              @keyframes mixed { 0% { opacity: 0 } 50% { width: 100px } 100% { opacity: 1 } }
              @keyframes stepped { 0% { opacity: 0; animation-timing-function: steps(2, end) } 100% { opacity: 1 } }
            </style>
            <div id=el></div>""";

    private final Page page = page(new TestHost());
    private final Element el = page.byId("el");

    private static Page page(TestHost host) {
        Page page = host.load(KEYFRAMES);
        page.listen(page.byId("el"), TransitionTest.EVENTS);
        return page;
    }

    /** Restyles {@code #el} to {@code css} in the frame at {@code ms}. */
    private void restyle(double ms, String css) {
        TransitionTest.restyle(page, ms, css);
    }

    /** {@code #el} with opacity 0.5 running {@code animation} from t = 0. */
    private void animate(String animation) {
        restyle(0, "opacity: 0.5; animation: " + animation);
    }

    private float opacity() {
        return el.style.opacity;
    }

    private AnimationEngine engine() {
        return page.doc.animations();
    }

    @Test
    void playsKeyframesThenRevertsWithoutFill() {
        animate("fade 100ms linear");
        assertEquals(0, opacity()); // the first frame applies at once
        assertEquals(List.of("animationstart:el=fade@0.0"), page.takeLog());
        page.frame(50);
        assertEquals(0.5, opacity(), EPS);
        assertTrue(engine().isAnimating());
        page.frame(100);
        assertEquals(List.of("animationend:el=fade@0.1"), page.takeLog());
        assertSame(el.baseStyle, el.style);
        assertFalse(engine().isAnimating());
    }

    @Test
    void missingEndpointsUseTheBaseValue() {
        restyle(0, "opacity: 1; animation: dip 100ms linear");
        page.frame(25);
        assertEquals(0.5, opacity(), EPS);
        page.frame(50);
        assertEquals(0, opacity(), EPS);
        page.frame(75);
        assertEquals(0.5, opacity(), EPS);
    }

    @Test
    void propertiesOnlyAnimateBetweenTheKeyframesThatSetThem() {
        restyle(0, "width: 0; animation: mixed 100ms linear");
        page.frame(25);
        assertEquals(0.25, opacity(), EPS);
        assertEquals(Length.px(50), el.style.width);
        page.frame(75);
        assertEquals(Length.px(50), el.style.width); // back towards the base width
    }

    @Test
    void iterationsFireIterationEvents() {
        animate("fade 100ms linear 2");
        page.frame(150);
        assertEquals(0.5, opacity(), EPS);
        page.frame(200);
        assertEquals(List.of("animationstart:el=fade@0.0", "animationiteration:el=fade@0.1", "animationend:el=fade@0.2"),
                page.takeLog());
    }

    @Test
    void directions() {
        animate("fade 100ms linear 2 alternate");
        page.frame(25);
        assertEquals(0.25, opacity(), EPS);
        page.frame(125);
        assertEquals(0.75, opacity(), EPS);

        restyle(200, "opacity: 0.5");
        restyle(200, "opacity: 0.5; animation: fade 100ms linear reverse");
        page.frame(225);
        assertEquals(0.75, opacity(), EPS);
    }

    @Test
    void fillForwardsKeepsTheLastFrame() {
        animate("fade 100ms linear 1.5 forwards");
        page.frame(1000);
        assertEquals(0.5, opacity(), EPS); // ended half-way through the second iteration
        assertFalse(engine().isAnimating());

        restyle(1000, "opacity: 0.5");
        restyle(1000, "opacity: 0.5; animation: fade 100ms linear forwards");
        page.frame(1100);
        assertEquals(1, opacity());
    }

    @Test
    void fillBackwardsAppliesDuringTheDelay() {
        animate("fade 100ms linear 50ms backwards");
        assertEquals(0, opacity());
        assertEquals(List.of(), page.log);
        page.frame(50);
        assertEquals(List.of("animationstart:el=fade@0.0"), page.takeLog());

        restyle(200, "opacity: 0.5");
        restyle(200, "opacity: 0.5; animation: fade 100ms linear 50ms");
        page.frame(225);
        assertEquals(0.5, opacity()); // the base value
    }

    @Test
    void negativeDelayStartsPartWay() {
        animate("fade 100ms linear -25ms");
        assertEquals(0.25, opacity(), EPS);
        assertEquals(List.of("animationstart:el=fade@0.025"), page.takeLog());
        page.frame(75);
        assertEquals(List.of("animationend:el=fade@0.1"), page.takeLog());
    }

    @Test
    void keyframeTimingFunctionsEaseTheirSegment() {
        animate("stepped 100ms linear");
        page.frame(30);
        assertEquals(0, opacity());
        page.frame(60);
        assertEquals(0.5, opacity());

        // Keyframes without their own timing function use the animation's.
        restyle(200, "opacity: 0.5");
        restyle(200, "opacity: 0.5; animation: fade 100ms ease");
        page.frame(250);
        assertEquals(0.8024, opacity(), 1e-3);
    }

    @Test
    void pausedAnimationsHoldTheirTime() {
        animate("fade 100ms linear paused");
        page.frame(50);
        assertEquals(0, opacity());
        assertFalse(engine().isAnimating());
        restyle(50, "opacity: 0.5; animation: fade 100ms linear");
        page.frame(100);
        assertEquals(0.5, opacity(), EPS);
        assertEquals(List.of("animationstart:el=fade@0.0"), page.takeLog());
    }

    @Test
    void zeroDurationEndsImmediately() {
        animate("fade 0s linear forwards");
        assertEquals(1, opacity());
        assertEquals(List.of("animationstart:el=fade@0.0", "animationend:el=fade@0.0"), page.takeLog());
    }

    @Test
    void infiniteAnimationsKeepRunning() {
        animate("fade 100ms linear infinite");
        page.frame(250);
        assertEquals(0.5, opacity(), EPS);
        assertTrue(engine().isAnimating());
        assertEquals(List.of("animationstart:el=fade@0.0", "animationiteration:el=fade@0.2"), page.takeLog());
    }

    @Test
    void extendingAFinishedAnimationRunsItOn() {
        animate("fade 100ms linear");
        page.frame(150);
        assertSame(el.baseStyle, el.style);
        restyle(150, "opacity: 0.5; animation: fade 100ms linear infinite");
        page.frame(175);
        assertEquals(0.25, opacity(), EPS); // on from where it stopped: 25ms into the second iteration
        assertTrue(engine().isAnimating());
    }

    @Test
    void unrelatedRestylesKeepAnimationsRunningAndReresolveKeyframes() {
        restyle(0, "width: 0; animation: grow-em 100ms linear");
        page.frame(50);
        assertEquals(Length.px(40), el.style.width); // half-way to 10em of 8px
        restyle(50, "width: 0; font-size: 16px; animation: grow-em 100ms linear");
        page.frame(75);
        assertEquals(Length.px(120), el.style.width); // still running, now towards 10em of 16px
        assertEquals(List.of("animationstart:el=grow-em@0.0"), page.takeLog());
    }

    @Test
    void changingTheNameListStartsAndCancels() {
        restyle(0, "animation: fade 100ms linear, grow 100ms linear");
        page.frame(50);
        page.takeLog();
        restyle(50, "animation: grow 100ms linear"); // fade removed; grow keeps going
        page.frame(75);
        assertEquals(1, opacity()); // the base again
        assertEquals(Length.px(75), el.style.width);
        assertEquals(List.of("animationcancel:el=fade@0.05"), page.takeLog());

        restyle(75, "animation: grow 100ms linear, fade 100ms linear"); // fade restarts
        assertEquals(0, opacity());
        assertEquals(List.of("animationstart:el=fade@0.0"), page.takeLog());
    }

    @Test
    void unknownKeyframesDoNotRun() {
        animate("missing 100ms linear");
        assertNull(el.animationState);
        assertSame(el.baseStyle, el.style);
        assertEquals(List.of(), page.log);
    }

    @Test
    void animationsOverrideTransitions() {
        String t = "transition: width 100ms linear; ";
        restyle(0, t + "width: 0");
        restyle(0, t + "width: 200px; animation: grow 100ms linear");
        page.frame(50);
        assertEquals(Length.px(50), el.style.width); // grow's 50px, not the transition's 100px
        page.frame(100);
        assertSame(el.baseStyle, el.style);
    }

    @Test
    void reducedMotionJumpsToTheEnd() {
        Page page = page(new TestHost() {
            @Override
            public boolean prefersReducedMotion() { return true; }
        });
        TransitionTest.restyle(page, 0, "opacity: 0.5; animation: fade 1000ms linear 500ms forwards");
        assertEquals(1, page.byId("el").style.opacity);
        assertEquals(List.of("animationstart:el=fade@0.0", "animationend:el=fade@0.0"), page.takeLog());
        assertFalse(page.doc.animations().isAnimating());
    }

    @Test
    void layoutInvalidatesOnlyForLayoutProperties() {
        animate("fade 100ms linear");
        assertFalse(page.frameLaysOut(50));
        restyle(50, "animation: grow 100ms linear");
        assertTrue(page.frameLaysOut(60));
    }
}
