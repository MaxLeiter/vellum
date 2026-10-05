package dev.vellum.engine.layout;

import dev.vellum.engine.host.FontSpec;
import dev.vellum.engine.layout.InlineContent.Atomic;
import dev.vellum.engine.layout.InlineContent.Break;
import dev.vellum.engine.layout.InlineContent.Close;
import dev.vellum.engine.layout.InlineContent.Item;
import dev.vellum.engine.layout.InlineContent.Open;
import dev.vellum.engine.layout.InlineContent.Placeholder;
import dev.vellum.engine.layout.InlineContent.Span;
import dev.vellum.engine.layout.InlineContent.TextItem;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.TextAlign;
import dev.vellum.engine.style.TextOverflow;
import dev.vellum.engine.style.VerticalAlign;
import dev.vellum.engine.style.WordBreak;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Inline formatting (CSS Text 3, CSS 2.1 §10.8): breaks {@link InlineContent} into line boxes and fragments.
 *
 * <p>The content is first cut into {@link Pieces}: words, spaces, inline box edges, atomics and breaks, each with a
 * width and the soft wrap opportunities between them. Lines are filled greedily ({@link #breakLines}), which also
 * yields the min-content (break everywhere) and max-content (break nowhere) widths. Each line is then aligned
 * vertically (half-leading from {@code line-height}, {@code vertical-align}) and horizontally ({@code text-align},
 * justification) and written as fragments ({@link LineWriter}), with {@code text-overflow: ellipsis} and
 * {@code line-clamp} applied.
 */
final class InlineLayout {
    private static final float EPSILON = 0.01f;
    private static final String ELLIPSIS = "…";
    private static final float[] NO_OFFSET = {0, 0};
    static final byte TEXT = 0, SPACE = 1, OPEN = 2, CLOSE = 3, ATOMIC = 4, BREAK = 5, PLACEHOLDER = 6;

    private final LayoutPass pass;

    InlineLayout(LayoutPass pass) {
        this.pass = pass;
    }

    // ---- Pieces ----

    /** The content cut into unbreakable pieces, as parallel arrays. Width-independent, so computed once per pass. */
    static final class Pieces {
        int size;
        byte[] kind = new byte[16];
        int[] item = new int[16], start = new int[16], end = new int[16];
        /** Text widths (words and spaces); the other kinds are sized per layout. */
        float[] width = new float[16];
        /** A soft wrap opportunity before the piece, and an emergency one (overflow-wrap: break-word). */
        boolean[] breakBefore = new boolean[16], emergencyBefore = new boolean[16];
        /** Spaces that collapse, and so vanish at the start and end of a line. */
        boolean[] collapsible = new boolean[16];

        int add(byte k, int itemIndex, int s, int e, float w) {
            if (size == kind.length) {
                int n = size * 2;
                kind = Arrays.copyOf(kind, n);
                item = Arrays.copyOf(item, n);
                start = Arrays.copyOf(start, n);
                end = Arrays.copyOf(end, n);
                width = Arrays.copyOf(width, n);
                breakBefore = Arrays.copyOf(breakBefore, n);
                emergencyBefore = Arrays.copyOf(emergencyBefore, n);
                collapsible = Arrays.copyOf(collapsible, n);
            }
            kind[size] = k;
            item[size] = itemIndex;
            start[size] = s;
            end[size] = e;
            width[size] = w;
            return size++;
        }

        /** Marks a soft wrap opportunity after piece {@code i}; closing edges stay with the content before it. */
        void breakAfter(int i) {
            int j = i + 1;
            while (j < size && kind[j] == CLOSE) j++;
            if (j < size) breakBefore[j] = true;
        }

        /** Marks a soft wrap opportunity before piece {@code i}; opening edges move with the content after it. */
        void breakBeforePiece(int i) {
            int j = i;
            while (j > 0 && kind[j - 1] == OPEN) j--;
            if (j > 0) breakBefore[j] = true;
        }

        boolean isStrippable(int i) {
            return kind[i] == SPACE && collapsible[i];
        }
    }

    private Pieces pieces(InlineContent content) {
        if (content.pieces == null) content.pieces = shape(content);
        return content.pieces;
    }

    private Pieces shape(InlineContent content) {
        Pieces p = new Pieces();
        List<Item> items = content.items;
        for (int i = 0; i < items.size(); i++) {
            switch (items.get(i)) {
                case TextItem t -> shapeText(p, i, t);
                case Open o -> p.add(OPEN, i, 0, 0, 0);
                case Close c -> p.add(CLOSE, i, 0, 0, 0);
                case Atomic a -> p.add(ATOMIC, i, 0, 0, 0);
                case Break b -> p.add(BREAK, i, 0, 0, 0);
                case Placeholder ph -> p.add(PLACEHOLDER, i, 0, 0, 0);
            }
        }
        // Opportunities after spaces and around atomic inlines (like ideographs), now that the following pieces exist.
        for (int i = 0; i < p.size; i++) {
            Item item = items.get(p.item[i]);
            if (p.kind[i] == SPACE && ((TextItem) item).span().style.whiteSpace.wraps()) {
                p.breakAfter(i);
            } else if (item instanceof Atomic a && a.span().style.whiteSpace.wraps()) {
                p.breakBeforePiece(i);
                p.breakAfter(i);
            }
        }
        return p;
    }

    /** Cuts a text item into words and spaces, with break opportunities inside words where the style allows. */
    private void shapeText(Pieces p, int itemIndex, TextItem t) {
        ComputedStyle s = t.span().style;
        FontSpec font = metrics(t.span()).font;
        String text = t.text();
        boolean wraps = s.whiteSpace.wraps();
        boolean breakAll = wraps && s.wordBreak == WordBreak.BREAK_ALL;
        boolean breakWord = wraps && s.wordBreak == WordBreak.BREAK_WORD;
        int i = 0, n = text.length();
        while (i < n) {
            int j = i;
            if (text.charAt(i) == ' ') {
                while (j < n && text.charAt(j) == ' ') j++;
                float w = pass.text.width(" ", font, s) * (j - i);
                // add() may grow the arrays, so index after it returns (p.collapsible[p.add(...)] would write to the
                // array as it was before growing).
                int piece = p.add(SPACE, itemIndex, i, j, w);
                p.collapsible[piece] = s.whiteSpace.collapsesSpaces();
            } else {
                // A word, cut further after zero-width spaces and inner hyphens, and between all characters for
                // break-all (soft opportunities) and break-word (emergency ones).
                while (j < n && text.charAt(j) != ' ') {
                    int cp = text.codePointAt(j);
                    j += Character.charCount(cp);
                    if (breakAll || breakWord || cp == 0x200B
                            || (cp == '-' && j - 1 > i && j < n && Character.isLetter(text.charAt(j)))) break;
                }
                int k = p.add(TEXT, itemIndex, i, j, pass.text.width(text.substring(i, j), font, s));
                if (k > 0 && p.kind[k - 1] == TEXT && p.item[k - 1] == itemIndex) {
                    char prev = text.charAt(i - 1);
                    if (breakAll || (wraps && (prev == '​' || prev == '-'))) p.breakBefore[k] = true;
                    else if (breakWord) p.emergencyBefore[k] = true;
                }
            }
            i = j;
        }
    }

    /** Left margin + border + padding of an inline box (percentages of the containing block width). */
    private static float edgeStart(Span s, float cbWidth) {
        ComputedStyle st = s.style;
        return st.marginLeft.resolve(cbWidth) + Math.max(0, st.borderLeftWidth)
                + BoxModel.nonNegative(st.paddingLeft, cbWidth);
    }

    private static float edgeEnd(Span s, float cbWidth) {
        ComputedStyle st = s.style;
        return BoxModel.nonNegative(st.paddingRight, cbWidth) + Math.max(0, st.borderRightWidth)
                + st.marginRight.resolve(cbWidth);
    }

    // ---- Line breaking ----

    /**
     * One line: pieces {@code [start, end)}. {@code forced} when it ends with a forced break; {@code empty} when it
     * has nothing that makes a line box (only collapsed spaces, empty inline boxes and placeholders).
     */
    record Line(int start, int end, boolean forced, boolean empty) {}

    /**
     * Greedy line filling. A piece that would overflow moves to the next line at the last soft wrap opportunity
     * (or, when {@code emergency} is allowed and there is none, the last overflow-wrap opportunity); spaces never
     * cause a break (they hang). {@code widths} gives every piece's width for this layout.
     */
    static List<Line> breakLines(Pieces p, float[] widths, float available, float indent, boolean emergency) {
        List<Line> lines = new ArrayList<>();
        int i = 0;
        while (i < p.size) {
            int start = i, end = p.size;
            boolean forced = false, content = false;
            int breakAt = -1, emergencyAt = -1;
            float x = lines.isEmpty() ? indent : 0;
            for (int j = start; j < p.size; j++) {
                byte k = p.kind[j];
                if (content && p.breakBefore[j]) breakAt = j;
                if (content && p.emergencyBefore[j]) emergencyAt = j;
                if (k == BREAK) {
                    end = j + 1;
                    while (end < p.size && p.kind[end] == CLOSE) end++;
                    forced = true;
                    break;
                }
                if (!content && p.isStrippable(j)) continue;
                float w = widths[j];
                if (content && k != SPACE && x + w > available + EPSILON) {
                    if (breakAt > start) {
                        end = breakAt;
                        break;
                    }
                    if (emergency && emergencyAt > start) {
                        end = emergencyAt;
                        break;
                    }
                }
                x += w;
                content |= k == TEXT || k == ATOMIC || k == SPACE || (k == OPEN || k == CLOSE) && w != 0;
            }
            lines.add(new Line(start, end, forced, !content && !forced));
            i = end;
        }
        return lines;
    }

    /** Width of a line's content: leading and trailing collapsible spaces do not count. */
    private static float lineWidth(Pieces p, float[] widths, Line line, float indent) {
        int first = firstContent(p, line), last = lastContent(p, line);
        float w = indent;
        for (int j = line.start; j < line.end; j++) {
            if (!p.isStrippable(j) || (j > first && j < last)) w += widths[j];
        }
        return w;
    }

    private static int firstContent(Pieces p, Line line) {
        for (int j = line.start; j < line.end; j++) if (!p.isStrippable(j)) return j;
        return line.end;
    }

    /** The last piece that is not a collapsible space, an end edge, a break or a placeholder. */
    private static int lastContent(Pieces p, Line line) {
        for (int j = line.end - 1; j >= line.start; j--) {
            byte k = p.kind[j];
            if (k != CLOSE && k != PLACEHOLDER && k != BREAK && !p.isStrippable(j)) return j;
        }
        return line.start - 1;
    }

    // ---- Intrinsic widths ----

    /** Sizes an atomic inline (piece {@code j}) for one layout, returning its margin-box width. */
    private interface AtomicSizer {
        float width(int j, LayoutBox atomic);
    }

    /** Every piece's width: text as shaped, inline box edges against {@code cbWidth}, atomics by {@code atomics}. */
    private static float[] pieceWidths(InlineContent content, Pieces p, float cbWidth, AtomicSizer atomics) {
        float[] widths = p.width.clone();
        for (int j = 0; j < p.size; j++) {
            switch (content.items.get(p.item[j])) {
                case Open o -> widths[j] = edgeStart(o.span(), cbWidth);
                case Close c -> widths[j] = edgeEnd(c.span(), cbWidth);
                case Atomic a -> widths[j] = atomics.width(j, a.box());
                default -> { }
            }
        }
        return widths;
    }

    /** Min-content (break at every opportunity) or max-content (only forced breaks) width of the content. */
    float intrinsicWidth(LayoutBox box, boolean max) {
        InlineContent content = box.inline;
        Pieces p = pieces(content);
        float[] widths = pieceWidths(content, p, Float.NaN, (j, atomic) -> pass.contribution(atomic, max));
        float indent = box.style.textIndent;
        List<Line> lines = breakLines(p, widths, max ? Float.POSITIVE_INFINITY : 0, indent, false);
        float width = 0;
        for (int i = 0; i < lines.size(); i++) {
            width = Math.max(width, lineWidth(p, widths, lines.get(i), i == 0 ? indent : 0));
        }
        return width;
    }

    // ---- Layout ----

    /**
     * Lays out (or measures) the box's inline content in a content box {@code contentWidth} wide;
     * {@code percentHeight} is the height percentages of atomic inlines resolve against (NaN if indefinite).
     */
    LayoutResult layout(LayoutBox box, float contentWidth, float percentHeight, boolean measure) {
        InlineContent content = box.inline;
        Pieces p = pieces(content);
        ComputedStyle style = box.style;
        LayoutResult[] atomics = new LayoutResult[p.size];
        float[] widths = pieceWidths(content, p, contentWidth, (j, b) -> {
            // Atomic inlines shrink to fit the line's width.
            BoxModel.resolveEdges(b, contentWidth);
            float available = contentWidth - b.marginLeft - b.marginRight;
            float w = pass.usedWidth(b, available, contentWidth, percentHeight, false);
            atomics[j] = measure ? pass.measure(b, w, Float.NaN, contentWidth, percentHeight)
                    : pass.layout(b, w, Float.NaN, contentWidth, percentHeight);
            return w + b.marginLeft + b.marginRight;
        });

        List<Line> lines = breakLines(p, widths, contentWidth, style.textIndent, true);
        int kept = lines.size();
        int clamp = style.lineClamp;
        LineWriter writer = measure ? null
                : new LineWriter(box, content, p, widths, atomics, contentWidth, percentHeight);
        float y = box.contentY();
        float firstBaseline = Float.NaN, lastBaseline = Float.NaN;
        int written = 0;
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            if (line.empty) {
                if (writer != null) writer.placeholders(line, y);
                continue;
            }
            if (clamp > 0 && written == clamp) {
                kept = i;
                break;
            }
            LineMetrics m = lineMetrics(content, p, atomics, line);
            if (writer != null) {
                boolean clampedEnd = clamp > 0 && written == clamp - 1 && hasLineAfter(lines, i);
                writer.write(line, i == 0 ? style.textIndent : 0, y, m, clampedEnd, isLastLine(lines, i));
            }
            if (written++ == 0) firstBaseline = y + m.baseline;
            lastBaseline = y + m.baseline;
            y += m.height;
        }
        if (writer != null) writer.finish(lines.subList(kept, lines.size()), y);
        return new LayoutResult(y - box.contentY(), firstBaseline, lastBaseline, MarginSet.EMPTY, MarginSet.EMPTY,
                written == 0);
    }

    private static boolean hasLineAfter(List<Line> lines, int i) {
        for (int k = i + 1; k < lines.size(); k++) if (!lines.get(k).empty) return true;
        return false;
    }

    private static boolean isLastLine(List<Line> lines, int i) {
        return !hasLineAfter(lines, i);
    }

    // ---- Vertical alignment ----

    /** Font metrics of an inline box and its baseline offset from the root inline box's baseline (down positive). */
    static final class Metrics {
        FontSpec font;
        float ascent, glyphHeight;
        /** Extent of the line-height box above and below the baseline (half-leading included). */
        float above, below;
        float shift;
    }

    private Metrics metrics(Span span) {
        if (span.metrics != null) return span.metrics;
        Metrics m = new Metrics();
        ComputedStyle s = span.style;
        m.font = FontSpec.of(s);
        m.ascent = pass.text.ascent(m.font);
        m.glyphHeight = pass.text.glyphHeight(m.font);
        float halfLeading = (s.usedLineHeight() - m.glyphHeight) / 2;
        m.above = m.ascent + halfLeading;
        m.below = m.glyphHeight - m.ascent + halfLeading;
        if (span.parent != null) {
            Metrics parent = metrics(span.parent);
            m.shift = parent.shift + alignShift(s.verticalAlign, parent, m.above, m.below);
        }
        span.metrics = m;
        return m;
    }

    /**
     * Baseline offset (down positive) of a box extending {@code above}/{@code below} its baseline, aligned per
     * {@code vertical-align} in a parent inline box. Line-relative {@code top}/{@code bottom} give 0 here; the line
     * handles atomics with them (inline boxes with them are treated as baseline-aligned).
     */
    private static float alignShift(VerticalAlign align, Metrics parent, float above, float below) {
        float size = parent.font.size();
        return switch (align) {
            case BASELINE, TOP, BOTTOM -> 0;
            case SUB -> size * 0.2f;
            case SUPER -> -size * 0.34f;
            case TEXT_TOP -> -parent.ascent + above;
            case TEXT_BOTTOM -> parent.glyphHeight - parent.ascent - below;
            // The box's middle on the parent's baseline raised by half its x-height (taken as half the em).
            case MIDDLE -> -size / 4 - (below - above) / 2;
        };
    }

    /**
     * A line's height and the offset of its root baseline from the line top. {@code atomicTop} holds, per piece of
     * the line, an atomic's margin-box top relative to the root baseline (or -/+ infinity for top/bottom).
     */
    private record LineMetrics(float height, float baseline, float[] atomicTop) {}

    private LineMetrics lineMetrics(InlineContent content, Pieces p, LayoutResult[] atomics, Line line) {
        Metrics root = metrics(content.root);
        float top = -root.above, bottom = root.below;
        float topAligned = 0, bottomAligned = 0;
        float[] atomicTop = new float[line.end - line.start];
        for (int j = line.start; j < line.end; j++) {
            Item item = content.items.get(p.item[j]);
            Span span = switch (item) {
                case TextItem t -> t.span();
                case Open o -> o.span();
                case Atomic a -> a.span();
                default -> null;
            };
            if (span == null) continue;
            Metrics m = metrics(span);
            top = Math.min(top, m.shift - m.above);
            bottom = Math.max(bottom, m.shift + m.below);
            if (item instanceof Atomic a) {
                LayoutBox b = a.box();
                float height = atomics[j].height() + b.marginTop + b.marginBottom;
                VerticalAlign align = b.style.verticalAlign;
                if (align == VerticalAlign.TOP) {
                    topAligned = Math.max(topAligned, height);
                    atomicTop[j - line.start] = Float.NEGATIVE_INFINITY;
                } else if (align == VerticalAlign.BOTTOM) {
                    bottomAligned = Math.max(bottomAligned, height);
                    atomicTop[j - line.start] = Float.POSITIVE_INFINITY;
                } else {
                    float baseline = b.marginTop + atomicBaseline(b, atomics[j]);
                    float boxTop = m.shift + alignShift(align, m, baseline, height - baseline) - baseline;
                    atomicTop[j - line.start] = boxTop;
                    top = Math.min(top, boxTop);
                    bottom = Math.max(bottom, boxTop + height);
                }
            }
        }
        // Line-relative (top/bottom) boxes can only make the line taller.
        if (topAligned > bottom - top) bottom = top + topAligned;
        if (bottomAligned > bottom - top) top = bottom - bottomAligned;
        return new LineMetrics(bottom - top, -top, atomicTop);
    }

    /**
     * Baseline of an atomic inline from its border-box top: an inline-block's last line, an inline flex/grid
     * container's first baseline, a control's text; its bottom margin edge when it has none or clips its overflow.
     */
    private static float atomicBaseline(LayoutBox b, LayoutResult r) {
        float baseline = switch (b.context) {
            case FLOW -> b.style.isScrollContainer() ? Float.NaN : r.lastBaseline();
            case FLEX, GRID, LEAF -> r.firstBaseline();
        };
        return Float.isNaN(baseline) ? r.height() + b.marginBottom : baseline;
    }

    // ---- Fragments ----

    /** Writes the line boxes and fragments of one layout into the box, and the geometry of its inline elements. */
    private final class LineWriter {
        private final LayoutBox box;
        private final InlineContent content;
        private final Pieces p;
        private final float[] widths;
        private final LayoutResult[] atomics;
        private final float contentWidth, percentHeight;
        /** Inline boxes open at the current position, outermost first. */
        private final List<Span> open = new ArrayList<>();
        /** Fragments of inline boxes on the current line, waiting for their end. */
        private final List<OpenFragment> pending = new ArrayList<>();
        /** Bounding rectangles {x0, y0, x1, y1} of inline elements' fragments. */
        private final Map<Span, float[]> bounds = new IdentityHashMap<>();
        private final Map<Span, float[]> offsets = new IdentityHashMap<>();
        private LineBox lineBox;
        private float pen, lineTop, lineHeight, rootBaseline;
        // The text run being accumulated.
        private TextItem runItem;
        private int runStart, runEnd;
        private float runX, runWidth;
        private boolean runEllipsis;

        /** An inline box's fragment on the current line, held at a reserved index of the fragment list. */
        private record OpenFragment(Span span, int index, float x, boolean first) {}

        LineWriter(LayoutBox box, InlineContent content, Pieces p, float[] widths, LayoutResult[] atomics,
                   float contentWidth, float percentHeight) {
            this.box = box;
            this.content = content;
            this.p = p;
            this.widths = widths;
            this.atomics = atomics;
            this.contentWidth = contentWidth;
            this.percentHeight = percentHeight;
            box.lines.clear();
            // Paint draws the inline boxes' edges as resolved here, against this containing block.
            for (Item item : content.items) if (item instanceof Open o) BoxModel.resolveEdges(o.span().box, contentWidth);
        }

        void write(Line line, float indent, float y, LineMetrics m, boolean clampedEnd, boolean lastLine) {
            ComputedStyle style = box.style;
            float width = lineWidth(p, widths, line, indent);
            float free = contentWidth - width;
            int first = firstContent(p, line), last = lastContent(p, line);
            // Overflowing lines are start-aligned.
            float offset = switch (style.textAlign) {
                case RIGHT, END -> Math.max(0, free);
                case CENTER -> Math.max(0, free) / 2;
                case LEFT, START, JUSTIFY -> 0;
            };
            float extraPerSpace = 0;
            if (style.textAlign == TextAlign.JUSTIFY && free > 0 && !line.forced && !lastLine) {
                int spaces = 0;
                for (int j = first; j <= last; j++) if (p.kind[j] == SPACE) spaces += p.end[j] - p.start[j];
                if (spaces > 0) extraPerSpace = free / spaces;
            }
            // An ellipsis replaces what overflows a clipping box, and ends a line that line-clamp cut short.
            float ellipsisWidth = ellipsisWidth(content.root);
            boolean overflowEllipsis = style.textOverflow == TextOverflow.ELLIPSIS && style.overflowX.clips()
                    && width > contentWidth + EPSILON;
            boolean ellipsisFits = width + ellipsisWidth <= contentWidth + EPSILON;
            float limit = overflowEllipsis || (clampedEnd && !ellipsisFits)
                    ? box.contentX() + contentWidth - ellipsisWidth : Float.POSITIVE_INFINITY;

            lineTop = y;
            lineHeight = m.height;
            rootBaseline = y + m.baseline;
            pen = box.contentX() + indent + offset;
            lineBox = new LineBox(pen, y, 0, m.height, rootBaseline);
            box.lines.add(lineBox);
            for (Span s : open) pending.add(new OpenFragment(s, reserve(), pen, false));

            boolean truncated = false;
            for (int j = line.start; j < line.end; j++) {
                byte k = p.kind[j];
                boolean visible = k == TEXT || k == SPACE || k == ATOMIC;
                if (p.isStrippable(j) && (j < first || j > last)) continue;
                if (visible && truncated) continue;
                if (visible && pen + widths[j] > limit + EPSILON) {
                    truncate(j, limit);
                    truncated = true;
                    continue;
                }
                if (k == SPACE && extraPerSpace > 0) {
                    // Justified: words become separate runs with the stretched spaces between them.
                    flushRun();
                    pen += widths[j] + extraPerSpace * (p.end[j] - p.start[j]);
                    continue;
                }
                switch (content.items.get(p.item[j])) {
                    case TextItem t -> addText(t, j);
                    case Open o -> openSpan(o.span());
                    case Close c -> closeSpan(c.span());
                    case Atomic a -> placeAtomic(a, j, m.atomicTop[j - line.start]);
                    case Break b -> {
                        flushRun();
                        if (b.box() != null) setBounds(b.box(), pen, lineTop, 0, lineHeight);
                    }
                    case Placeholder ph -> {
                        flushRun();
                        PositionedLayout.setStaticPosition(ph.box(), box, pen, lineTop);
                    }
                }
            }
            flushRun();
            if (clampedEnd && !truncated) {
                startRun(null, 0);
                ellipsis();
            }
            for (int i = pending.size() - 1; i >= 0; i--) closeFragment(pending.get(i), false);
            pending.clear();
            lineBox.width = pen - lineBox.x;
        }

        /** Static positions of out-of-flow boxes whose placeholders sit on an empty (zero-height) line. */
        void placeholders(Line line, float y) {
            for (int j = line.start; j < line.end; j++) {
                if (content.items.get(p.item[j]) instanceof Placeholder ph) {
                    PositionedLayout.setStaticPosition(ph.box(), box, box.contentX(), y);
                }
            }
        }

        // -- Text runs --

        private void addText(TextItem item, int j) {
            if (runItem != item || runEnd != p.start[j]) {
                flushRun();
                startRun(item, p.start[j]);
            }
            runEnd = p.end[j];
            runWidth += widths[j];
            pen += widths[j];
        }

        private void startRun(TextItem item, int start) {
            runItem = item;
            runStart = runEnd = start;
            runX = pen;
            runWidth = 0;
        }

        /** Ends the current run with an ellipsis and writes it. */
        private void ellipsis() {
            float w = ellipsisWidth(runItem != null ? runItem.span() : content.root);
            runWidth += w;
            pen += w;
            runEllipsis = true;
            flushRun();
        }

        private void flushRun() {
            if (runItem == null && !runEllipsis) return;
            Span span = runItem != null ? runItem.span() : content.root;
            Metrics m = metrics(span);
            float[] off = offset(span);
            String text = runItem != null ? runItem.text().substring(runStart, runEnd) : "";
            if (runEllipsis) text += ELLIPSIS;
            int[] source = runItem == null ? null : runItem.sourceSlice(runStart, runEnd);
            lineBox.fragments.add(new Fragment.TextRun(runItem != null ? runItem.node() : null, span.element,
                    span.style, text, source, pass.text.spaced(text, m.font, span.style),
                    runX + off[0], rootBaseline + m.shift - m.ascent + off[1], runWidth, m.glyphHeight));
            runItem = null;
            runEllipsis = false;
        }

        /**
         * Cuts the line before {@code limit}: keeps the characters of text piece {@code j} that fit, then ends the
         * run with an ellipsis. Everything visible after it is dropped.
         */
        private void truncate(int j, float limit) {
            if (p.kind[j] == ATOMIC) {
                flushRun();
                startRun(null, 0);
                ellipsis();
                return;
            }
            TextItem item = (TextItem) content.items.get(p.item[j]);
            ComputedStyle s = item.span().style;
            FontSpec font = metrics(item.span()).font;
            String text = item.text();
            int i = p.start[j];
            float w = 0;
            while (i < p.end[j]) {
                int cp = text.codePointAt(i);
                float cw = pass.text.advance(cp, font, s);
                if (pen + w + cw > limit + EPSILON) break;
                w += cw;
                i += Character.charCount(cp);
            }
            if (runItem != item || runEnd != p.start[j]) {
                flushRun();
                startRun(item, p.start[j]);
            }
            runEnd = i;
            runWidth += w;
            pen += w;
            ellipsis();
        }

        private float ellipsisWidth(Span span) {
            return pass.text.width(ELLIPSIS, metrics(span).font, span.style);
        }

        // -- Inline boxes --

        /** Reserves the fragment-list slot of an inline box, so it paints before the content it wraps. */
        private int reserve() {
            lineBox.fragments.add(null);
            return lineBox.fragments.size() - 1;
        }

        private void openSpan(Span span) {
            flushRun();
            Box b = span.box;
            pen += b.marginLeft;
            open.add(span);
            pending.add(new OpenFragment(span, reserve(), pen, true));
            pen += b.borderLeft + b.paddingLeft;
        }

        private void closeSpan(Span span) {
            flushRun();
            Box b = span.box;
            pen += b.paddingRight + b.borderRight;
            for (int i = pending.size() - 1; i >= 0; i--) {
                if (pending.get(i).span == span) {
                    closeFragment(pending.remove(i), true);
                    break;
                }
            }
            open.remove(span);
            pen += b.marginRight;
        }

        /** Fills an inline box's reserved fragment slot now that its extent on this line is known. */
        private void closeFragment(OpenFragment f, boolean last) {
            Span span = f.span;
            Box b = span.box;
            Metrics m = metrics(span);
            float[] off = offset(span);
            float above = b.paddingTop + b.borderTop, below = b.paddingBottom + b.borderBottom;
            float x = f.x + off[0];
            float y = rootBaseline + m.shift - m.ascent - above + off[1];
            float w = pen - f.x, h = m.glyphHeight + above + below;
            lineBox.fragments.set(f.index, new Fragment.InlineBox(b, x, y, w, h, f.first, last, lineBox.fragments.size()));
            float[] r = bounds.get(span);
            if (r == null) {
                bounds.put(span, new float[] {x, y, x + w, y + h});
            } else {
                r[0] = Math.min(r[0], x);
                r[1] = Math.min(r[1], y);
                r[2] = Math.max(r[2], x + w);
                r[3] = Math.max(r[3], y + h);
            }
        }

        /** The relative-position offset of a span, accumulated over its ancestors. */
        private float[] offset(Span span) {
            if (span.parent == null) return NO_OFFSET;
            float[] total = offsets.get(span);
            if (total == null) {
                float[] parent = offset(span.parent);
                float[] own = PositionedLayout.relativeOffset(span.style, contentWidth, percentHeight);
                total = new float[] {parent[0] + own[0], parent[1] + own[1]};
                offsets.put(span, total);
            }
            return total;
        }

        // -- Atomics --

        private void placeAtomic(Atomic item, int j, float top) {
            flushRun();
            LayoutBox b = item.box();
            float height = atomics[j].height() + b.marginTop + b.marginBottom;
            float y = top == Float.NEGATIVE_INFINITY ? lineTop
                    : top == Float.POSITIVE_INFINITY ? lineTop + lineHeight - height
                    : rootBaseline + top;
            float[] off = offset(item.span());
            b.x = pen + b.marginLeft + off[0];
            b.y = y + b.marginTop + off[1];
            PositionedLayout.applyRelativeOffset(b, contentWidth, percentHeight);
            lineBox.fragments.add(new Fragment.Atomic(b));
            pen += widths[j];
        }

        /**
         * Gives the inline boxes the bounds of their fragments (those without fragments get an empty box at the
         * content origin) and positions the placeholders of lines cut off by line-clamp.
         */
        void finish(List<Line> clampedAway, float y) {
            for (Line line : clampedAway) placeholders(line, y);
            for (Item item : content.items) {
                if (!(item instanceof Open o)) continue;
                float[] r = bounds.get(o.span());
                if (r == null) setBounds(o.span().box, box.contentX(), box.contentY(), 0, 0);
                else setBounds(o.span().box, r[0], r[1], r[2] - r[0], r[3] - r[1]);
            }
        }
    }

    private static void setBounds(Box b, float x, float y, float w, float h) {
        b.x = x;
        b.y = y;
        b.width = w;
        b.height = h;
    }
}
