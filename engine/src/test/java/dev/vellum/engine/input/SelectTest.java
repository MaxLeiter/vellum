package dev.vellum.engine.input;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.RecordingCanvas;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.vellum.engine.testing.Page.ALT;
import static dev.vellum.engine.testing.Page.NONE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectTest {
    /** Rows: One, Two, G (header), Three, Four (disabled). Rows are 12px tall, starting at y 31 under the select. */
    private final Page page = new TestHost().load("""
            <style>
              #s { position: absolute; left: 10px; top: 10px; width: 80px; height: 20px }
              #other { position: absolute; left: 200px; top: 200px; width: 10px; height: 10px }
            </style>
            <select id=s><option value=1>One<option value=2 selected>Two
            <optgroup label=G><option value=3>Three<option value=4 disabled>Four</optgroup></select>
            <button id=other></button>""");
    private final Element s = page.byId("s");

    {
        page.listen(s, "input", "change");
    }

    private static float row(int i) {
        return 31 + 12 * i + 6;
    }

    private RecordingCanvas overlays() {
        RecordingCanvas canvas = new RecordingCanvas();
        page.input.paintOverlays(canvas);
        return canvas;
    }

    private boolean isOpen() {
        return !overlays().calls.isEmpty();
    }

    @Test
    void valuesFollowSelectedAttributesAndOptionText() {
        Page other = new TestHost().load("<select id=a><option disabled>x<option> Plain  text </option></select><select id=b></select>");
        assertEquals("2", s.value());
        assertEquals("Plain text", other.byId("a").value(), "first enabled option; value defaults to the text");
        assertEquals("", other.byId("b").value());
    }

    @Test
    void clickOpensAndChoosingAnOptionCommits() {
        page.click(20, 20);
        assertTrue(isOpen());
        assertTrue(s.isFocused());
        assertEquals(List.of(Activation.CLICK_SOUND), page.host.sounds);
        page.move(20, row(3));
        page.down(20, row(3));
        assertTrue(isOpen(), "the press inside the list is swallowed");
        page.up(20, row(3));
        assertEquals("3", s.value());
        assertEquals(List.of("input:s", "change:s"), page.log);
        assertFalse(isOpen());
    }

    @Test
    void releasingOnAHeaderOrDisabledRowKeepsTheListOpen() {
        page.click(20, 20);
        page.down(20, row(2));
        page.up(20, row(2));
        page.down(20, row(4));
        page.up(20, row(4));
        assertTrue(isOpen());
        assertEquals("2", s.value());
    }

    @Test
    void pressOutsideClosesWithoutChanging() {
        page.click(20, 20);
        assertTrue(page.input.mouseDown(205, 205, 0, NONE));
        page.up(205, 205);
        assertFalse(isOpen());
        assertEquals("2", s.value());
        assertTrue(s.isFocused(), "the swallowed press did not move focus");
        page.click(20, 20);
        page.click(20, 20);
        assertFalse(isOpen(), "clicking the select again toggles the list");
    }

    @Test
    void keyboardOpensNavigatesAndCommits() {
        s.focus();
        assertTrue(page.key("Enter"));
        assertTrue(isOpen());
        page.key("ArrowDown");
        page.key("ArrowDown");
        assertTrue(page.key("Enter"));
        assertEquals("3", s.value(), "skipped the header; the disabled option stopped the second move");
        assertFalse(isOpen());
        page.key(" ");
        page.key("Home");
        assertTrue(page.key("Escape"), "Escape closes the list instead of the screen");
        assertFalse(isOpen());
        assertEquals("3", s.value());
        page.key("ArrowDown", ALT);
        assertTrue(isOpen());
    }

    @Test
    void tabClosesTheListAndMovesFocus() {
        s.focus();
        page.key("Enter");
        page.key("Tab");
        assertFalse(isOpen());
        assertSame(page.byId("other"), page.doc.focusedElement());
    }

    @Test
    void listOpensAboveWhenThereIsNoRoomBelow() {
        s.setAttribute("style", "top: 200px");
        page.frame();
        page.click(20, 210);
        assertEquals("rect 10,138 80x62 #f0100010", overlays().trace().getFirst());
    }

    @Test
    void longListsScrollWithTheWheel() {
        Page list = new TestHost().load("<select id=s style='width: 80px'>" + "<option>o".repeat(12) + "<option>last</select>");
        list.click(5, 5);
        RecordingCanvas before = new RecordingCanvas();
        list.input.paintOverlays(before);
        assertEquals(8, before.trace("text").size(), "eight rows visible");
        assertTrue(list.input.wheel(5, 40, 0, 100, NONE));
        RecordingCanvas after = new RecordingCanvas();
        list.input.paintOverlays(after);
        assertTrue(after.trace("text").getLast().startsWith("text 'last'"));
    }

    @Test
    void removingTheSelectClosesItsList() {
        page.click(20, 20);
        s.remove();
        assertFalse(isOpen());
    }

    @Test
    void paintsRowsWithHighlightAndSelectedColour() {
        page.click(20, 20);
        page.move(20, row(0));
        List<String> calls = overlays().trace();
        assertTrue(calls.contains("rect 11,31 78x12 #40ffffff"), "hover highlight on One: " + calls);
        assertTrue(calls.contains("text 'One' 15,32.50 #ffffffff shadow"));
        assertTrue(calls.contains("text 'Two' 15,44.50 #ffffff55 shadow"), "the selected option");
        assertTrue(calls.contains("text 'Three' 19,68.50 #ffffffff shadow"), "indented under its group");
        assertTrue(calls.contains("text 'Four' 19,80.50 #ff808080 shadow"), "disabled");
    }
}
