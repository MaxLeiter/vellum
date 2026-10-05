package dev.vellum.engine.input;

import dev.vellum.engine.dom.Element;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.vellum.engine.input.Fixture.ALT;
import static dev.vellum.engine.input.Fixture.NONE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectTest {
    /** Rows: One, Two, G (header), Three, Four (disabled). Rows are 12px tall, starting at y 31 under the select. */
    private final Fixture fx = new Fixture("""
            <select id=s><option value=1>One<option value=2 selected>Two
            <optgroup label=G><option value=3>Three<option value=4 disabled>Four</optgroup></select>
            <button id=other></button>""");
    private final Element s = fx.el("s");

    {
        fx.box(s, null, 10, 10, 80, 20);
        fx.box("other", null, 200, 200, 10, 10);
        fx.listen(s, "input", "change");
    }

    private static float row(int i) {
        return 31 + 12 * i + 6;
    }

    private RecordingCanvas overlays() {
        RecordingCanvas canvas = new RecordingCanvas();
        fx.input.paintOverlays(canvas);
        return canvas;
    }

    private boolean isOpen() {
        return !overlays().calls.isEmpty();
    }

    @Test
    void valuesFollowSelectedAttributesAndOptionText() {
        Fixture fx = new Fixture("<select id=a><option disabled>x<option> Plain  text </option></select><select id=b></select>");
        assertEquals("2", s.value());
        assertEquals("Plain text", fx.el("a").value(), "first enabled option; value defaults to the text");
        assertEquals("", fx.el("b").value());
    }

    @Test
    void clickOpensAndChoosingAnOptionCommits() {
        fx.click(20, 20);
        assertTrue(isOpen());
        assertTrue(s.isFocused());
        assertEquals(List.of(Activation.CLICK_SOUND), fx.host.sounds);
        fx.move(20, row(3));
        fx.down(20, row(3));
        assertTrue(isOpen(), "the press inside the list is swallowed");
        fx.up(20, row(3));
        assertEquals("3", s.value());
        assertEquals(List.of("input:s", "change:s"), fx.log);
        assertFalse(isOpen());
    }

    @Test
    void releasingOnAHeaderOrDisabledRowKeepsTheListOpen() {
        fx.click(20, 20);
        fx.down(20, row(2));
        fx.up(20, row(2));
        fx.down(20, row(4));
        fx.up(20, row(4));
        assertTrue(isOpen());
        assertEquals("2", s.value());
    }

    @Test
    void pressOutsideClosesWithoutChanging() {
        fx.click(20, 20);
        assertTrue(fx.input.mouseDown(205, 205, 0, NONE));
        fx.up(205, 205);
        assertFalse(isOpen());
        assertEquals("2", s.value());
        assertTrue(s.isFocused(), "the swallowed press did not move focus");
        fx.click(20, 20);
        fx.click(20, 20);
        assertFalse(isOpen(), "clicking the select again toggles the list");
    }

    @Test
    void keyboardOpensNavigatesAndCommits() {
        s.focus();
        assertTrue(fx.key("Enter"));
        assertTrue(isOpen());
        fx.key("ArrowDown");
        fx.key("ArrowDown");
        assertTrue(fx.key("Enter"));
        assertEquals("3", s.value(), "skipped the header; the disabled option stopped the second move");
        assertFalse(isOpen());
        fx.key(" ");
        fx.key("Home");
        assertTrue(fx.key("Escape"), "Escape closes the list instead of the screen");
        assertFalse(isOpen());
        assertEquals("3", s.value());
        fx.key("ArrowDown", ALT);
        assertTrue(isOpen());
    }

    @Test
    void tabClosesTheListAndMovesFocus() {
        s.focus();
        fx.key("Enter");
        fx.key("Tab");
        assertFalse(isOpen());
        assertSame(fx.el("other"), fx.doc.focusedElement());
    }

    @Test
    void listOpensAboveWhenThereIsNoRoomBelow() {
        s.box.y = 200;
        fx.click(20, 210);
        assertEquals("rect 10,138 80x62 #f0100010", overlays().calls.getFirst());
    }

    @Test
    void longListsScrollWithTheWheel() {
        Fixture fx = new Fixture("<select id=s>" + "<option>o".repeat(12) + "<option>last</select>");
        fx.box("s", null, 0, 0, 80, 20);
        fx.click(5, 5);
        RecordingCanvas before = new RecordingCanvas();
        fx.input.paintOverlays(before);
        assertEquals(8, before.matching("text").size(), "eight rows visible");
        assertTrue(fx.input.wheel(5, 40, 0, 100, NONE));
        RecordingCanvas after = new RecordingCanvas();
        fx.input.paintOverlays(after);
        assertTrue(after.matching("text").getLast().startsWith("text 'last'"));
    }

    @Test
    void removingTheSelectClosesItsList() {
        fx.click(20, 20);
        s.remove();
        assertFalse(isOpen());
    }

    @Test
    void paintsRowsWithHighlightAndSelectedColour() {
        fx.click(20, 20);
        fx.move(20, row(0));
        List<String> calls = overlays().calls;
        assertTrue(calls.contains("rect 11,31 78x12 #40ffffff"), "hover highlight on One: " + calls);
        assertTrue(calls.contains("text 'One' 15,32.50 #ffffffff shadow"));
        assertTrue(calls.contains("text 'Two' 15,44.50 #ffffff55 shadow"), "the selected option");
        assertTrue(calls.contains("text 'Three' 19,68.50 #ffffffff shadow"), "indented under its group");
        assertTrue(calls.contains("text 'Four' 19,80.50 #ff808080 shadow"), "disabled");
    }
}
