package dev.vellum.engine.input;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.layout.Box;
import dev.vellum.engine.style.BackgroundLayer;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Image;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ControlsTest {
    private final Fixture fx = new Fixture("""
            <input id=t value=hi placeholder=Name><input id=pw type=password value=ab>
            <input type=checkbox id=cb checked><input type=radio id=rb checked>
            <input type=range id=r label=Volume><select id=s><option>One<option selected>Two</select>
            <progress id=p value=0.25></progress><progress id=pi></progress><meter id=m min=10 max=20 value=15></meter>
            <input type=submit id=sub><textarea id=ta>ab
            cd</textarea>""");

    private List<String> paint(String id) {
        RecordingCanvas canvas = new RecordingCanvas();
        Controls.paint(canvas, fx.el(id).box);
        return canvas.calls;
    }

    /** A 100x20 field with a 1px border and 2px padding: content at (3, 3), 94x14, so text sits at y 5.5. */
    private Element field(String id) {
        Box b = fx.box(id, null, 0, 0, 100, 20);
        b.borderLeft = b.borderTop = b.borderRight = b.borderBottom = 1;
        b.paddingLeft = b.paddingTop = b.paddingRight = b.paddingBottom = 2;
        return fx.el(id);
    }

    @Test
    void textInputPaintsValueAndCaretInsideItsPaddingBox() {
        Element t = field("t");
        assertEquals(List.of("clip 1,1 98x18", "text 'hi' 3,5.50 #ff000000"), paint("t"));
        t.focus();
        assertEquals(List.of("clip 1,1 98x18", "text 'hi' 3,5.50 #ff000000", "rect 11,5.50 1x9 #ff000000"), paint("t"));
        fx.input.tick(600);
        assertEquals(2, paint("t").size(), "the caret blinks off");
    }

    @Test
    void selectionIsHighlightedBehindTheText() {
        Element t = field("t");
        t.focus();
        TextField.of(t).selectAll();
        assertEquals(List.of("clip 1,1 98x18", "rect 3,5.50 8x9 #735b8bd9", "text 'hi' 3,5.50 #ff000000"), paint("t"));
    }

    @Test
    void placeholderShowsWhenEmpty() {
        Element t = field("t");
        t.setValue("");
        assertEquals("text 'Name' 3,5.50 #80000000", paint("t").get(1), "half-transparent text colour");
        t.placeholderStyle = new ComputedStyle();
        t.placeholderStyle.color = 0xFF888888;
        assertEquals("text 'Name' 3,5.50 #ff888888", paint("t").get(1));
    }

    @Test
    void passwordsAreBullets() {
        field("pw");
        assertEquals("text '••' 3,5.50 #ff000000", paint("pw").get(1));
    }

    @Test
    void textareaPaintsWrappedLines() {
        fx.box("ta", null, 0, 0, 50, 30);
        assertEquals(List.of("clip 0,0 50x30", "text 'ab' 0,0 #ff000000", "text 'cd' 0,9 #ff000000"), paint("ta"));
    }

    @Test
    void checkMarksAreAFallbackForUnstyledControls() {
        fx.box("cb", null, 0, 0, 10, 10);
        fx.box("rb", null, 0, 0, 10, 10);
        assertEquals("rect 2.50,2.50 5x5 #ff5b8bd9", paint("cb").get(1));
        assertEquals("round 2.50,2.50 5x5 #ff5b8bd9", paint("rb").get(1));
        fx.el("rb").setChecked(false);
        assertEquals(1, paint("rb").size());
        fx.el("cb").style.backgroundLayers = List.of(BackgroundLayer.simple(new Image.Sprite("minecraft:widget/checkbox")));
        assertEquals(1, paint("cb").size(), "the UA sprite already shows the state");
    }

    @Test
    void rangeDrawsTheVanillaHandleAndLabel() {
        fx.box("r", null, 0, 0, 108, 20);
        assertEquals(List.of("clip 0,0 108x20", "sprite minecraft:widget/slider_handle 50,0 8x20",
                "text 'Volume: 50' 28.50,5.50 #ff000000 shadow"), paint("r"));
        fx.doc.setHovered(fx.el("r"), true);
        assertEquals("sprite minecraft:widget/slider_handle_highlighted 50,0 8x20", paint("r").get(1));
    }

    @Test
    void selectShowsItsOptionAndAnArrow() {
        fx.box("s", null, 0, 0, 80, 20);
        assertEquals(List.of("clip 0,0 80x20", "text '▼' 74,5.50 #ff000000", "clip 0,0 72x20", "text 'Two' 0,5.50 #ff000000"),
                paint("s"));
    }

    @Test
    void barsFillTheirFraction() {
        for (String id : List.of("p", "pi", "m")) fx.box(id, null, 0, 0, 100, 10);
        assertEquals("rect 0,0 25x10 #ff5b8bd9", paint("p").get(1));
        assertEquals(1, paint("pi").size(), "indeterminate progress draws no fill");
        assertEquals("rect 0,0 50x10 #ff5b8bd9", paint("m").get(1));
    }

    @Test
    void inputButtonsShowTheirLabel() {
        fx.box("sub", null, 0, 0, 100, 20);
        assertEquals("text 'Submit' 35,5.50 #ff000000", paint("sub").get(1));
    }
}
