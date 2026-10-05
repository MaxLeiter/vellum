package dev.vellum.engine.input;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.event.Event;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.vellum.engine.input.Fixture.NONE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyboardTest {
    @Test
    void keysGoToTheFocusedElementOrBody() {
        Fixture fx = new Fixture("<button id=b></button>");
        fx.listen(fx.doc.documentElement(), "keydown", "keyup");
        assertFalse(fx.key("x"));
        fx.el("b").focus();
        fx.key("y");
        assertEquals(List.of("keydown:body", "keyup:body", "keydown:b", "keyup:b"), fx.log);
    }

    @Test
    void cancelledKeysAreConsumedAndEscapeGoesToTheHost() {
        Fixture fx = new Fixture("<div></div>");
        assertFalse(fx.key("Escape"));
        fx.doc.body().addEventListener("keydown", Event::preventDefault);
        assertTrue(fx.key("Escape"), "a script kept the screen open");
    }

    @Test
    void enterAndSpaceActivate() {
        Fixture fx = new Fixture("<button id=b></button><input type=checkbox id=c><a id=a href=x>x</a>");
        fx.listen(fx.el("b"), "click");
        fx.el("b").focus();
        assertTrue(fx.key("Enter"));
        assertTrue(fx.input.keyDown(" ", "Space", NONE));
        assertFalse(fx.input.keyDown(" ", "Space", NONE), "auto-repeat does not re-click");
        fx.input.keyUp(" ", "Space", NONE);
        assertEquals(List.of("click:b", "click:b"), fx.log);
        fx.el("c").focus();
        assertFalse(fx.key("Enter"), "Enter does not toggle checkboxes");
        fx.key(" ");
        assertTrue(fx.el("c").checked());
        fx.el("a").focus();
        fx.key("Enter");
        assertEquals(List.of("test:x"), fx.host.navigations);
    }

    @Test
    void rangeKeysStepAndFireEvents() {
        Fixture fx = new Fixture("<input type=range id=r min=0 max=100 step=5 value=50>");
        Element r = fx.el("r");
        fx.listen(r, "input", "change");
        r.focus();
        List<String> values = new ArrayList<>();
        for (String key : List.of("ArrowRight", "ArrowLeft", "ArrowUp", "PageUp", "PageDown", "Home", "ArrowDown", "End", "ArrowRight")) {
            assertTrue(fx.key(key), key);
            values.add(r.value());
        }
        assertEquals(List.of("55", "50", "55", "65", "55", "0", "0", "100", "100"), values);
        assertEquals(14, fx.log.size(), "input and change for each of the 7 real changes");
        assertFalse(fx.key("a"));
    }

    @Test
    void rangeValueIsConstrained() {
        Fixture fx = new Fixture("""
                <input type=range id=a><input type=range id=b min=0 max=10 step=4 value=9>
                <input type=range id=c min=0 max=1 step=any value=0.3><input type=range id=d value=7.4 step=0.1>""");
        assertEquals("50", RangeControl.of(fx.el("a")).text(), "default: the midpoint");
        assertEquals("8", RangeControl.of(fx.el("b")).text(), "snapped below max");
        assertEquals("0.3", RangeControl.of(fx.el("c")).text());
        assertEquals("7.4", RangeControl.of(fx.el("d")).text(), "no floating-point noise");
    }

    @Test
    void arrowsMoveAndCheckWithinARadioGroup() {
        Fixture fx = new Fixture("<input type=radio name=g id=g1 checked><input type=radio name=g id=g2 disabled><input type=radio name=g id=g3>");
        fx.listen(fx.el("g3"), "change");
        fx.el("g1").focus();
        fx.key("ArrowDown");
        assertSame(fx.el("g3"), fx.doc.focusedElement());
        assertTrue(fx.el("g3").checked());
        assertFalse(fx.el("g1").checked());
        assertEquals(List.of("change:g3"), fx.log);
        assertTrue(fx.input.focusVisible());
        fx.key("ArrowRight");
        assertSame(fx.el("g1"), fx.doc.focusedElement(), "wraps, skipping the disabled radio");
        fx.key("ArrowLeft");
        assertTrue(fx.el("g3").checked());
    }

    @Test
    void arrowsChangeAClosedSelect() {
        Fixture fx = new Fixture("<select id=s><option>a<option value=B>b<option disabled>c<option>d</select>");
        Element s = fx.el("s");
        assertEquals("a", s.value());
        fx.listen(s, "input", "change");
        s.focus();
        List<String> values = new ArrayList<>();
        for (String key : List.of("ArrowDown", "ArrowDown", "ArrowDown", "Home", "End", "ArrowUp")) {
            fx.key(key);
            values.add(s.value());
        }
        assertEquals(List.of("B", "d", "d", "a", "d", "B"), values);
        assertEquals(10, fx.log.size(), "no events when the selection stays");
    }
}
