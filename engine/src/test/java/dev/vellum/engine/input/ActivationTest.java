package dev.vellum.engine.input;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.event.Event;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Activation behaviour through {@code element.click()}, which needs no layout. */
class ActivationTest {
    @Test
    void checkboxTogglesAndFiresInputThenChange() {
        Fixture fx = new Fixture("<input type=checkbox id=c>");
        Element c = fx.el("c");
        fx.listen(c, "click", "input", "change");
        c.click();
        assertTrue(c.checked());
        assertEquals(List.of("click:c", "input:c", "change:c"), fx.log);
        assertEquals(List.of(Activation.CLICK_SOUND), fx.host.sounds);
        c.click();
        assertFalse(c.checked());
    }

    @Test
    void cancelledOrDisabledClicksDoNothing() {
        Fixture fx = new Fixture("<input type=checkbox id=a><input type=checkbox id=b disabled><fieldset disabled><input type=checkbox id=c></fieldset>");
        fx.el("a").addEventListener("click", Event::preventDefault);
        for (String id : List.of("a", "b", "c")) {
            fx.el(id).click();
            assertFalse(fx.el(id).checked(), id);
        }
        assertEquals(List.of(), fx.host.sounds);
    }

    @Test
    void radiosAreExclusiveWithinTheirFormAndName() {
        Fixture fx = new Fixture("""
                <form><input type=radio name=r id=a checked><input type=radio name=r id=b><input type=radio name=x id=x checked></form>
                <form><input type=radio name=r id=c checked></form>
                <input type=radio name=r id=d>""");
        fx.listen(fx.el("b"), "change");
        fx.el("b").click();
        assertEquals(List.of(false, true, true, true, false),
                List.of(fx.el("a").checked(), fx.el("b").checked(), fx.el("x").checked(), fx.el("c").checked(), fx.el("d").checked()));
        fx.el("d").click();
        assertTrue(fx.el("b").checked(), "a radio outside the form is another group");
        fx.el("b").click();
        assertEquals(List.of("change:b"), fx.log, "re-checking a checked radio is not a change");
    }

    @Test
    void labelsForwardClicksToTheirControl() {
        Fixture fx = new Fixture("""
                <label id=l1 for=c1>one</label><input type=checkbox id=c1>
                <label id=l2><span id=s>two</span><input type=checkbox id=c2></label>
                <label id=l3>name <input id=t></label>""");
        fx.el("l1").click();
        assertTrue(fx.el("c1").checked());
        fx.el("s").click();
        assertTrue(fx.el("c2").checked());
        fx.el("c2").click();
        assertFalse(fx.el("c2").checked(), "a click on the control inside its label toggles once");
        fx.el("l3").click();
        assertTrue(fx.el("t").isFocused(), "labels focus their control");
        fx.el("t").click(); // inside the label, but the control handles it: no loop
    }

    @Test
    void summaryTogglesItsDetails() {
        Fixture fx = new Fixture("<details id=d><summary id=s>S</summary>body</details>");
        Element d = fx.el("d");
        fx.listen(d, "toggle");
        fx.el("s").click();
        assertTrue(d.hasAttribute("open"));
        fx.el("s").click();
        assertFalse(d.hasAttribute("open"));
        assertEquals(List.of("toggle:d", "toggle:d"), fx.log);
        assertEquals(2, fx.host.sounds.size());
    }

    @Test
    void linksNavigateToTheResolvedUrl() {
        Fixture fx = new Fixture("<a id=a href=other.html><b id=b>go</b></a><a id=n>no href</a>");
        fx.el("b").click();
        fx.el("n").click();
        assertEquals(List.of("test:other.html"), fx.host.navigations);
    }

    @Test
    void submitButtonsSubmitTheirForm() {
        Fixture fx = new Fixture("""
                <form id=f><button id=b1>go</button><button id=b2 type=button>no</button><input type=submit id=b3>
                <input type=reset id=b4></form><button id=b5 form=f>remote</button><button id=b6>outside</button>""");
        fx.listen(fx.el("f"), "submit");
        for (String id : List.of("b1", "b2", "b3", "b4", "b5", "b6")) fx.el(id).click();
        assertEquals(List.of("submit:f", "submit:f", "submit:f"), fx.log);
        assertEquals(6, fx.host.sounds.size());
    }
}
