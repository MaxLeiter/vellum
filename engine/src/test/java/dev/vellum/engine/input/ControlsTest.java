package dev.vellum.engine.input;

import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What form controls paint, each alone at the top left of a page. Most drop their UA background so the trace is
 * just the control's own drawing (and its border).
 */
class ControlsTest {
    /**
     * A 100x20 field with a 1px border and 2px padding: content at (3, 3), 94x14, so text sits at y 5.5, drawn on
     * the device pixel at 6. Nothing overflows, so nothing is clipped.
     */
    private static final String FIELD = "display: block; width: 100px; height: 20px; padding: 2px; border: 1px solid; background: none";
    private static final String BORDER = "border 0,0 100x20 #ffe0e0e0";

    @Test
    void textInputPaintsValueAndCaretInsideItsPaddingBox() {
        Page page = new TestHost().load("<input id=t value=hi style='" + FIELD + "'>");
        assertEquals(List.of(BORDER, "text 'hi' 3,6 #ffe0e0e0"), page.paint().trace());
        page.byId("t").focus();
        page.frame();
        assertEquals(List.of(BORDER, "text 'hi' 3,6 #ffe0e0e0", "rect 11,6 1x9 #ffe0e0e0"), page.paint().trace());
        page.frame(600);
        assertEquals(List.of(BORDER, "text 'hi' 3,6 #ffe0e0e0"), page.paint().trace(), "the caret blinks off");
    }

    @Test
    void selectionIsHighlightedBehindTheText() {
        Page page = new TestHost().load("<input id=t value=hi style='" + FIELD + "'>");
        page.byId("t").focus();
        TextField.of(page.byId("t")).selectAll();
        assertEquals(List.of(BORDER, "rect 3,6 8x9 #735b8bd9", "text 'hi' 3,6 #ffe0e0e0"), page.paint().trace());
    }

    @Test
    void placeholderShowsWhenEmpty() {
        Page page = new TestHost().load("<style>#styled::placeholder { color: #888 }</style>"
                + "<input id=t placeholder=Name style='" + FIELD + "'><input id=styled placeholder=Name style='" + FIELD + "'>");
        assertEquals(List.of("text 'Name' 3,6 #ff808080", "text 'Name' 3,26 #ff888888"), page.paint().trace("text"),
                "the UA sheet's grey, then the page's own");
        page.byId("t").setValue("x");
        page.frame();
        assertEquals("text 'x' 3,6 #ffe0e0e0", page.paint().trace("text").getFirst());
    }

    @Test
    void passwordsAreBullets() {
        Page page = new TestHost().load("<input type=password value=ab style='" + FIELD + "'>");
        assertEquals(List.of("text '••' 3,6 #ffe0e0e0"), page.paint().trace("text"));
    }

    @Test
    void textareaPaintsWrappedLines() {
        String style = "display: block; width: 50px; padding: 0; overflow: hidden; background: none; height: ";
        Page page = new TestHost().load("<textarea id=ta style='" + style + "30px'>ab\ncd</textarea>");
        assertEquals(List.of("text 'ab' 0,0 #ffe0e0e0", "text 'cd' 0,9 #ffe0e0e0"), page.paint().trace());
        page.byId("ta").setAttribute("style", style + "10px");
        page.frame();
        assertEquals(List.of("clip 0,0 50x10", "text 'ab' 0,0 #ffe0e0e0", "text 'cd' 0,9 #ffe0e0e0"), page.paint().trace(),
                "clipped when the text overflows");
    }

    @Test
    void checkMarksAreAFallbackForUnstyledControls() {
        Page page = new TestHost().load("""
                <input type=checkbox id=cb checked style="display: block; width: 10px; height: 10px; background: none">
                <input type=radio id=rb checked style="display: block; width: 10px; height: 10px; background: none">
                <input type=checkbox id=sprite checked style="display: block; width: 10px; height: 10px">""");
        assertEquals(List.of("rect 2.50,2.50 5x5 #ff5b8bd9", "round 2.50,12.50 5x5 #ff5b8bd9",
                "sprite minecraft:widget/checkbox_selected 0,20 10x10"), page.paint().trace(),
                "no clip: the marks stay inside; the UA sprite already shows the state");
        page.byId("rb").setChecked(false);
        page.frame();
        assertEquals(List.of("rect 2.50,2.50 5x5 #ff5b8bd9", "sprite minecraft:widget/checkbox_selected 0,20 10x10"),
                page.paint().trace());
    }

    @Test
    void rangeDrawsTheVanillaHandleAndLabel() {
        Page page = new TestHost().load("<input type=range id=r label=Volume style='display: block; width: 108px'>");
        assertEquals(List.of("sprite minecraft:widget/slider 0,0 108x20", "sprite minecraft:widget/slider_handle 50,0 8x20",
                "text 'Volume: 50' 29,6 #ffffffff shadow"), page.paint().trace());
        page.hover(page.byId("r"));
        assertEquals("sprite minecraft:widget/slider_handle_highlighted 50,0 8x20", page.paint().trace().get(1));
    }

    @Test
    void selectShowsItsOptionAndAnArrow() {
        String style = "display: block; padding: 0; background: none; width: ";
        Page page = new TestHost().load("<select id=s style='" + style + "80px'><option>One<option selected>Two</select>");
        assertEquals(List.of("text '▼' 74,6 #ffffffff shadow", "text 'Two' 0,6 #ffffffff shadow"), page.paint().trace());
        page.byId("s").setAttribute("style", style + "20px");
        page.frame();
        assertEquals(List.of("text '▼' 14,6 #ffffffff shadow", "clip 0,0 12x20", "text 'Two' 0,6 #ffffffff shadow"),
                page.paint().trace(), "the label is clipped before the arrow when it does not fit");
    }

    @Test
    void barsFillTheirFraction() {
        String style = "style='display: block; width: 100px; height: 10px; background: none; border: none'";
        Page page = new TestHost().load("<progress " + style + " value=0.25></progress><progress " + style + "></progress>"
                + "<meter " + style + " min=10 max=20 value=15></meter>");
        assertEquals(List.of("rect 0,0 25x10 #ff5b8bd9", "rect 0,20 50x10 #ff5b8bd9"), page.paint().trace(),
                "indeterminate progress draws no fill");
    }

    @Test
    void inputButtonsShowTheirLabel() {
        Page page = new TestHost().load("<input type=submit style='display: block; width: 100px; padding: 0; background: none'>");
        assertEquals(List.of("text 'Submit' 35,6 #ffffffff shadow"), page.paint().trace());
    }
}
