package dev.vellum.engine.layout;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Text;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Length;
import dev.vellum.engine.style.TextTransform;
import dev.vellum.engine.style.WhiteSpace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The inline-level content of a block container that establishes an inline formatting context, in document order:
 * text after white-space processing and {@code text-transform}, the start and end of inline elements, atomic
 * inlines, forced line breaks and the static-position placeholders of out-of-flow boxes. Built once per pass by
 * {@link BoxTreeBuilder}; {@link InlineLayout} breaks it into lines.
 */
final class InlineContent {
    /** An inline box: an inline element, an inline ::before/::after, or the root inline box of the container. */
    static final class Span {
        final Element element;
        final ComputedStyle style;
        final Span parent;
        /**
         * The span's box: an inline element's {@link Box.Kind#INLINE} box, an inline pseudo-element's
         * {@link Box.Kind#PSEUDO} box; null for the root span.
         */
        final Box box;
        /** Font metrics and baseline shift, computed once per pass by {@link InlineLayout}. */
        InlineLayout.Metrics metrics;

        Span(Element element, ComputedStyle style, Span parent, Box box) {
            this.element = element;
            this.style = style;
            this.parent = parent;
            this.box = box;
        }

        boolean hasHorizontalEdges() {
            ComputedStyle s = style;
            return s.borderLeftWidth > 0 || s.borderRightWidth > 0 || !s.paddingLeft.equals(Length.ZERO)
                    || !s.paddingRight.equals(Length.ZERO) || !s.marginLeft.equals(Length.ZERO)
                    || !s.marginRight.equals(Length.ZERO);
        }
    }

    sealed interface Item {}

    /**
     * Processed text in one span. {@code sourceIndex[i]} is the index in the text node's data of char {@code i}
     * (length {@code text.length() + 1}); null for generated content.
     */
    record TextItem(Span span, Text node, String text, int[] sourceIndex) implements Item {
        /** The source indices of chars {@code [start, end]} (the end included), or null for generated content. */
        int[] sourceSlice(int start, int end) {
            return sourceIndex == null ? null : Arrays.copyOfRange(sourceIndex, start, end + 1);
        }
    }

    record Open(Span span) implements Item {}

    record Close(Span span) implements Item {}

    record Atomic(Span span, LayoutBox box) implements Item {}

    /** A forced line break: {@code <br>} (with its inline box) or a preserved newline (box null). */
    record Break(Span span, Box box) implements Item {}

    /** Where an out-of-flow box would have been: its static position. */
    record Placeholder(LayoutBox box) implements Item {}

    final Span root;
    final List<Item> items = new ArrayList<>();
    /** The items cut into pieces, computed once per pass by {@link InlineLayout}. */
    InlineLayout.Pieces pieces;
    /** True after a collapsible space (or at the start of a line), so the next one collapses away. */
    private boolean afterSpace = true;
    /** True at the start of a word, for {@code text-transform: capitalize}. */
    private boolean wordStart = true;

    InlineContent(Span root) {
        this.root = root;
    }

    void open(Span span) {
        items.add(new Open(span));
    }

    void close(Span span) {
        items.add(new Close(span));
    }

    void atomic(Span span, LayoutBox box) {
        items.add(new Atomic(span, box));
        afterSpace = false;
        wordStart = true;
    }

    void lineBreak(Span span, Box box) {
        items.add(new Break(span, box));
        afterSpace = true;
        wordStart = true;
    }

    void placeholder(LayoutBox box) {
        items.add(new Placeholder(box));
    }

    /**
     * Adds text, applying white-space processing (collapsing spaces and tabs, turning newlines into spaces or
     * forced breaks per {@code white-space}) and {@code text-transform}. Collapsing continues across inline
     * element boundaries, as in CSS.
     */
    void addText(Span span, Text node, String data) {
        WhiteSpace ws = span.style.whiteSpace;
        TextTransform transform = span.style.textTransform;
        boolean collapse = ws.collapsesSpaces();
        StringBuilder out = new StringBuilder(data.length());
        int[] source = new int[data.length() + 1];
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            if (c == '\r') {
                if (i + 1 < data.length() && data.charAt(i + 1) == '\n') continue;
                c = '\n';
            }
            if (c == '\n' && ws.preservesNewlines()) {
                flushText(span, node, out, source, i);
                lineBreak(span, null);
                continue;
            }
            boolean space = c == ' ' || c == '\t' || c == '\n' || c == '\f';
            if (space && collapse) {
                if (afterSpace) continue;
                append(out, source, ' ', i);
                afterSpace = true;
                wordStart = true;
            } else if (c == '\t') {
                // Preserved tabs advance four spaces (a simplified tab-size).
                for (int k = 0; k < 4; k++) append(out, source, ' ', i);
                afterSpace = false;
                wordStart = true;
            } else {
                append(out, source, transform(c, transform), i);
                afterSpace = false;
                if (space) wordStart = true;
                else if (Character.isLetterOrDigit(c)) wordStart = false;
            }
        }
        flushText(span, node, out, source, data.length());
    }

    private char transform(char c, TextTransform t) {
        return switch (t) {
            case NONE -> c;
            case UPPERCASE -> Character.toUpperCase(c);
            case LOWERCASE -> Character.toLowerCase(c);
            case CAPITALIZE -> wordStart && Character.isLetter(c) ? Character.toTitleCase(c) : c;
        };
    }

    private static void append(StringBuilder out, int[] source, char c, int sourceIndex) {
        source[out.length()] = sourceIndex;
        out.append(c);
    }

    /** Emits the text collected so far as an item; {@code end} is the source index just past it. */
    private void flushText(Span span, Text node, StringBuilder out, int[] source, int end) {
        if (out.length() > 0) {
            int[] map = null;
            if (node != null) {
                map = Arrays.copyOf(source, out.length() + 1);
                map[out.length()] = end;
            }
            items.add(new TextItem(span, node, out.toString(), map));
            out.setLength(0);
        }
    }

    /**
     * Whether the content produces at least one line box: text (white space that survived collapsing counts), an
     * atomic inline, a forced break, or an inline box with horizontal padding, border or margin.
     */
    boolean hasContent() {
        for (Item item : items) {
            if (item instanceof TextItem || item instanceof Atomic || item instanceof Break) return true;
            if (item instanceof Open o && o.span.hasHorizontalEdges()) return true;
        }
        return false;
    }

    /** Makes {@code owner} (the box that owns the lines) the parent of the inline elements' boxes. */
    void adopt(Box owner) {
        for (Item item : items) {
            Box b = item instanceof Open o ? o.span.box : item instanceof Break br ? br.box : null;
            if (b != null) b.parent = owner;
        }
    }
}
