package dev.vellum.engine.anim;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.style.Length;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CSS transitions, started by style changes between frames and run by the frames that follow. */
class TransitionTest {
    private static final double EPS = 1e-4;
    static final String[] EVENTS = {"transitionrun", "transitionstart", "transitionend", "transitioncancel",
            "animationstart", "animationiteration", "animationend", "animationcancel"};
    private static final String FADE = "transition: opacity 100ms linear";

    private final Page page = page(new TestHost());
    private final Element el = page.byId("el");

    /** A page holding {@code #el}, styled at t = 0 with {@code css}, whose timing events are logged. */
    static Page page(TestHost host) {
        Page page = host.load("<div id=el></div>");
        page.listen(page.byId("el"), EVENTS);
        return page;
    }

    /** Sets {@code #el}'s style and runs the frame at {@code ms}, which restyles it then advances its animations. */
    static void restyle(Page page, double ms, String css) {
        page.byId("el").setAttribute("style", css);
        page.frame(ms);
    }

    private void restyle(double ms, String css) {
        restyle(page, ms, css);
    }

    private float opacity() {
        return el.style.opacity;
    }

    private AnimationEngine engine() {
        return page.doc.animations();
    }

    /** Styles the element with opacity 1, then changes it to 0 at t = 0 with the given transition. */
    private void fadeOut(String transition) {
        restyle(0, "opacity: 1; " + transition);
        restyle(0, "opacity: 0; " + transition);
    }

    @Test
    void elementsWithoutAnimationsKeepTheirBaseStyle() {
        restyle(0, "opacity: 0.5");
        restyle(16, "opacity: 1");
        assertSame(el.baseStyle, el.style);
        assertNull(el.animationState);
        assertFalse(engine().isAnimating());
    }

    @Test
    void noTransitionOnTheFirstStyle() {
        Page fresh = new TestHost().load("<div id=el style='opacity: 0; " + FADE + "'></div>");
        Element el = fresh.byId("el");
        assertSame(el.baseStyle, el.style);
        assertNull(el.animationState);
    }

    @Test
    void runsFromTheOldValueToTheNewOne() {
        fadeOut(FADE);
        assertEquals(1, opacity()); // styled at the start value straight away
        assertNotSame(el.baseStyle, el.style);
        assertEquals(List.of("transitionrun:el=opacity@0.0", "transitionstart:el=opacity@0.0"), page.takeLog());
        page.frame(50);
        assertEquals(0.5, opacity(), EPS);
        assertTrue(engine().isAnimating());
        page.frame(100);
        assertEquals(List.of("transitionend:el=opacity@0.1"), page.takeLog());
        assertSame(el.baseStyle, el.style);
        assertNull(el.animationState);
        assertFalse(engine().isAnimating());
    }

    @Test
    void timingFunctionShapesProgress() {
        fadeOut("transition: opacity 100ms ease");
        page.frame(50);
        assertEquals(1 - 0.8024, opacity(), 1e-3);
    }

    @Test
    void delayHoldsTheStartValue() {
        fadeOut("transition: opacity 100ms linear 50ms");
        assertEquals(List.of("transitionrun:el=opacity@0.0"), page.takeLog());
        page.frame(25);
        assertEquals(1, opacity());
        assertTrue(engine().isAnimating());
        page.frame(50);
        assertEquals(List.of("transitionstart:el=opacity@0.0"), page.takeLog());
        page.frame(100);
        assertEquals(0.5, opacity(), EPS);
        page.frame(150);
        assertEquals(List.of("transitionend:el=opacity@0.1"), page.takeLog());
    }

    @Test
    void negativeDelayStartsPartWay() {
        fadeOut("transition: opacity 100ms linear -50ms");
        assertEquals(0.5, opacity(), EPS);
        assertEquals(List.of("transitionrun:el=opacity@0.05", "transitionstart:el=opacity@0.05"), page.takeLog());
        page.frame(50);
        assertEquals(List.of("transitionend:el=opacity@0.1"), page.takeLog());
    }

    @Test
    void retargetingStartsFromTheCurrentValue() {
        String t = "; transition: width 100ms linear";
        restyle(0, "width: 0" + t);
        restyle(0, "width: 100px" + t);
        page.frame(50);
        assertEquals(Length.px(50), el.style.width);
        page.takeLog();

        restyle(50, "width: 200px" + t);
        assertEquals(Length.px(50), el.style.width);
        assertEquals(List.of("transitioncancel:el=width@0.05", "transitionrun:el=width@0.0", "transitionstart:el=width@0.0"),
                page.takeLog());
        page.frame(100);
        assertEquals(Length.px(125), el.style.width); // the full duration again, from 50px
        page.frame(150);
        assertEquals(Length.px(200), el.style.width);
        assertNull(el.animationState);
    }

