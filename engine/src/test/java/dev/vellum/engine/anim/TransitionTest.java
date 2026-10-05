package dev.vellum.engine.anim;

import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Display;
import dev.vellum.engine.style.Length;
import dev.vellum.engine.style.Prop;
import dev.vellum.engine.style.TimingFunction;
import dev.vellum.engine.style.TransitionSpec;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.vellum.engine.anim.AnimFixture.style;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransitionTest {
    private static final double EPS = 1e-4;
    private final AnimFixture f = new AnimFixture();

    private static List<TransitionSpec> transition(String property, float durationMs, float delayMs) {
        return List.of(new TransitionSpec(property, durationMs, delayMs, TimingFunction.LINEAR));
    }

    private static ComputedStyle opacity(float opacity, List<TransitionSpec> transitions) {
        return style(Prop.OPACITY, opacity, Prop.TRANSITION, transitions);
    }

    private static ComputedStyle width(float px, List<TransitionSpec> transitions) {
        return style(Prop.WIDTH, Length.px(px), Prop.TRANSITION, transitions);
    }

    /** Styles the element with opacity 1, then changes it to 0 at t = 0 with the given transition. */
    private void fadeOut(List<TransitionSpec> transitions) {
        f.restyle(0, opacity(1, transitions));
        f.restyle(0, opacity(0, transitions));
    }

    @Test
    void elementsWithoutAnimationsKeepTheirBaseStyle() {
        ComputedStyle base = style(Prop.OPACITY, 0.5f);
        f.restyle(0, base);
        f.restyle(0, style(Prop.OPACITY, 1f));
        f.tick(16);
        assertSame(f.el.baseStyle, f.el.style);
        assertNull(f.el.animationState);
        assertFalse(f.engine.isAnimating());
    }

    @Test
    void noTransitionOnTheFirstStyle() {
        ComputedStyle base = opacity(0, transition("opacity", 100, 0));
        f.restyle(0, base);
        assertSame(base, f.el.style);
        assertNull(f.el.animationState);
    }

    @Test
    void runsFromTheOldValueToTheNewOne() {
        fadeOut(transition("opacity", 100, 0));
        assertEquals(1, f.opacity()); // styled at the start value straight away
        assertNotSame(f.el.baseStyle, f.el.style);
        f.tick(0);
        assertEquals(List.of("transitionrun:opacity@0.0", "transitionstart:opacity@0.0"), f.takeEvents());
        f.tick(50);
        assertEquals(0.5, f.opacity(), EPS);
        assertTrue(f.engine.isAnimating());
        f.tick(100);
        assertEquals(List.of("transitionend:opacity@0.1"), f.takeEvents());
        assertSame(f.el.baseStyle, f.el.style);
        assertNull(f.el.animationState);
        assertFalse(f.engine.isAnimating());
    }

    @Test
    void timingFunctionShapesProgress() {
        List<TransitionSpec> ease = List.of(new TransitionSpec("opacity", 100, 0, TimingFunction.EASE));
        fadeOut(ease);
        f.tick(0);
        f.tick(50);
        assertEquals(1 - 0.8024, f.opacity(), 1e-3);
    }

    @Test
    void delayHoldsTheStartValue() {
        fadeOut(transition("opacity", 100, 50));
        f.tick(0);
        assertEquals(List.of("transitionrun:opacity@0.0"), f.takeEvents());
        f.tick(25);
        assertEquals(1, f.opacity());
        assertTrue(f.engine.isAnimating());
        f.tick(50);
        assertEquals(List.of("transitionstart:opacity@0.0"), f.takeEvents());
        f.tick(100);
        assertEquals(0.5, f.opacity(), EPS);
        f.tick(150);
        assertEquals(List.of("transitionend:opacity@0.1"), f.takeEvents());
    }

    @Test
    void negativeDelayStartsPartWay() {
        fadeOut(transition("opacity", 100, -50));
        f.tick(0);
        assertEquals(0.5, f.opacity(), EPS);
        assertEquals(List.of("transitionrun:opacity@0.05", "transitionstart:opacity@0.05"), f.takeEvents());
        f.tick(50);
        assertEquals(List.of("transitionend:opacity@0.1"), f.takeEvents());
    }

    @Test
    void retargetingStartsFromTheCurrentValue() {
        List<TransitionSpec> t = transition("width", 100, 0);
        f.restyle(0, width(0, t));
        f.restyle(0, width(100, t));
        f.tick(0);
        f.tick(50);
        assertEquals(Length.px(50), f.el.style.width);
        f.takeEvents();

        f.restyle(50, width(200, t));
        assertEquals(Length.px(50), f.el.style.width);
        f.tick(50);
        assertEquals(List.of("transitioncancel:width@0.05", "transitionrun:width@0.0", "transitionstart:width@0.0"),
                f.takeEvents());
        f.tick(100);
        assertEquals(Length.px(125), f.el.style.width); // the full duration again, from 50px
        f.tick(150);
        assertEquals(Length.px(200), f.el.style.width);
        assertNull(f.el.animationState);
    }

    @Test
    void reversingIsShortened() {
        List<TransitionSpec> t = transition("opacity", 100, 0);
        fadeOut(t);
        f.tick(0);
        f.tick(25);
        assertEquals(0.75, f.opacity(), EPS);

        f.restyle(25, opacity(1, t)); // back to where it came from: a quarter of the way, a quarter of the time
        f.tick(25);
        f.tick(37.5);
        assertEquals(0.875, f.opacity(), EPS);
        f.tick(50);
        assertEquals(1, f.opacity());
        assertNull(f.el.animationState);
    }

    @Test
    void reversingTheReversalUsesTheShortenedState() {
        List<TransitionSpec> t = transition("opacity", 100, 0);
        fadeOut(t);
        f.tick(0);
        f.tick(50); // 0.5
        f.restyle(50, opacity(1, t)); // reverse: factor 0.5, 50ms
        f.tick(50);
        f.tick(75); // 0.75
        assertEquals(0.75, f.opacity(), EPS);
        f.restyle(75, opacity(0, t)); // reverse again: factor |0.5 * 0.5 + 1 - 0.5| = 0.75, 75ms
        f.tick(75);
        f.tick(112.5);
        assertEquals(0.375, f.opacity(), EPS);
        f.tick(150);
        assertNull(f.el.animationState);
    }

    @Test
    void unrelatedChangesDoNotDisturbARunningTransition() {
        List<TransitionSpec> t = transition("opacity", 100, 0);
        fadeOut(t);
        f.tick(0);
        f.tick(50);
        ComputedStyle recoloured = opacity(0, t);
        recoloured.color = 0xFFFF0000;
        f.restyle(50, recoloured);
        f.tick(75);
        assertEquals(0.25, f.opacity(), EPS);
        assertEquals(0xFFFF0000, f.el.style.color);
        assertEquals(List.of("transitionrun:opacity@0.0", "transitionstart:opacity@0.0"), f.takeEvents());
    }

    @Test
    void removingThePropertyFromTheListCancels() {
        fadeOut(transition("opacity", 100, 0));
        f.tick(0);
        f.tick(50);
        f.takeEvents();
        f.restyle(50, opacity(0, List.of()));
        assertSame(f.el.baseStyle, f.el.style);
        assertNull(f.el.animationState);
        f.tick(50);
        assertEquals(List.of("transitioncancel:opacity@0.05"), f.takeEvents());
    }

    @Test
    void zeroDurationCancels() {
        fadeOut(transition("opacity", 100, 0));
        f.tick(0);
        f.tick(50);
        f.restyle(50, opacity(0, transition("opacity", 0, 0)));
        assertEquals(0, f.opacity());
        assertNull(f.el.animationState);

        f.restyle(60, opacity(1, transition("opacity", 0, 0)));
        assertSame(f.el.baseStyle, f.el.style);
    }

    @Test
    void displayNoneCancelsAndNoTransitionStartsFromIt() {
        List<TransitionSpec> t = transition("opacity", 100, 0);
        fadeOut(t);
        f.tick(0);
        ComputedStyle hidden = opacity(1, t);
        hidden.display = Display.NONE;
        f.restyle(10, hidden);
        assertSame(hidden, f.el.style);

        f.restyle(20, opacity(0, t)); // shown again with another value: no transition from display: none
        assertSame(f.el.baseStyle, f.el.style);
        assertNull(f.el.animationState);
    }

    @Test
    void valuesThatCannotInterpolateDoNotTransition() {
        List<TransitionSpec> t = transition("width", 100, 0);
        f.restyle(0, style(Prop.WIDTH, Length.AUTO, Prop.TRANSITION, t));
        f.restyle(0, width(100, t));
        assertSame(f.el.baseStyle, f.el.style);
        assertNull(f.el.animationState);
    }

    @Test
    void allAndShorthandsExpand() {
        List<TransitionSpec> t = transition("all", 100, 0);
        f.restyle(0, style(Prop.OPACITY, 1f, Prop.MARGIN_TOP, Length.ZERO, Prop.TRANSITION, t));
        f.restyle(0, style(Prop.OPACITY, 0f, Prop.MARGIN_TOP, Length.px(10), Prop.TRANSITION, t));
        f.tick(0);
        f.tick(50);
        assertEquals(0.5, f.opacity(), EPS);
        assertEquals(Length.px(5), f.el.style.marginTop);

        AnimFixture g = new AnimFixture();
        List<TransitionSpec> margin = transition("margin", 100, 0);
        g.restyle(0, style(Prop.MARGIN_LEFT, Length.ZERO, Prop.TRANSITION, margin));
        g.restyle(0, style(Prop.MARGIN_LEFT, Length.px(10), Prop.TRANSITION, margin));
        g.tick(0);
        g.tick(50);
        assertEquals(Length.px(5), g.el.style.marginLeft);
    }

    @Test
    void theLastEntryNamingAPropertyWins() {
        List<TransitionSpec> t = List.of(new TransitionSpec("all", 100, 0, TimingFunction.LINEAR),
                new TransitionSpec("opacity", 0, 0, TimingFunction.LINEAR));
        f.restyle(0, style(Prop.OPACITY, 1f, Prop.WIDTH, Length.ZERO, Prop.TRANSITION, t));
        f.restyle(0, style(Prop.OPACITY, 0f, Prop.WIDTH, Length.px(10), Prop.TRANSITION, t));
        f.tick(0);
        f.tick(50);
        assertEquals(0, f.opacity());
        assertEquals(Length.px(5), f.el.style.width);
    }

    @Test
    void onlyLayoutPropertiesInvalidateLayout() {
        fadeOut(transition("opacity", 100, 0));
        f.tick(0);
        f.clearLayoutDirty();
        f.tick(50);
        assertFalse(f.doc.needsLayout());

        AnimFixture g = new AnimFixture();
        List<TransitionSpec> t = transition("width", 100, 0);
        g.restyle(0, width(0, t));
        g.restyle(0, width(100, t));
        g.tick(0);
        g.clearLayoutDirty();
        g.tick(50);
        assertTrue(g.doc.needsLayout());
        g.clearLayoutDirty();
        g.tick(50); // nothing moved
        assertFalse(g.doc.needsLayout());
    }

    @Test
    void reducedMotionSkipsTransitions() {
        AnimFixture g = new AnimFixture(new TestHost() {
            @Override
            public boolean prefersReducedMotion() { return true; }
        });
        List<TransitionSpec> t = transition("opacity", 100, 0);
        g.restyle(0, opacity(1, t));
        g.restyle(0, opacity(0, t));
        assertSame(g.el.baseStyle, g.el.style);
        g.tick(0);
        assertEquals(List.of(), g.events);
    }

    @Test
    void leavingTheDocumentDropsTransitions() {
        fadeOut(transition("opacity", 100, 0));
        f.tick(0);
        f.takeEvents();
        f.el.remove();
        f.tick(50);
        assertNull(f.el.animationState);
        assertFalse(f.engine.isAnimating());
        assertEquals(List.of("transitioncancel:opacity@0.05"), f.takeEvents());
    }
}
