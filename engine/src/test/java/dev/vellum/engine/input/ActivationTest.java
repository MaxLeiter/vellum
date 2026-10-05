package dev.vellum.engine.input;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.event.Event;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Activation behaviour through {@code element.click()}, which needs no layout. */
class ActivationTest {
    @Test
    void checkboxTogglesAndFiresInputThenChange() {
        Page page = new TestHost().load("<input type=checkbox id=c>");
        Element c = page.byId("c");
        page.listen(c, "click", "input", "change");
        c.click();
        assertTrue(c.checked());
        assertEquals(List.of("click:c", "input:c", "change:c"), page.log);
        assertEquals(List.of(Activation.CLICK_SOUND), page.host.sounds);
        c.click();
        assertFalse(c.checked());
    }

    @Test
    void cancelledOrDisabledClicksDoNothing() {
        Page page = new TestHost().load("<input type=checkbox id=a><input type=checkbox id=b disabled><fieldset disabled><input type=checkbox id=c></fieldset>");
        page.byId("a").addEventListener("click", Event::preventDefault);
        for (String id : List.of("a", "b", "c")) {
            page.byId(id).click();
            assertFalse(page.byId(id).checked(), id);
        }
        assertEquals(List.of(), page.host.sounds);
    }

    @Test
    void radiosAreExclusiveWithinTheirFormAndName() {
        Page page = new TestHost().load("""
                <form><input type=radio name=r id=a checked><input type=radio name=r id=b><input type=radio name=x id=x checked></form>
                <form><input type=radio name=r id=c checked></form>
                <input type=radio name=r id=d>""");
        page.listen(page.byId("b"), "change");
        page.byId("b").click();
        assertEquals(List.of(false, true, true, true, false),
                List.of(page.byId("a").checked(), page.byId("b").checked(), page.byId("x").checked(), page.byId("c").checked(), page.byId("d").checked()));
        page.byId("d").click();
        assertTrue(page.byId("b").checked(), "a radio outside the form is another group");
        page.byId("b").click();
        assertEquals(List.of("change:b"), page.log, "re-checking a checked radio is not a change");
    }

    @Test
    void labelsForwardClicksToTheirControl() {
        Page page = new TestHost().load("""
                <label id=l1 for=c1>one</label><input type=checkbox id=c1>
                <label id=l2><span id=s>two</span><input type=checkbox id=c2></label>
                <label id=l3>name <input id=t></label>""");
        page.byId("l1").click();
        assertTrue(page.byId("c1").checked());
        page.byId("s").click();
        assertTrue(page.byId("c2").checked());
        page.byId("c2").click();
        assertFalse(page.byId("c2").checked(), "a click on the control inside its label toggles once");
        page.byId("l3").click();
        assertTrue(page.byId("t").isFocused(), "labels focus their control");
        page.byId("t").click(); // inside the label, but the control handles it: no loop
    }

    @Test
    void summaryTogglesItsDetails() {
        Page page = new TestHost().load("<details id=d><summary id=s>S</summary>body</details>");
        Element d = page.byId("d");
        page.listen(d, "toggle");
        page.byId("s").click();
        assertTrue(d.hasAttribute("open"));
        page.byId("s").click();
        assertFalse(d.hasAttribute("open"));
        assertEquals(List.of("toggle:d", "toggle:d"), page.log);
        assertEquals(2, page.host.sounds.size());
    }

    @Test
    void linksNavigateToTheResolvedUrl() {
        Page page = new TestHost().load("<a id=a href=other.html><b id=b>go</b></a><a id=n>no href</a>");
        page.byId("b").click();
        page.byId("n").click();
        assertEquals(List.of("test:other.html"), page.host.navigations);
    }

    @Test
    void submitButtonsSubmitTheirForm() {
        Page page = new TestHost().load("""
                <form id=f><button id=b1>go</button><button id=b2 type=button>no</button><input type=submit id=b3>
                <input type=reset id=b4></form><button id=b5 form=f>remote</button><button id=b6>outside</button>""");
        page.listen(page.byId("f"), "submit");
        for (String id : List.of("b1", "b2", "b3", "b4", "b5", "b6")) page.byId(id).click();
        assertEquals(List.of("submit:f", "submit:f", "submit:f"), page.log);
        assertEquals(6, page.host.sounds.size());
    }
}
