package dev.vellum.engine.input;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.style.Overflow;
import dev.vellum.engine.style.Visibility;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.vellum.engine.input.Fixture.SHIFT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FocusTest {
    /** Gives every element a box, except those listed. */
    private static void boxAll(Fixture fx, String... except) {
        List<String> skip = List.of(except);
        for (Element e : fx.doc.getElementsByTagName("*")) if (!skip.contains(e.id())) fx.box(e, null, 0, 0, 10, 10);
    }

    private static List<String> ids(List<Element> elements) {
        List<String> out = new ArrayList<>();
        for (Element e : elements) out.add(e.id());
        return out;
    }

    private static List<String> order(Fixture fx) {
        return ids(new FocusNavigator(fx.doc).order());
    }

    @Test
    void tabOrderIsPositiveTabindexFirstThenTreeOrder() {
        Fixture fx = new Fixture("""
                <button id=a>a</button><input id=b tabindex=2><a id=c href=x>c</a><input id=d tabindex=1>
                <button id=e disabled>e</button><div id=f tabindex=0>f</div><div id=g tabindex=-1>g</div>
                <input id=h type=hidden><button id=i>no box</button><button id=j>hidden</button>
                <details id=k><summary id=l>s</summary><button id=m>inside</button></details>
                <fieldset disabled><input id=o></fieldset><span id=n tabindex=0>inline</span>""");
        boxAll(fx, "i", "m"); // m: hidden in the closed details, so layout gave it no box
        fx.el("j").style.visibility = Visibility.HIDDEN;
        assertEquals(List.of("d", "b", "a", "c", "f", "l", "n"), order(fx));
        fx.el("k").setAttribute("open", "");
        fx.box("m", null, 0, 0, 10, 10);
        assertEquals(List.of("d", "b", "a", "c", "f", "l", "m", "n"), order(fx));
    }

    @Test
    void tabAndShiftTabCycle() {
        Fixture fx = new Fixture("<button id=a></button><button id=b></button><button id=c></button>");
        boxAll(fx);
        assertTrue(fx.key("Tab"));
        assertSame(fx.el("a"), fx.doc.focusedElement());
        fx.key("Tab");
        fx.key("Tab");
        fx.key("Tab");
        assertSame(fx.el("a"), fx.doc.focusedElement(), "wraps around");
        fx.key("Tab", SHIFT);
        assertSame(fx.el("c"), fx.doc.focusedElement());
    }

    @Test
    void tabWithNothingFocusableIsLeftToTheHost() {
        Fixture fx = new Fixture("<div>text</div>");
        assertFalse(fx.key("Tab"));
    }

    @Test
    void radioGroupsAreOneTabStop() {
        Fixture fx = new Fixture("""
                <input type=radio name=r id=r1><input type=radio name=r id=r2 checked><input type=radio name=r id=r3>
                <input type=radio name=q id=q1><input type=radio name=q id=q2>""");
        boxAll(fx);
        assertEquals(List.of("r2", "q1", "q2"), order(fx));
    }

    @Test
    void focusVisibleAfterKeyboardNotAfterPointer() {
        Fixture fx = new Fixture("<button id=a></button><button id=b></button>");
        boxAll(fx);
        fx.key("Tab");
        assertTrue(fx.input.focusVisible());
        fx.forcedHit = fx.el("b");
        fx.click(0, 0);
        assertSame(fx.el("b"), fx.doc.focusedElement());
        assertFalse(fx.input.focusVisible());
    }

    @Test
    void autofocusOnTheFirstLayoutOnly() {
        Fixture fx = new Fixture("<input id=a><input id=b autofocus><input id=c autofocus>");
        boxAll(fx);
        fx.input.afterLayout();
        assertSame(fx.el("b"), fx.doc.focusedElement());
        assertTrue(fx.input.focusVisible());
        fx.el("b").blur();
        fx.input.afterLayout();
        assertNull(fx.doc.focusedElement());
    }

    @Test
    void autofocusDefersToScriptFocus() {
        Fixture fx = new Fixture("<input id=a><input id=b autofocus>");
        boxAll(fx);
        fx.el("a").focus();
        fx.input.afterLayout();
        assertSame(fx.el("a"), fx.doc.focusedElement());
    }

    /** A 50px tall scroll container holding "top" at y 0 and "low" at y 120. */
    private static Fixture scrolling() {
        Fixture fx = new Fixture("<div id=s><button id=top></button><button id=low></button></div>");
        var s = fx.box("s", null, 0, 0, 100, 50);
        s.scrollHeight = 200;
        fx.el("s").style.overflowY = Overflow.AUTO;
        fx.box("top", s, 0, 0, 100, 20);
        fx.box("low", s, 0, 120, 100, 20);
        return fx;
    }

    @Test
    void keyboardFocusScrollsIntoView() {
        Fixture fx = scrolling();
        fx.key("Tab");
        assertEquals(0, fx.el("s").scrollTop());
        fx.key("Tab");
        assertEquals(90, fx.el("s").scrollTop(), "bottom edge aligned");
        fx.key("Tab");
        assertEquals(0, fx.el("s").scrollTop(), "top edge aligned");
    }

    @Test
    void scriptFocusScrollsIntoViewButPointerFocusDoesNot() {
        Fixture fx = scrolling();
        fx.el("low").focus();
        assertEquals(90, fx.el("s").scrollTop());
        fx.el("top").focus();
        assertEquals(0, fx.el("s").scrollTop());
        fx.forcedHit = fx.el("low");
        fx.click(0, 0);
        assertTrue(fx.el("low").isFocused());
        assertEquals(0, fx.el("s").scrollTop());
    }
}
