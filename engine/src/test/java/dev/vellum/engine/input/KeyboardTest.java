package dev.vellum.engine.input;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.event.Event;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.vellum.engine.testing.Page.NONE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyboardTest {
    @Test
    void keysGoToTheFocusedElementOrBody() {
        Page page = new TestHost().load("<button id=b></button>");
        page.listen(page.doc.documentElement(), "keydown", "keyup");
        assertFalse(page.key("x"));
        page.byId("b").focus();
        page.key("y");
        assertEquals(List.of("keydown:body", "keyup:body", "keydown:b", "keyup:b"), page.log);
    }

    @Test
    void cancelledKeysAreConsumedAndEscapeGoesToTheHost() {
        Page page = new TestHost().load("<div></div>");
        assertFalse(page.key("Escape"));
        page.doc.body().addEventListener("keydown", Event::preventDefault);
        assertTrue(page.key("Escape"), "a script kept the screen open");
    }

    @Test
    void enterAndSpaceActivate() {
        Page page = new TestHost().load("<button id=b></button><input type=checkbox id=c><a id=a href=x>x</a>");
        page.listen(page.byId("b"), "click");
        page.byId("b").focus();
        assertTrue(page.key("Enter"));
        assertTrue(page.input.keyDown(" ", "Space", NONE));
        assertFalse(page.input.keyDown(" ", "Space", NONE), "auto-repeat does not re-click");
        page.input.keyUp(" ", "Space", NONE);
        assertEquals(List.of("click:b", "click:b"), page.log);
        page.byId("c").focus();
        assertFalse(page.key("Enter"), "Enter does not toggle checkboxes");
        page.key(" ");
        assertTrue(page.byId("c").checked());
        page.byId("a").focus();
        page.key("Enter");
        assertEquals(List.of("test:x"), page.host.navigations);
    }

    @Test
    void rangeKeysStepAndFireEvents() {
        Page page = new TestHost().load("<input type=range id=r min=0 max=100 step=5 value=50>");
        Element r = page.byId("r");
        page.listen(r, "input", "change");
        r.focus();
        List<String> values = new ArrayList<>();
        for (String key : List.of("ArrowRight", "ArrowLeft", "ArrowUp", "PageUp", "PageDown", "Home", "ArrowDown", "End", "ArrowRight")) {
            assertTrue(page.key(key), key);
            values.add(r.value());
        }
        assertEquals(List.of("55", "50", "55", "65", "55", "0", "0", "100", "100"), values);
        assertEquals(14, page.log.size(), "input and change for each of the 7 real changes");
        assertFalse(page.key("a"));
    }

    @Test
    void rangeValueIsConstrained() {
        Page page = new TestHost().load("""
                <input type=range id=a><input type=range id=b min=0 max=10 step=4 value=9>
                <input type=range id=c min=0 max=1 step=any value=0.3><input type=range id=d value=7.4 step=0.1>""");
        assertEquals("50", RangeControl.of(page.byId("a")).text(), "default: the midpoint");
        assertEquals("8", RangeControl.of(page.byId("b")).text(), "snapped below max");
        assertEquals("0.3", RangeControl.of(page.byId("c")).text());
        assertEquals("7.4", RangeControl.of(page.byId("d")).text(), "no floating-point noise");
    }

    @Test
    void arrowsMoveAndCheckWithinARadioGroup() {
        Page page = new TestHost().load("<input type=radio name=g id=g1 checked><input type=radio name=g id=g2 disabled><input type=radio name=g id=g3>");
        page.listen(page.byId("g3"), "change");
        page.byId("g1").focus();
        page.key("ArrowDown");
        assertSame(page.byId("g3"), page.doc.focusedElement());
        assertTrue(page.byId("g3").checked());
        assertFalse(page.byId("g1").checked());
        assertEquals(List.of("change:g3"), page.log);
        assertTrue(page.input.focusVisible());
        page.key("ArrowRight");
        assertSame(page.byId("g1"), page.doc.focusedElement(), "wraps, skipping the disabled radio");
        page.key("ArrowLeft");
        assertTrue(page.byId("g3").checked());
    }

    @Test
    void arrowsChangeAClosedSelect() {
        Page page = new TestHost().load("<select id=s><option>a<option value=B>b<option disabled>c<option>d</select>");
        Element s = page.byId("s");
        assertEquals("a", s.value());
        page.listen(s, "input", "change");
        s.focus();
        List<String> values = new ArrayList<>();
        for (String key : List.of("ArrowDown", "ArrowDown", "ArrowDown", "Home", "End", "ArrowUp")) {
            page.key(key);
            values.add(s.value());
        }
        assertEquals(List.of("B", "d", "d", "a", "d", "B"), values);
        assertEquals(10, page.log.size(), "no events when the selection stays");
    }
}
