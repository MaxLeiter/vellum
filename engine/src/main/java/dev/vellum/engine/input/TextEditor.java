package dev.vellum.engine.input;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * The text model of a text control: the value, the caret and selection, and undo history. It knows nothing about the
 * DOM or fonts, so single-line inputs and textareas share it and it is unit-tested on its own. Offsets are UTF-16
 * indices and never split a surrogate pair.
 *
 * <p>Edits are two-phase so the caller can fire a cancelable {@code beforeinput} in between: a {@code plan*} method
 * describes an edit without applying it (null when there is nothing to do), and {@link #apply} performs it.
 */
public final class TextEditor {
    /** Replaces {@code [start, end)} by {@code text}. {@code inputType} is the DOM InputEvent type. */
    public record Edit(int start, int end, String text, String inputType) {
        /** The {@code data} of the InputEvent: the inserted text, or null for deletions. */
        public String data() {
            return text.isEmpty() ? null : text;
        }
    }

    private record State(String text, int anchor, int caret) {}

    /** Edit types that merge with an immediately preceding edit of the same type into one undo step. */
    private static final Set<String> COALESCING = Set.of("insertText", "deleteContentBackward", "deleteContentForward");
    private static final int HISTORY_LIMIT = 100;

    private final boolean multiline;
    private String text = "";
    private int anchor, caret;
    private int maxLength = -1;
    private IntPredicate allowed = cp -> true;
    private final Deque<State> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    /** Input type of the open undo group, or null when the next edit starts a new one. */
    private String group;

    public TextEditor(boolean multiline) {
        this.multiline = multiline;
    }

    // ---- State ----

    public String text() { return text; }
    public int caret() { return caret; }
    public int anchor() { return anchor; }
    public int selectionStart() { return Math.min(anchor, caret); }
    public int selectionEnd() { return Math.max(anchor, caret); }
    public boolean hasSelection() { return anchor != caret; }
    public String selectedText() { return text.substring(selectionStart(), selectionEnd()); }
    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }

    /** Replaces the whole text (a value set by script), clearing history and putting the caret at the end. */
    public void reset(String newText) {
        text = newText;
        anchor = caret = newText.length();
        undo.clear();
        redo.clear();
        group = null;
    }

    /** Maximum length in UTF-16 units for typed and pasted text ({@code maxlength}); negative for none. */
    public void setMaxLength(int maxLength) { this.maxLength = maxLength; }

    /** Restricts which code points can be typed or pasted (number inputs); null allows everything. */
    public void setFilter(IntPredicate filter) { this.allowed = filter == null ? cp -> true : filter; }

    // ---- Caret and selection ----

    /** Moves the caret, extending the selection from the anchor when {@code extend} is set. */
    public void moveTo(int offset, boolean extend) {
        caret = snap(offset);
        if (!extend) anchor = caret;
        group = null;
    }

    public void select(int anchor, int caret) {
        this.anchor = snap(anchor);
        this.caret = snap(caret);
        group = null;
    }

    public void selectAll() {
        select(0, text.length());
    }

    /**
     * Left/Right: one code point or one word. Without {@code extend} a selection collapses to its start or end
     * instead of moving.
     */
    public void moveHorizontally(boolean forward, boolean word, boolean extend) {
        if (!extend && hasSelection()) {
            moveTo(forward ? selectionEnd() : selectionStart(), false);
            return;
        }
        int target = forward ? (word ? wordEnd(caret) : next(caret)) : (word ? wordStart(caret) : previous(caret));
        moveTo(target, extend);
    }

    /** The offset one code point before {@code offset}. */
    public int previous(int offset) {
        return offset <= 0 ? 0 : text.offsetByCodePoints(offset, -1);
    }

    /** The offset one code point after {@code offset}. */
    public int next(int offset) {
        return offset >= text.length() ? text.length() : text.offsetByCodePoints(offset, 1);
    }

    /** Start of the word at or before {@code offset}, skipping separators first (Ctrl/Alt+Left). */
    public int wordStart(int offset) {
        int i = snap(offset);
        while (i > 0 && !isWordChar(text.codePointBefore(i))) i = previous(i);
        while (i > 0 && isWordChar(text.codePointBefore(i))) i = previous(i);
        return i;
    }

    /** End of the word at or after {@code offset}, skipping separators first (Ctrl/Alt+Right). */
    public int wordEnd(int offset) {
        int i = snap(offset);
        while (i < text.length() && !isWordChar(text.codePointAt(i))) i = next(i);
        while (i < text.length() && isWordChar(text.codePointAt(i))) i = next(i);
        return i;
    }

    /**
     * {start, end} of the run under {@code offset} for a double-click: a word, a run of whitespace, or a single other
     * character.
     */
    public int[] wordAt(int offset) {
        int i = snap(offset);
        if (i == text.length() && i > 0) i = previous(i);
        if (text.isEmpty()) return new int[] {0, 0};
        int cp = text.codePointAt(i);
        IntPredicate same = isWordChar(cp) ? TextEditor::isWordChar
                : Character.isWhitespace(cp) ? Character::isWhitespace : null;
        if (same == null) return new int[] {i, next(i)};
        int start = i, end = i;
        while (start > 0 && same.test(text.codePointBefore(start))) start = previous(start);
        while (end < text.length() && same.test(text.codePointAt(end))) end = next(end);
        return new int[] {start, end};
    }

    /** Start of the hard line (paragraph) containing {@code offset}. */
    public int lineStart(int offset) {
        return text.lastIndexOf('\n', snap(offset) - 1) + 1;
    }

    /** End of the hard line (paragraph) containing {@code offset}, before its newline. */
    public int lineEnd(int offset) {
        int nl = text.indexOf('\n', snap(offset));
        return nl < 0 ? text.length() : nl;
    }

    // ---- Edits ----

    /**
     * Plans replacing the selection with {@code input} (typing, paste, line break). Disallowed characters are dropped
     * (control characters other than tab, newlines in single-line fields, whatever the filter rejects) and the text is
     * truncated to {@code maxlength}. Returns null when nothing would be inserted.
     */
    public Edit planInsert(String input, String inputType) {
        String s = sanitize(input);
        int start = selectionStart(), end = selectionEnd();
        if (maxLength >= 0) {
            int room = Math.max(0, maxLength - (text.length() - (end - start)));
            if (s.length() > room) {
                // Never keep the high half of a pair whose low half does not fit.
                s = s.substring(0, room > 0 && Character.isHighSurrogate(s.charAt(room - 1)) ? room - 1 : room);
            }
        }
        return s.isEmpty() ? null : new Edit(start, end, s, inputType);
    }

    /**
     * Plans Backspace ({@code forward} false) or Delete, by code point or by word. A selection is deleted as a whole.
     * Returns null when there is nothing to delete.
     */
    public Edit planDelete(boolean forward, boolean word) {
        String type = (word ? "deleteWord" : "deleteContent") + (forward ? "Forward" : "Backward");
        if (hasSelection()) return new Edit(selectionStart(), selectionEnd(), "", type);
        if (forward) return caret == text.length() ? null : new Edit(caret, word ? wordEnd(caret) : next(caret), "", type);
        return caret == 0 ? null : new Edit(word ? wordStart(caret) : previous(caret), caret, "", type);
    }

    /** Plans deleting the selection (cut). Returns null when nothing is selected. */
    public Edit planDeleteSelection(String inputType) {
        return hasSelection() ? new Edit(selectionStart(), selectionEnd(), "", inputType) : null;
    }

    /**
     * Applies an edit and puts the caret after the inserted text. Consecutive typing or deleting merges into one undo
     * step until the caret moves, the edit type changes, or a word starts (typing whitespace starts a new step).
     */
    public void apply(Edit edit) {
        boolean merge = edit.inputType().equals(group)
                && !(edit.text().length() > 0 && Character.isWhitespace(edit.text().codePointAt(0)));
        if (!merge) {
            push(undo, current());
            redo.clear();
        }
        text = text.substring(0, edit.start()) + edit.text() + text.substring(edit.end());
        anchor = caret = edit.start() + edit.text().length();
        group = COALESCING.contains(edit.inputType()) ? edit.inputType() : null;
    }

    /** Restores the state before the last undo step. Returns false when there is nothing to undo. */
    public boolean undo() {
        return travel(undo, redo);
    }

    /** Re-applies the last undone step. Returns false when there is nothing to redo. */
    public boolean redo() {
        return travel(redo, undo);
    }

    private boolean travel(Deque<State> from, Deque<State> to) {
        if (from.isEmpty()) return false;
        push(to, current());
        State s = from.pop();
        text = s.text;
        anchor = s.anchor;
        caret = s.caret;
        group = null;
        return true;
    }

    private State current() {
        return new State(text, anchor, caret);
    }

    private static void push(Deque<State> stack, State s) {
        stack.push(s);
        if (stack.size() > HISTORY_LIMIT) stack.removeLast();
    }

    // ---- Helpers ----

    private String sanitize(String input) {
        if (multiline) input = input.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder sb = new StringBuilder(input.length());
        input.codePoints()
                .filter(cp -> (cp == '\n' ? multiline : cp == '\t' || !Character.isISOControl(cp)) && allowed.test(cp))
                .forEach(sb::appendCodePoint);
        return sb.toString();
    }

    /** Clamps into the text and moves off the low half of a surrogate pair. */
    private int snap(int offset) {
        int o = Math.max(0, Math.min(text.length(), offset));
        boolean insidePair = o > 0 && o < text.length()
                && Character.isLowSurrogate(text.charAt(o)) && Character.isHighSurrogate(text.charAt(o - 1));
        return insidePair ? o - 1 : o;
    }

    private static boolean isWordChar(int cp) {
        return Character.isLetterOrDigit(cp) || cp == '_';
    }
}
