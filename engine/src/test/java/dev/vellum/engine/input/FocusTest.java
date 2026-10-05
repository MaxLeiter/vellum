package dev.vellum.engine.input;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.vellum.engine.testing.Page.SHIFT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FocusTest {
    private static List<String> order(Page page) {
        List<String> ids = new ArrayList<>();
        for (Element e : new FocusNavigator(page.doc).order()) ids.add(e.id());
        return ids;
    }

    @Test
    void tabOrderIsPositiveTabindexFirstThenTreeOrder() {
        Page page = new TestHost().load("""
                <button id=a>a</button><input id=b tabindex=2><a id=c href=x>c</a><input id=d tabindex=1>
                <button id=e disabled>e</button><div id=f tabindex=0>f</div><div id=g tabindex=-1>g</div>
                <input id=h type=hidden><button id=i style="display: none">no box</button>
                <button id=j style="visibility: hidden">hidden</button>
                <details id=k><summary id=l>s</summary><button id=m>inside</button></details>
                <fieldset disabled><input id=o></fieldset><span id=n tabindex=0>inline</span>""");
        assertEquals(List.of("d", "b", "a", "c", "f", "l", "n"), order(page));
        page.byId("k").setAttribute("open", "");
        page.frame();
        assertEquals(List.of("d", "b", "a", "c", "f", "l", "m", "n"), order(page), "m is rendered once its details opens");
    }

    @Test
    void tabAndShiftTabCycle() {
        Page page = new TestHost().load("<button id=a></button><button id=b></button><button id=c></button>");
        assertTrue(page.key("Tab"));
        assertSame(page.byId("a"), page.doc.focusedElement());
        page.key("Tab");
        page.key("Tab");
        page.key("Tab");
        assertSame(page.byId("a"), page.doc.focusedElement(), "wraps around");
        page.key("Tab", SHIFT);
        assertSame(page.byId("c"), page.doc.focusedElement());
    }

    @Test
    void tabWithNothingFocusableIsLeftToTheHost() {
        Page page = new TestHost().load("<div>text</div>");
        assertFalse(page.key("Tab"));
    }

    @Test
    void radioGroupsAreOneTabStop() {
        Page page = new TestHost().load("""
                <input type=radio name=r id=r1><input type=radio name=r id=r2 checked><input type=radio name=r id=r3>
                <input type=radio name=q id=q1><input type=radio name=q id=q2>""");
        assertEquals(List.of("r2", "q1", "q2"), order(page));
    }

    @Test
    void focusVisibleAfterKeyboardNotAfterPointer() {
        Page page = new TestHost().load("<button id=a></button><button id=b></button>");
        page.key("Tab");
        assertTrue(page.input.focusVisible());
        page.click(page.byId("b"));
        assertSame(page.byId("b"), page.doc.focusedElement());
        assertFalse(page.input.focusVisible());
    }

    @Test
    void autofocusOnTheFirstLayoutOnly() {
        Page page = new TestHost().load("<input id=a><input id=b autofocus><input id=c autofocus>");
        assertSame(page.byId("b"), page.doc.focusedElement());
        assertTrue(page.input.focusVisible());
        page.byId("b").blur();
        page.doc.invalidateLayout();
        page.frame();
        assertNull(page.doc.focusedElement());
    }

    @Test
    void autofocusDefersToScriptFocus() {
        Page page = new TestHost().load("<input id=a><input id=b autofocus><script>document.getElementById('a').focus()</script>");
        assertSame(page.byId("a"), page.doc.focusedElement());
    }

    /** A 50px tall scroll container holding "top" at y 0 and "low" at y 40, which shows its first 10px. */
    private static Page scrolling() {
        return new TestHost().load("""
                <div id=s style="overflow-y: auto; width: 100px; height: 50px">
                  <button id=top style="display: block; width: 100px; height: 20px"></button>
                  <button id=low style="display: block; width: 100px; height: 20px; margin-top: 20px"></button>
                  <div style="height: 140px"></div>
                </div>""");
    }

    @Test
    void keyboardFocusScrollsIntoView() {
        Page page = scrolling();
        page.key("Tab");
        assertEquals(0, page.byId("s").scrollTop());
        page.key("Tab");
        assertEquals(10, page.byId("s").scrollTop(), "bottom edge aligned");
        page.key("Tab");
        assertEquals(0, page.byId("s").scrollTop(), "top edge aligned");
    }

    @Test
    void scriptFocusScrollsIntoViewButPointerFocusDoesNot() {
        Page page = scrolling();
        page.byId("low").focus();
        assertEquals(10, page.byId("s").scrollTop());
        page.byId("top").focus();
        assertEquals(0, page.byId("s").scrollTop());
        page.click(50, 45);
        assertTrue(page.byId("low").isFocused());
        assertEquals(0, page.byId("s").scrollTop());
    }
}
