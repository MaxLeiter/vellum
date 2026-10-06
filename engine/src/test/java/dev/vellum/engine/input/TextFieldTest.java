package dev.vellum.engine.input;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.event.InputEvent;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.vellum.engine.testing.Page.ALT;
import static dev.vellum.engine.testing.Page.NONE;
import static dev.vellum.engine.testing.Page.SHIFT;
import static dev.vellum.engine.testing.Page.SHORTCUT;
import static dev.vellum.engine.testing.Page.SHORTCUT_SHIFT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextFieldTest {
    private final Page page = new TestHost().load("""
            <input id=t value=hi><input id=pw type=password value=secret><input id=ro value=abc readonly>
            <input id=num type=number><input id=max maxlength=3><input id=cb type=checkbox>
            <textarea id=ta>aaa bbb ccc</textarea>""");
    private final Element t = page.byId("t");

    private TextEditor editor(Element e) {
        return TextField.of(e).editor;
    }

    /** Lays {@code e} out alone at the top left of the page, styled by {@code css}. */
    private void place(Element e, String css) {
        e.setAttribute("style", "position: absolute; left: 0; top: 0; scroll-behavior: auto; " + css);
        page.frame();
    }

    @Test
    void typingFiresBeforeinputThenInput() {
        page.listen(t, "beforeinput", "input", "change");
        t.focus();
        page.type("ab");
        assertEquals("hiab", t.value());
        assertEquals(List.of("beforeinput:t=a", "input:t=a", "beforeinput:t=b", "input:t=b"), page.log);
    }

    @Test
    void cancelledBeforeinputSkipsTheEdit() {
        t.addEventListener("beforeinput", e -> { if ("x".equals(((InputEvent) e).data)) e.preventDefault(); });
        t.focus();
        page.type("axb");
        assertEquals("hiab", t.value());
    }

    @Test
    void cancelledKeydownDropsItsCharacter() {
        t.addEventListener("keydown", e -> e.preventDefault());
        t.focus();
        assertTrue(page.input.keyDown("a", "KeyA", NONE));
        assertTrue(page.input.charTyped("a"));
        assertEquals("hi", t.value());
        assertTrue(page.input.charTyped("é"), "a character without a keydown (IME) still goes in");
        assertEquals("hié", t.value());
    }

    @Test
    void changeFiresOnEnterAndOnBlurOnlyWhenTheValueChanged() {
        page.listen(t, "change");
        t.focus();
        t.blur();
        assertEquals(List.of(), page.log);
        t.focus();
        page.type("!");
        assertTrue(page.key("Enter"));
        assertEquals(List.of("change:t"), page.log);
        t.blur();
        assertEquals(List.of("change:t"), page.log, "already committed by Enter");
        t.focus();
        page.type("?");
        t.blur();
        assertEquals(List.of("change:t", "change:t"), page.log);
    }

    @Test
    void editingKeys() {
        t.focus();
        page.type(" there world");
        assertEquals("hi there world", t.value());
        page.key("Backspace", ALT);
        assertEquals("hi there ", t.value());
        page.key("Home");
        page.key("Delete");
        assertEquals("i there ", t.value());
        page.key("ArrowRight", ALT);
        page.key("End", SHIFT);
        assertEquals(" there ", editor(t).selectedText());
        page.key("Backspace");
        assertEquals("i", t.value());
        assertFalse(page.key("Escape"), "Escape goes to the host");
    }

    @Test
    void clipboardShortcuts() {
        t.focus();
        page.key("a", SHORTCUT);
        page.key("c", SHORTCUT);
        assertEquals("hi", page.host.clipboard);
        page.key("x", SHORTCUT);
        assertEquals("", t.value());
        page.key("v", SHORTCUT);
        page.key("v", SHORTCUT);
        assertEquals("hihi", t.value());
    }

    @Test
    void passwordsAreMaskedAndNeverCopied() {
        Element pw = page.byId("pw");
        pw.focus();
        page.key("a", SHORTCUT);
        page.key("c", SHORTCUT);
        page.key("x", SHORTCUT);
        assertEquals("", page.host.clipboard);
        assertEquals("secret", pw.value(), "cut does nothing either");
        assertEquals("••••••", TextField.of(pw).layout().line(0).display());
    }

    @Test
    void undoAndRedoShortcuts() {
        t.focus();
        page.type("ab cd");
        page.key("z", SHORTCUT);
        assertEquals("hiab", t.value());
        page.key("z", SHORTCUT);
        assertEquals("hi", t.value());
        page.key("z", SHORTCUT_SHIFT);
        assertEquals("hiab", t.value());
        page.key("y", SHORTCUT);
        assertEquals("hiab cd", t.value());
    }

    @Test
    void scriptValueChangesResetTheModel() {
        t.focus();
        page.type("x");
        t.setValue("new");
        page.type("!");
        assertEquals("new!", t.value());
        page.key("z", SHORTCUT);
        page.key("z", SHORTCUT);
        assertEquals("new", t.value(), "history before the script's value is gone");
    }

    @Test
    void readonlyAllowsSelectionAndCopyButNoEdits() {
        Element ro = page.byId("ro");
        ro.focus();
        page.type("x");
        page.key("Backspace");
        assertEquals("abc", ro.value());
        page.key("a", SHORTCUT);
        page.key("c", SHORTCUT);
        assertEquals("abc", page.host.clipboard);
        assertFalse(page.input.wantsKeyboard());
    }

    @Test
    void numberAndMaxlengthFilters() {
        page.byId("num").focus();
        page.type("1a2.5e");
        assertEquals("12.5e", page.byId("num").value());
        page.byId("max").focus();
        page.type("abcdef");
        assertEquals("abc", page.byId("max").value());
    }

    @Test
    void wantsKeyboardOnlyForEditableTextFields() {
        assertFalse(page.input.wantsKeyboard());
        t.focus();
        assertTrue(page.input.wantsKeyboard());
        page.byId("cb").focus();
        assertFalse(page.input.wantsKeyboard());
        page.byId("ta").focus();
        assertTrue(page.input.wantsKeyboard());
    }

    @Test
    void tabIntoAnInputSelectsItsText() {
        page.key("Tab");
        assertTrue(t.isFocused());
        assertEquals("hi", editor(t).selectedText());
    }

    // ---- Mouse ----

    /** "hello" at content x 13: caret positions 13, 19, 25, 28, 31, 37. */
    private Element hello() {
        t.setValue("hello");
        place(t, "left: 10px; top: 10px; width: 100px; height: 20px; border-left: 1px solid; padding: 0 0 0 2px");
        return t;
    }

    @Test
    void clickFocusesAndPlacesTheCaret() {
        hello();
        page.click(26, 15);
        assertTrue(t.isFocused());
        assertEquals(2, editor(t).caret(), "26 is nearer 25 than 28");
        page.frame(1000); // not a double-click
        page.click(29, 15);
        assertEquals(3, editor(t).caret());
        page.click(200, 15);
        assertEquals(3, editor(t).caret(), "outside the box: nothing hit");
        page.click(100, 15);
        assertEquals(5, editor(t).caret(), "past the text: the end");
        assertFalse(page.input.focusVisible());
    }

    @Test
    void doubleClickSelectsAWordAndTripleClickEverything() {
        t.setValue("hello world");
        place(t, "width: 200px; padding: 0");
        page.click(5, 5);
        page.down(5, 5);
        assertEquals("hello", editor(t).selectedText());
        page.frame();
        assertEquals("hello", editor(t).selectedText(), "the drag re-sent at rest does not shrink the selection");
        page.up(5, 5);
        page.click(5, 5);
        assertEquals("hello world", editor(t).selectedText());
    }

    @Test
    void dragAndShiftClickSelect() {
        hello();
        page.down(13, 15);
        page.move(33, 15);
        page.up(33, 15);
        assertEquals("hell", editor(t).selectedText());
        page.click(19, 15);
        page.input.mouseDown(31, 15, 0, SHIFT);
        page.input.mouseUp(31, 15, 0, SHIFT);
        assertEquals("ell", editor(t).selectedText());
    }

    @Test
    void caretStaysVisibleByScrollingHorizontally() {
        t.setValue("");
        place(t, "width: 20px; height: 12px; padding: 0");
        t.focus();
        page.type("aaaaaaaa");
        assertEquals(48 + 1 - 20, t.scrollLeft(), "caret at 48 shown at the right edge");
        page.key("Home");
        assertEquals(0, t.scrollLeft());
        t.blur();
        page.key("End");
        assertEquals(0, t.scrollLeft(), "unfocused fields rest at the start");
    }

    // ---- Textarea ----

    @Test
    void textareaWrapsAndMovesByVisualLines() {
        Element ta = page.byId("ta");
        place(ta, "width: 30px; height: 18px; padding: 0");
        ta.focus();
        TextField field = TextField.of(ta);
        TextLayout layout = field.layout();
        assertEquals(3, layout.lineCount());
        assertEquals("aaa ", layout.line(0).display());
        assertEquals("bbb ", layout.line(1).display());
        assertEquals("ccc", layout.line(2).display());
        assertEquals(11, field.editor.caret());
        page.key("ArrowUp");
        assertEquals(7, field.editor.caret(), "x 18 on 'bbb ' is before its hanging space");
        page.key("ArrowUp");
        assertEquals(3, field.editor.caret());
        page.key("ArrowDown");
        assertEquals(7, field.editor.caret(), "the goal x survives consecutive vertical moves");
        page.key("Home");
        assertEquals(4, field.editor.caret());
        page.key("Enter");
        assertEquals("aaa \nbbb ccc", ta.value());
        assertEquals(0, ta.scrollTop());
        page.key("End", SHORTCUT);
        assertEquals(9, ta.scrollTop(), "3 lines of 9px in 18px: scrolled to show the caret's line");
    }

    @Test
    void textareaScrollsWithTheWheelThenChains() {
        Element ta = page.byId("ta");
        place(ta, "width: 30px; height: 18px; padding: 0");
        assertTrue(page.input.wheel(5, 5, 0, 5, NONE));
        assertEquals(5, ta.scrollTop());
        assertTrue(page.input.wheel(5, 5, 0, 20, NONE));
        assertEquals(9, ta.scrollTop());
        assertFalse(page.input.wheel(5, 5, 0, 5, NONE), "at the bottom and nothing outside scrolls");
    }

    @Test
    void caretBlinks() {
        t.focus();
        TextField field = TextField.of(t);
        page.frame(100);
        assertTrue(field.caretOn());
        page.frame(600);
        assertFalse(field.caretOn());
        page.frame(1100);
        assertTrue(field.caretOn());
        page.frame(1700);
        assertFalse(field.caretOn());
        page.type("x");
        assertTrue(field.caretOn(), "typing restarts the blink");
        page.frame(1800);
        assertTrue(field.caretOn());
    }

    @Test
    void enterSubmitsTheFormAsInBrowsers() {
        Page p = new TestHost().load("""
                <form id=a><input id=a1><input id=a2><button id=ab type=button>no</button><button id=go>go</button></form>
                <form id=b><input id=b1></form>
                <form id=c><input id=c1><input id=c2></form>
                <form id=d><input id=d1><button disabled>go</button></form>
                <form id=e><textarea id=e1></textarea></form>""");
        for (String id : List.of("a", "b", "c", "d", "e")) p.listen(p.byId(id), "submit");
        p.listen(p.byId("go"), "click");
        for (String id : List.of("a2", "b1", "c1", "d1", "e1")) {
            p.byId(id).focus();
            p.input.keyDown("Enter", "Enter", NONE);
        }
        assertEquals(List.of("click:go", "submit:a", "submit:b"), p.log,
                "the first submit button is clicked; a form without one submits when it has a single field");
    }
}
