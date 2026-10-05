package dev.vellum.engine.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextEditorTest {
    private static TextEditor editor(String text) {
        TextEditor e = new TextEditor(false);
        e.reset(text);
        return e;
    }

    private static void type(TextEditor e, String text) {
        text.codePoints().forEach(cp -> e.apply(e.planInsert(new String(Character.toChars(cp)), "insertText")));
    }

    @Test
    void insertReplacesSelectionAndMovesCaret() {
        TextEditor e = editor("hello world");
        e.select(6, 11);
        TextEditor.Edit edit = e.planInsert("there", "insertText");
        assertEquals(new TextEditor.Edit(6, 11, "there", "insertText"), edit);
        assertEquals("there", edit.data());
        e.apply(edit);
        assertEquals("hello there", e.text());
        assertEquals(11, e.caret());
        assertFalse(e.hasSelection());
    }

    @Test
    void resetPutsCaretAtEndAndClearsHistory() {
        TextEditor e = editor("ab");
        type(e, "c");
        e.reset("xyz");
        assertEquals(3, e.caret());
        assertFalse(e.canUndo());
    }

    @Test
    void backspaceAndDeleteByCharacter() {
        TextEditor e = editor("abc");
        e.moveTo(1, false);
        e.apply(e.planDelete(false, false));
        assertEquals("bc", e.text());
        assertEquals(0, e.caret());
        assertNull(e.planDelete(false, false), "nothing before the caret");
        e.apply(e.planDelete(true, false));
        assertEquals("c", e.text());
        e.moveTo(1, false);
        assertNull(e.planDelete(true, false), "nothing after the caret");
    }

    @Test
    void deleteTypesAndData() {
        TextEditor e = editor("abc def");
        assertEquals("deleteContentBackward", e.planDelete(false, false).inputType());
        assertEquals("deleteWordBackward", e.planDelete(false, true).inputType());
        e.moveTo(0, false);
        assertEquals("deleteContentForward", e.planDelete(true, false).inputType());
        assertEquals("deleteWordForward", e.planDelete(true, true).inputType());
        assertNull(e.planDelete(true, true).data());
    }

    @Test
    void wordDeletion() {
        TextEditor e = editor("foo bar  baz");
        e.apply(e.planDelete(false, true));
        assertEquals("foo bar  ", e.text());
        e.apply(e.planDelete(false, true));
        assertEquals("foo ", e.text(), "skips the spaces, then the word");
        e.moveTo(0, false);
        e.apply(e.planDelete(true, true));
        assertEquals(" ", e.text());
    }

    @Test
    void selectionIsDeletedAsAWhole() {
        TextEditor e = editor("abcdef");
        e.select(4, 1);
        assertEquals(1, e.selectionStart());
        assertEquals(4, e.selectionEnd());
        assertEquals("bcd", e.selectedText());
        e.apply(e.planDelete(true, true));
        assertEquals("aef", e.text());
        assertEquals(1, e.caret());
    }

    @Test
    void horizontalMovementCollapsesOrExtendsSelection() {
        TextEditor e = editor("abcdef");
        e.select(2, 4);
        e.moveHorizontally(false, false, false);
        assertEquals(2, e.caret());
        assertFalse(e.hasSelection());
        e.select(2, 4);
        e.moveHorizontally(true, false, false);
        assertEquals(4, e.caret());
        e.moveHorizontally(true, false, true);
        e.moveHorizontally(true, false, true);
        assertEquals(4, e.anchor());
        assertEquals(6, e.caret());
        e.moveHorizontally(true, false, true);
        assertEquals(6, e.caret(), "clamped at the end");
    }

    @Test
    void wordMovementSkipsPunctuationAndSpaces() {
        TextEditor e = editor("one, two_2  three");
        e.moveTo(0, false);
        e.moveHorizontally(true, true, false);
        assertEquals(3, e.caret());
        e.moveHorizontally(true, true, false);
        assertEquals(10, e.caret(), "underscores and digits are word characters");
        e.moveHorizontally(true, true, false);
        assertEquals(17, e.caret());
        e.moveHorizontally(false, true, false);
        assertEquals(12, e.caret());
        e.moveHorizontally(false, true, true);
        assertEquals(5, e.caret());
        assertEquals(12, e.anchor());
    }

    @Test
    void wordAtSelectsWordsSpacesOrSingleCharacters() {
        TextEditor e = editor("foo  bar.baz");
        assertArrayEquals(new int[] {0, 3}, e.wordAt(1));
        assertArrayEquals(new int[] {3, 5}, e.wordAt(3));
        assertArrayEquals(new int[] {8, 9}, e.wordAt(8));
        assertArrayEquals(new int[] {9, 12}, e.wordAt(12), "at the end: the last word");
        assertArrayEquals(new int[] {0, 0}, editor("").wordAt(0));
    }

    @Test
    void hardLineBoundaries() {
        TextEditor e = new TextEditor(true);
        e.reset("ab\ncd\n");
        assertEquals(3, e.lineStart(4));
        assertEquals(5, e.lineEnd(4));
        assertEquals(0, e.lineStart(2));
        assertEquals(2, e.lineEnd(0));
        assertEquals(6, e.lineStart(6));
        assertEquals(6, e.lineEnd(6));
    }

    @Test
    void typingCoalescesIntoOneUndoStepPerWord() {
        TextEditor e = editor("");
        type(e, "hello world");
        assertTrue(e.undo());
        assertEquals("hello", e.text());
        assertEquals(5, e.caret());
        assertTrue(e.undo());
        assertEquals("", e.text());
        assertFalse(e.undo());
        assertTrue(e.redo());
        assertEquals("hello", e.text());
        assertTrue(e.redo());
        assertEquals("hello world", e.text());
        assertFalse(e.redo());
    }

    @Test
    void caretMovesAndTypeChangesStartNewUndoSteps() {
        TextEditor e = editor("");
        type(e, "abc");
        e.moveTo(1, false);
        type(e, "X");
        e.apply(e.planDelete(false, false));
        e.apply(e.planDelete(false, false));
        assertEquals("bc", e.text());
        e.undo();
        assertEquals("aXbc", e.text(), "the two backspaces were one step");
        e.undo();
        assertEquals("abc", e.text());
        e.undo();
        assertEquals("", e.text());
    }

    @Test
    void undoRestoresSelectionAndNewEditClearsRedo() {
        TextEditor e = editor("abc");
        e.select(0, 3);
        e.apply(e.planInsert("x", "insertFromPaste"));
        e.undo();
        assertEquals("abc", e.text());
        assertEquals(0, e.selectionStart());
        assertEquals(3, e.selectionEnd());
        e.apply(e.planInsert("y", "insertText"));
        assertFalse(e.canRedo());
    }

    @Test
    void pasteIsAlwaysItsOwnUndoStep() {
        TextEditor e = editor("");
        e.apply(e.planInsert("a", "insertFromPaste"));
        e.apply(e.planInsert("b", "insertFromPaste"));
        e.undo();
        assertEquals("a", e.text());
    }

    @Test
    void maxLengthTruncatesInsertedText() {
        TextEditor e = editor("abc");
        e.setMaxLength(5);
        e.apply(e.planInsert("defg", "insertFromPaste"));
        assertEquals("abcde", e.text());
        assertNull(e.planInsert("x", "insertText"), "full");
        e.select(0, 2);
        e.apply(e.planInsert("xyz", "insertText"));
        assertEquals("xycde", e.text(), "replacing a selection frees its room");
    }

    @Test
    void maxLengthNeverSplitsASurrogatePair() {
        TextEditor e = editor("ab");
        e.setMaxLength(3);
        assertNull(e.planInsert("😀", "insertText"), "the emoji needs two units but only one is free");
        e.setMaxLength(4);
        e.apply(e.planInsert("😀x", "insertText"));
        assertEquals("ab😀", e.text());
    }

    @Test
    void filterDropsDisallowedCharacters() {
        TextEditor e = editor("");
        e.setFilter(cp -> Character.isDigit(cp) || cp == '-');
        e.apply(e.planInsert("-1a2b", "insertFromPaste"));
        assertEquals("-12", e.text());
        assertNull(e.planInsert("abc", "insertText"));
    }

    @Test
    void singleLineStripsLineBreaksAndControlCharacters() {
        TextEditor e = editor("");
        e.apply(e.planInsert("a\r\nb\nc\u0007\td", "insertFromPaste"));
        assertEquals("abc\td", e.text());
    }

    @Test
    void multilineNormalisesLineBreaks() {
        TextEditor e = new TextEditor(true);
        e.apply(e.planInsert("a\r\nb\rc\n", "insertFromPaste"));
        assertEquals("a\nb\nc\n", e.text());
    }

    @Test
    void surrogatePairsAreSingleCharacters() {
        TextEditor e = editor("a😀b");
        e.moveTo(1, false);
        e.moveHorizontally(true, false, false);
        assertEquals(3, e.caret(), "steps over both halves");
        e.moveTo(2, false);
        assertEquals(1, e.caret(), "never lands inside a pair");
        e.moveTo(3, false);
        e.apply(e.planDelete(false, false));
        assertEquals("ab", e.text());
        e.reset("😀😀");
        e.moveTo(2, false);
        e.apply(e.planDelete(true, false));
        assertEquals("😀", e.text());
        assertArrayEquals(new int[] {0, 2}, editor("😀 x").wordAt(1));
    }

    @Test
    void selectAll() {
        TextEditor e = editor("abc");
        e.selectAll();
        assertEquals("abc", e.selectedText());
        assertNull(editor("abc").planDeleteSelection("deleteByCut"));
        assertEquals(new TextEditor.Edit(0, 3, "", "deleteByCut"), e.planDeleteSelection("deleteByCut"));
    }
}
