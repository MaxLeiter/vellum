package dev.vellum.engine.input;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.event.InputEvent;
import dev.vellum.engine.layout.Box;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.vellum.engine.input.Fixture.ALT;
import static dev.vellum.engine.input.Fixture.NONE;
import static dev.vellum.engine.input.Fixture.SHIFT;
import static dev.vellum.engine.input.Fixture.SHORTCUT;
import static dev.vellum.engine.input.Fixture.SHORTCUT_SHIFT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextFieldTest {
    private final Fixture fx = new Fixture("""
            <input id=t value=hi><input id=pw type=password value=secret><input id=ro value=abc readonly>
            <input id=num type=number><input id=max maxlength=3><input id=cb type=checkbox>
            <textarea id=ta>aaa bbb ccc</textarea>""");
    private final Element t = fx.el("t");

    private TextEditor editor(Element e) {
        return TextField.of(e).editor;
    }

    @Test
    void typingFiresBeforeinputThenInput() {
        fx.listen(t, "beforeinput", "input", "change");
        t.focus();
        fx.type("ab");
        assertEquals("hiab", t.value());
        assertEquals(List.of("beforeinput:t=a", "input:t=a", "beforeinput:t=b", "input:t=b"), fx.log);
    }

    @Test
    void cancelledBeforeinputSkipsTheEdit() {
        t.addEventListener("beforeinput", e -> { if ("x".equals(((InputEvent) e).data)) e.preventDefault(); });
        t.focus();
        fx.type("axb");
        assertEquals("hiab", t.value());
    }

    @Test
    void cancelledKeydownDropsItsCharacter() {
        t.addEventListener("keydown", e -> e.preventDefault());
        t.focus();
        assertTrue(fx.input.keyDown("a", "KeyA", NONE));
        assertTrue(fx.input.charTyped("a"));
        assertEquals("hi", t.value());
        assertTrue(fx.input.charTyped("é"), "a character without a keydown (IME) still goes in");
        assertEquals("hié", t.value());
    }

    @Test
    void changeFiresOnEnterAndOnBlurOnlyWhenTheValueChanged() {
        fx.listen(t, "change");
        t.focus();
        t.blur();
        assertEquals(List.of(), fx.log);
        t.focus();
        fx.type("!");
        assertTrue(fx.key("Enter"));
        assertEquals(List.of("change:t"), fx.log);
        t.blur();
        assertEquals(List.of("change:t"), fx.log, "already committed by Enter");
        t.focus();
        fx.type("?");
        t.blur();
        assertEquals(List.of("change:t", "change:t"), fx.log);
    }

    @Test
    void editingKeys() {
        t.focus();
        fx.type(" there world");
        assertEquals("hi there world", t.value());
        fx.key("Backspace", ALT);
        assertEquals("hi there ", t.value());
        fx.key("Home");
        fx.key("Delete");
        assertEquals("i there ", t.value());
        fx.key("ArrowRight", ALT);
        fx.key("End", SHIFT);
        assertEquals(" there ", editor(t).selectedText());
        fx.key("Backspace");
        assertEquals("i", t.value());
        assertFalse(fx.key("Escape"), "Escape goes to the host");
    }

    @Test
    void clipboardShortcuts() {
        t.focus();
        fx.key("a", SHORTCUT);
        fx.key("c", SHORTCUT);
        assertEquals("hi", fx.host.clipboard);
        fx.key("x", SHORTCUT);
        assertEquals("", t.value());
        fx.key("v", SHORTCUT);
        fx.key("v", SHORTCUT);
        assertEquals("hihi", t.value());
    }

    @Test
    void passwordsAreMaskedAndNeverCopied() {
        Element pw = fx.el("pw");
        pw.focus();
        fx.key("a", SHORTCUT);
        fx.key("c", SHORTCUT);
        fx.key("x", SHORTCUT);
        assertEquals("", fx.host.clipboard);
        assertEquals("secret", pw.value(), "cut does nothing either");
        assertEquals("••••••", TextField.of(pw).layout().line(0).display());
    }

    @Test
    void undoAndRedoShortcuts() {
        t.focus();
        fx.type("ab cd");
        fx.key("z", SHORTCUT);
        assertEquals("hiab", t.value());
        fx.key("z", SHORTCUT);
        assertEquals("hi", t.value());
        fx.key("z", SHORTCUT_SHIFT);
        assertEquals("hiab", t.value());
        fx.key("y", SHORTCUT);
        assertEquals("hiab cd", t.value());
    }

    @Test
    void scriptValueChangesResetTheModel() {
        t.focus();
        fx.type("x");
        t.setValue("new");
        fx.type("!");
        assertEquals("new!", t.value());
        fx.key("z", SHORTCUT);
        fx.key("z", SHORTCUT);
        assertEquals("new", t.value(), "history before the script's value is gone");
    }

    @Test
    void readonlyAllowsSelectionAndCopyButNoEdits() {
        Element ro = fx.el("ro");
        ro.focus();
        fx.type("x");
        fx.key("Backspace");
        assertEquals("abc", ro.value());
        fx.key("a", SHORTCUT);
        fx.key("c", SHORTCUT);
        assertEquals("abc", fx.host.clipboard);
        assertFalse(fx.input.wantsKeyboard());
    }

    @Test
    void numberAndMaxlengthFilters() {
        fx.el("num").focus();
        fx.type("1a2.5e");
        assertEquals("12.5e", fx.el("num").value());
        fx.el("max").focus();
        fx.type("abcdef");
        assertEquals("abc", fx.el("max").value());
    }

    @Test
    void wantsKeyboardOnlyForEditableTextFields() {
        assertFalse(fx.input.wantsKeyboard());
        t.focus();
        assertTrue(fx.input.wantsKeyboard());
        fx.el("cb").focus();
        assertFalse(fx.input.wantsKeyboard());
        fx.el("ta").focus();
        assertTrue(fx.input.wantsKeyboard());
    }

    @Test
    void tabIntoAnInputSelectsItsText() {
        fx.box(t, null, 0, 0, 50, 12);
        fx.key("Tab");
        assertTrue(t.isFocused());
        assertEquals("hi", editor(t).selectedText());
    }

    // ---- Mouse ----

    /** "hello" at content x 13: caret positions 13, 19, 25, 28, 31, 37. */
    private Element hello() {
        t.setValue("hello");
        Box b = fx.box(t, null, 10, 10, 100, 20);
        b.borderLeft = 1;
        b.paddingLeft = 2;
        return t;
    }

    @Test
    void clickFocusesAndPlacesTheCaret() {
        hello();
        fx.click(26, 15);
        assertTrue(t.isFocused());
        assertEquals(2, editor(t).caret(), "26 is nearer 25 than 28");
        fx.time(1000); // not a double-click
        fx.click(29, 15);
        assertEquals(3, editor(t).caret());
        fx.click(200, 15);
        assertEquals(3, editor(t).caret(), "outside the box: nothing hit");
        fx.click(100, 15);
        assertEquals(5, editor(t).caret(), "past the text: the end");
        assertFalse(fx.input.focusVisible());
    }

    @Test
    void doubleClickSelectsAWordAndTripleClickEverything() {
        t.setValue("hello world");
        fx.box(t, null, 0, 0, 200, 20);
        fx.click(5, 5);
        fx.down(5, 5);
        assertEquals("hello", editor(t).selectedText());
        fx.input.tick(16);
        assertEquals("hello", editor(t).selectedText(), "the drag re-sent at rest does not shrink the selection");
        fx.up(5, 5);
        fx.click(5, 5);
        assertEquals("hello world", editor(t).selectedText());
    }

    @Test
    void dragAndShiftClickSelect() {
        hello();
        fx.down(13, 15);
        fx.move(33, 15);
        fx.up(33, 15);
        assertEquals("hell", editor(t).selectedText());
        fx.click(19, 15);
        fx.input.mouseDown(31, 15, 0, SHIFT);
        fx.input.mouseUp(31, 15, 0, SHIFT);
        assertEquals("ell", editor(t).selectedText());
    }

    @Test
    void caretStaysVisibleByScrollingHorizontally() {
        t.setValue("");
        fx.box(t, null, 0, 0, 20, 12);
        t.focus();
        fx.type("aaaaaaaa");
        TextField field = TextField.of(t);
        assertEquals(48 + 1 - 20, field.scrollX(), "caret at 48 shown at the right edge");
        fx.key("Home");
        assertEquals(0, field.scrollX());
        t.blur();
        fx.key("End");
        assertEquals(0, field.scrollX(), "unfocused fields rest at the start");
    }

    // ---- Textarea ----

    @Test
    void textareaWrapsAndMovesByVisualLines() {
        Element ta = fx.el("ta");
        fx.box(ta, null, 0, 0, 30, 18);
        ta.focus();
        TextField field = TextField.of(ta);
        TextLayout layout = field.layout();
        assertEquals(3, layout.lineCount());
        assertEquals("aaa ", layout.line(0).display());
        assertEquals("bbb ", layout.line(1).display());
        assertEquals("ccc", layout.line(2).display());
        assertEquals(11, field.editor.caret());
        fx.key("ArrowUp");
        assertEquals(7, field.editor.caret(), "x 18 on 'bbb ' is before its hanging space");
        fx.key("ArrowUp");
        assertEquals(3, field.editor.caret());
        fx.key("ArrowDown");
        assertEquals(7, field.editor.caret(), "the goal x survives consecutive vertical moves");
        fx.key("Home");
        assertEquals(4, field.editor.caret());
        fx.key("Enter");
        assertEquals("aaa \nbbb ccc", ta.value());
        assertEquals(0, field.scrollY());
        fx.key("End", SHORTCUT);
        assertEquals(9, field.scrollY(), "3 lines of 9px in 18px: scrolled to show the caret's line");
    }

    @Test
    void textareaScrollsWithTheWheelThenChains() {
        Element ta = fx.el("ta");
        fx.box(ta, null, 0, 0, 30, 18);
        TextField field = TextField.of(ta);
        assertTrue(fx.input.wheel(5, 5, 0, 5, NONE));
        assertEquals(5, field.scrollY());
        assertTrue(fx.input.wheel(5, 5, 0, 20, NONE));
        assertEquals(9, field.scrollY());
        assertFalse(fx.input.wheel(5, 5, 0, 5, NONE), "at the bottom and nothing outside scrolls");
    }

    @Test
    void caretBlinks() {
        t.focus();
        TextField field = TextField.of(t);
        fx.input.tick(100);
        assertTrue(field.caretOn());
        fx.input.tick(600);
        assertFalse(field.caretOn());
        fx.input.tick(1100);
        assertTrue(field.caretOn());
        fx.time(1700);
        fx.input.tick(1700);
        assertFalse(field.caretOn());
        fx.type("x");
        assertTrue(field.caretOn(), "typing restarts the blink");
        fx.input.tick(1800);
        assertTrue(field.caretOn());
    }
}
