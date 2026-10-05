package dev.vellum.engine.input;

import dev.vellum.engine.host.FontSpec;
import dev.vellum.engine.layout.SpacedText;
import dev.vellum.engine.layout.TextMeasure;
import dev.vellum.engine.style.ComputedStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * The visual lines of a text control's value, measured like any text ({@link TextMeasure}: the host's advances plus
 * letter- and word-spacing): one unwrapped line for inputs, paragraphs soft-wrapped at spaces (or anywhere, for words
 * longer than the width) for textareas. Offsets are model offsets into the value; for masked (password) text each
 * code point is drawn as a bullet.
 */
final class TextLayout {
    static final String BULLET = "•";

    /**
     * One visual line: model offsets {@code [start, end)}, the text to draw ({@code spaced} parts when spacing
     * applies, else null), and {@code xs[i]}, the caret x of offset {@code start + i} relative to the line's left
     * edge. {@code softWrapped} lines continue on the next line.
     */
    record Line(int start, int end, String display, SpacedText spaced, float[] xs, boolean softWrapped) {
        float width() { return xs[xs.length - 1]; }

        /** Caret x of {@code offset}, clamped to this line. */
        float x(int offset) { return xs[Math.max(0, Math.min(offset, end) - start)]; }
    }

    private final String text;
    private final boolean masked;
    private final float wrapWidth, letterSpacing, wordSpacing;
    private final FontSpec font;
    private final List<Line> lines = new ArrayList<>();

    /**
     * @param wrapWidth the width to wrap paragraphs at, or {@code Float.POSITIVE_INFINITY} for a single line (newlines
     *                  are then drawn as-is)
     */
    TextLayout(String text, boolean masked, float wrapWidth, TextMeasure measure, FontSpec font, ComputedStyle s) {
        this.text = text;
        this.masked = masked;
        this.wrapWidth = wrapWidth;
        this.font = font;
        this.letterSpacing = s.letterSpacing;
        this.wordSpacing = s.wordSpacing;
        boolean multiline = wrapWidth != Float.POSITIVE_INFINITY;
        int start = 0;
        while (true) {
            int nl = multiline ? text.indexOf('\n', start) : -1;
            int end = nl < 0 ? text.length() : nl;
            wrap(start, end, measure, s);
            if (nl < 0) break;
            start = nl + 1;
        }
    }

    /** True when this layout was built from the same inputs (so it can be reused). */
    boolean matches(String text, boolean masked, float wrapWidth, FontSpec font, ComputedStyle s) {
        return this.text.equals(text) && this.masked == masked && this.wrapWidth == wrapWidth && this.font.equals(font)
                && letterSpacing == s.letterSpacing && wordSpacing == s.wordSpacing;
    }

    int lineCount() { return lines.size(); }
    Line line(int i) { return lines.get(i); }

    float width() {
        float w = 0;
        for (Line l : lines) w = Math.max(w, l.width());
        return w;
    }

    /** The line showing the caret at {@code offset}. At a soft wrap the caret belongs to the start of the next line. */
    int lineOf(int offset) {
        int i = lines.size() - 1;
        while (i > 0 && lines.get(i).start > offset) i--;
        return i;
    }

    /** Caret x of {@code offset} relative to its line's left edge. */
    float x(int offset) {
        return lines.get(lineOf(offset)).x(offset);
    }

    /** The caret offset nearest to {@code x} on {@code line}. */
    int offsetAt(int line, float x) {
        Line l = lines.get(Math.max(0, Math.min(lines.size() - 1, line)));
        int last = caretEnd(l);
        for (int o = l.start; o < last; o = next(o)) {
            int n = next(o);
            if (x < (l.xs[o - l.start] + l.xs[n - l.start]) / 2) return o;
        }
        return last;
    }

    /**
     * The last caret position on a line (End key). On a line soft-wrapped after a space it sits before that space,
     * since the offset after it is drawn at the start of the next line.
     */
    int caretEnd(Line l) {
        return l.softWrapped && l.end > l.start && Character.isWhitespace(text.charAt(l.end - 1)) ? l.end - 1 : l.end;
    }

    private int next(int offset) {
        return offset < text.length() && Character.isHighSurrogate(text.charAt(offset)) ? offset + 2 : offset + 1;
    }

    /** Greedy wrapping of the paragraph {@code [start, end)}: break after spaces, or mid-word when a word overflows. */
    private void wrap(int start, int end, TextMeasure measure, ComputedStyle s) {
        float[] pos = new float[end - start + 1];
        float bullet = masked ? measure.advance(BULLET.codePointAt(0), font, s) : 0;
        for (int i = start; i < end; ) {
            int cp = text.codePointAt(i);
            int n = Character.charCount(cp);
            float w = masked ? bullet : measure.advance(cp, font, s);
            if (n == 2) pos[i + 1 - start] = pos[i - start];
            pos[i + n - start] = pos[i - start] + w;
            i += n;
        }
        int lineStart = start, breakAt = -1;
        for (int i = start; i < end; ) {
            int cp = text.codePointAt(i);
            int n = Character.charCount(cp);
            boolean space = Character.isWhitespace(cp);
            // Spaces may hang past the edge; anything else that overflows a non-empty line moves to the next one.
            if (!space && i > lineStart && pos[i + n - start] - pos[lineStart - start] > wrapWidth) {
                int at = breakAt > lineStart ? breakAt : i;
                addLine(lineStart, at, pos, start, true, measure, s);
                lineStart = at;
                breakAt = -1;
                continue;
            }
            if (space) breakAt = i + n;
            i += n;
        }
        addLine(lineStart, end, pos, start, false, measure, s);
    }

    private void addLine(int from, int to, float[] pos, int paragraphStart, boolean softWrapped, TextMeasure measure,
                         ComputedStyle s) {
        float[] xs = new float[to - from + 1];
        for (int i = 0; i < xs.length; i++) xs[i] = pos[from + i - paragraphStart] - pos[from - paragraphStart];
        String display = masked ? BULLET.repeat(text.codePointCount(from, to)) : text.substring(from, to);
        lines.add(new Line(from, to, display, measure.spaced(display, font, s), xs, softWrapped));
    }
}