    @Test
    void reversingIsShortened() {
        fadeOut(FADE);
        page.frame(25);
        assertEquals(0.75, opacity(), EPS);

        restyle(25, "opacity: 1; " + FADE); // back to where it came from: a quarter of the way, a quarter of the time
        page.frame(37.5);
        assertEquals(0.875, opacity(), EPS);
        page.frame(50);
        assertEquals(1, opacity());
        assertNull(el.animationState);
    }

    @Test
    void reversingTheReversalUsesTheShortenedState() {
        fadeOut(FADE);
        page.frame(50); // 0.5
        restyle(50, "opacity: 1; " + FADE); // reverse: factor 0.5, 50ms
        page.frame(75); // 0.75
        assertEquals(0.75, opacity(), EPS);
        restyle(75, "opacity: 0; " + FADE); // reverse again: factor |0.5 * 0.5 + 1 - 0.5| = 0.75, 75ms
        page.frame(112.5);
        assertEquals(0.375, opacity(), EPS);
        page.frame(150);
        assertNull(el.animationState);
    }

    @Test
    void unrelatedChangesDoNotDisturbARunningTransition() {
        fadeOut(FADE);
        page.frame(50);
        restyle(50, "opacity: 0; color: red; " + FADE);
        page.frame(75);
        assertEquals(0.25, opacity(), EPS);
        assertEquals(0xFFFF0000, el.style.color);
        assertEquals(List.of("transitionrun:el=opacity@0.0", "transitionstart:el=opacity@0.0"), page.takeLog());
    }

    @Test
    void removingThePropertyFromTheListCancels() {
        fadeOut(FADE);
        page.frame(50);
        page.takeLog();
        restyle(50, "opacity: 0");
        assertSame(el.baseStyle, el.style);
        assertNull(el.animationState);
        assertEquals(List.of("transitioncancel:el=opacity@0.05"), page.takeLog());
    }

    @Test
    void zeroDurationCancels() {
        fadeOut(FADE);
        page.frame(50);
        restyle(50, "opacity: 0; transition: opacity 0s");
        assertEquals(0, opacity());
        assertNull(el.animationState);

        restyle(60, "opacity: 1; transition: opacity 0s");
        assertSame(el.baseStyle, el.style);
    }

    @Test
    void displayNoneCancelsAndNoTransitionStartsFromIt() {
        fadeOut(FADE);
        restyle(10, "display: none; opacity: 1; " + FADE);
        assertSame(el.baseStyle, el.style);

        restyle(20, "opacity: 0; " + FADE); // shown again with another value: no transition from display: none
        assertSame(el.baseStyle, el.style);
        assertNull(el.animationState);
    }

    @Test
    void valuesThatCannotInterpolateDoNotTransition() {
        restyle(0, "width: auto; transition: width 100ms linear");
        restyle(0, "width: 100px; transition: width 100ms linear");
        assertSame(el.baseStyle, el.style);
        assertNull(el.animationState);
    }

    @Test
    void allAndShorthandsExpand() {
        restyle(0, "opacity: 1; margin-top: 0; transition: all 100ms linear");
        restyle(0, "opacity: 0; margin-top: 10px; transition: all 100ms linear");
        page.frame(50);
        assertEquals(0.5, opacity(), EPS);
        assertEquals(Length.px(5), el.style.marginTop);

        restyle(100, "margin-left: 0; transition: margin 100ms linear");
        restyle(100, "margin-left: 10px; transition: margin 100ms linear");
        page.frame(150);
        assertEquals(Length.px(5), el.style.marginLeft);
    }

    @Test
    void theLastEntryNamingAPropertyWins() {
        String t = "; transition: all 100ms linear, opacity 0s linear";
        restyle(0, "opacity: 1; width: 0" + t);
        restyle(0, "opacity: 0; width: 10px" + t);
        page.frame(50);
        assertEquals(0, opacity());
        assertEquals(Length.px(5), el.style.width);
    }

    @Test
    void onlyLayoutPropertiesInvalidateLayout() {
        fadeOut(FADE);
        assertFalse(page.frameLaysOut(50), "opacity only repaints");

        String t = "; transition: width 100ms linear";
        restyle(200, "width: 0" + t);
        restyle(200, "width: 100px" + t);
        assertTrue(page.frameLaysOut(250));
        assertFalse(page.frameLaysOut(250), "nothing moved");
    }

    @Test
    void reducedMotionSkipsTransitions() {
        Page page = page(new TestHost() {
            @Override
            public boolean prefersReducedMotion() { return true; }
        });
        restyle(page, 0, "opacity: 1; " + FADE);
        restyle(page, 0, "opacity: 0; " + FADE);
        Element el = page.byId("el");
        assertSame(el.baseStyle, el.style);
        assertEquals(List.of(), page.log);
    }

    @Test
    void leavingTheDocumentDropsTransitions() {
        fadeOut(FADE);
        page.takeLog();
        el.remove();
        page.frame(50);
        assertNull(el.animationState);
        assertFalse(engine().isAnimating());
        assertEquals(List.of("transitioncancel:el=opacity@0.05"), page.takeLog());
    }
}
