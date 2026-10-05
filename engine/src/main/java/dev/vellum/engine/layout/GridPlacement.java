package dev.vellum.engine.layout;

import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.GridLine;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Grid item placement (CSS Grid 1 §8): resolves line-based placement (numbers, negative numbers, spans, area names
 * from {@code grid-template-areas}) and runs the auto-placement algorithm (row or column flow, sparse or dense).
 * Track indices are 0-based and shifted so that implicit tracks created before the explicit grid start at 0.
 */
final class GridPlacement {
    /** A placed item: its track ranges {@code [start, end)} per axis. */
    static final class Placed {
        final LayoutBox box;
        int columnStart, columnEnd, rowStart, rowEnd;

        Placed(LayoutBox box) {
            this.box = box;
        }

        int start(Axis axis) { return axis.isHorizontal() ? columnStart : rowStart; }
        int end(Axis axis) { return axis.isHorizontal() ? columnEnd : rowEnd; }
        int span(Axis axis) { return end(axis) - start(axis); }

        void set(Axis axis, int start, int end) {
            if (axis.isHorizontal()) {
                columnStart = start;
                columnEnd = end;
            } else {
                rowStart = start;
                rowEnd = end;
            }
        }
    }

    /** The result: placed items, and per axis the track count and the index of the first explicit track. */
    record Result(List<Placed> items, int columns, int rows, int columnOffset, int rowOffset) {
        int count(Axis axis) { return axis.isHorizontal() ? columns : rows; }
        int offset(Axis axis) { return axis.isHorizontal() ? columnOffset : rowOffset; }
    }

    /** A resolved range in explicit-grid line indices (0 = first explicit line); start NaN-like when auto. */
    private record Span(boolean definite, int start, int span) {}

    private final ComputedStyle style;
    private final int explicitColumns, explicitRows;
    /** Area name to {columnStart, columnEnd, rowStart, rowEnd} in 0-based line indices. */
    private final Map<String, int[]> areas = new HashMap<>();

    GridPlacement(ComputedStyle style, int explicitColumns, int explicitRows) {
        this.style = style;
        this.explicitColumns = explicitColumns;
        this.explicitRows = explicitRows;
        List<List<String>> template = style.gridTemplateAreas;
        if (template != null) {
            for (int r = 0; r < template.size(); r++) {
                List<String> row = template.get(r);
                for (int c = 0; c < row.size(); c++) {
                    String name = row.get(c);
                    if (name.equals(".")) continue;
                    int[] area = areas.computeIfAbsent(name,
                            n -> new int[] {Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0});
                    area[0] = Math.min(area[0], c);
                    area[1] = Math.max(area[1], c + 1);
                    area[2] = Math.min(area[2], r);
                    area[3] = Math.max(area[3], r + 1);
                }
            }
        }
    }

    Result place(List<LayoutBox> boxes) {
        int n = boxes.size();
        Span[] cols = new Span[n], rows = new Span[n];
        int minColumn = 0, minRow = 0;
        for (int i = 0; i < n; i++) {
            ComputedStyle s = boxes.get(i).style;
            cols[i] = resolve(s.gridColumnStart, s.gridColumnEnd, explicitColumns, true);
            rows[i] = resolve(s.gridRowStart, s.gridRowEnd, explicitRows, false);
            if (cols[i].definite) minColumn = Math.min(minColumn, cols[i].start);
            if (rows[i].definite) minRow = Math.min(minRow, rows[i].start);
        }
        int columnOffset = -minColumn, rowOffset = -minRow;

        boolean columnFlow = style.gridAutoFlow.isColumn();
        boolean dense = style.gridAutoFlow.isDense();
        Axis major = columnFlow ? Axis.HORIZONTAL : Axis.VERTICAL;
        Axis minor = major.other();
        int majorOffset = columnFlow ? columnOffset : rowOffset, minorOffset = columnFlow ? rowOffset : columnOffset;
        Span[] majorSpans = columnFlow ? cols : rows, minorSpans = columnFlow ? rows : cols;

        // The minor axis has a fixed size during auto-placement: explicit, definite placements and auto spans.
        int minorCount = minorOffset + (columnFlow ? explicitRows : explicitColumns);
        for (int i = 0; i < n; i++) {
            Span m = minorSpans[i];
            minorCount = Math.max(minorCount, m.definite ? m.start + minorOffset + m.span : m.span);
        }

        List<Placed> placed = new ArrayList<>(n);
        for (LayoutBox b : boxes) placed.add(new Placed(b));
        Occupancy grid = new Occupancy();
        boolean[] done = new boolean[n];
        // 1. Items with a definite position in both axes.
        for (int i = 0; i < n; i++) {
            if (majorSpans[i].definite && minorSpans[i].definite) {
                put(placed.get(i), grid, major, majorSpans[i].start + majorOffset, majorSpans[i].span,
                        minorSpans[i].start + minorOffset, minorSpans[i].span);
                done[i] = true;
            }
        }
        // 2. Items locked to a major-axis line: earliest minor position that fits (after earlier ones when sparse).
        Map<Integer, Integer> lineCursors = new HashMap<>();
        for (int i = 0; i < n; i++) {
            if (done[i] || !majorSpans[i].definite) continue;
            int start = majorSpans[i].start + majorOffset, span = majorSpans[i].span, minorSpan = minorSpans[i].span;
            int m = dense ? 0 : lineCursors.getOrDefault(start, 0);
            while (!grid.fits(start, span, m, minorSpan)) m++;
            put(placed.get(i), grid, major, start, span, m, minorSpan);
            lineCursors.put(start, m + minorSpan);
            minorCount = Math.max(minorCount, m + minorSpan);
            done[i] = true;
        }
        // 3. Everything else, with the auto-placement cursor.
        int cursorMajor = 0, cursorMinor = 0;
        for (int i = 0; i < n; i++) {
            if (done[i]) continue;
            int majorSpan = majorSpans[i].span, minorSpan = Math.min(minorSpans[i].span, Math.max(1, minorCount));
            if (dense) {
                cursorMajor = 0;
                cursorMinor = 0;
            }
            if (minorSpans[i].definite) {
                int m = minorSpans[i].start + minorOffset;
                if (!dense && m < cursorMinor) cursorMajor++;
                cursorMinor = m;
                while (!grid.fits(cursorMajor, majorSpan, m, minorSpan)) cursorMajor++;
                put(placed.get(i), grid, major, cursorMajor, majorSpan, m, minorSpan);
            } else {
                while (true) {
                    if (cursorMinor + minorSpan > minorCount) {
                        cursorMajor++;
                        cursorMinor = 0;
                    } else if (grid.fits(cursorMajor, majorSpan, cursorMinor, minorSpan)) {
                        break;
                    } else {
                        cursorMinor++;
                    }
                }
                put(placed.get(i), grid, major, cursorMajor, majorSpan, cursorMinor, minorSpan);
                cursorMinor += minorSpan;
            }
        }

        int columns = columnOffset + explicitColumns, rowCount = rowOffset + explicitRows;
        for (Placed p : placed) {
            columns = Math.max(columns, p.columnEnd);
            rowCount = Math.max(rowCount, p.rowEnd);
        }
        return new Result(placed, columns, rowCount, columnOffset, rowOffset);
    }

    private static void put(Placed p, Occupancy grid, Axis major, int majorStart, int majorSpan, int minorStart,
                            int minorSpan) {
        p.set(major, majorStart, majorStart + majorSpan);
        p.set(major.other(), minorStart, minorStart + minorSpan);
        grid.mark(majorStart, majorSpan, minorStart, minorSpan);
    }

    /**
     * Resolves a start/end pair (CSS Grid §8.3.1) into a definite range of explicit line indices, or an auto
     * position with a span.
     */
    private Span resolve(GridLine start, GridLine end, int explicit, boolean columns) {
        int s = line(start, explicit, columns, true), e = line(end, explicit, columns, false);
        boolean sDefinite = s != Integer.MIN_VALUE, eDefinite = e != Integer.MIN_VALUE;
        if (sDefinite && eDefinite) {
            if (s == e) e = s + 1;
            return new Span(true, Math.min(s, e), Math.abs(e - s));
        }
        if (sDefinite) return new Span(true, s, end.kind() == GridLine.Kind.SPAN ? end.value() : 1);
        if (eDefinite) {
            int span = start.kind() == GridLine.Kind.SPAN ? start.value() : 1;
            return new Span(true, e - span, span);
        }
        return new Span(false, 0, start.kind() == GridLine.Kind.SPAN ? start.value()
                : end.kind() == GridLine.Kind.SPAN ? end.value() : 1);
    }

    /**
     * The lines (as track indices of {@code grid}) bounding the grid area of an absolutely positioned child in one
     * axis, -1 for an auto side, which is the grid container's padding edge (CSS Grid §9.4). A span against an auto
     * line, and a line outside the grid, are auto.
     */
    int[] absoluteArea(ComputedStyle s, Axis axis, Result grid) {
        boolean columns = axis.isHorizontal();
        GridLine startLine = columns ? s.gridColumnStart : s.gridRowStart;
        GridLine endLine = columns ? s.gridColumnEnd : s.gridRowEnd;
        int explicit = columns ? explicitColumns : explicitRows;
        int start = line(startLine, explicit, columns, true), end = line(endLine, explicit, columns, false);
        if (start != Integer.MIN_VALUE && end == Integer.MIN_VALUE && endLine.kind() == GridLine.Kind.SPAN) {
            end = start + endLine.value();
        } else if (end != Integer.MIN_VALUE && start == Integer.MIN_VALUE && startLine.kind() == GridLine.Kind.SPAN) {
            start = end - startLine.value();
        } else if (start != Integer.MIN_VALUE && end != Integer.MIN_VALUE && start > end) {
            int swap = start;
            start = end;
            end = swap;
        }
        int offset = grid.offset(axis), count = grid.count(axis);
        return new int[] {inGrid(start, offset, count), inGrid(end, offset, count)};
    }

    private static int inGrid(int line, int offset, int count) {
        int i = line == Integer.MIN_VALUE ? -1 : line + offset;
        return i < 0 || i > count ? -1 : i;
    }

    /** A line as a 0-based explicit line index, or {@code MIN_VALUE} when it is auto or a span. */
    private int line(GridLine l, int explicit, boolean columns, boolean isStart) {
        return switch (l.kind()) {
            case LINE -> l.value() > 0 ? l.value() - 1 : explicit + 1 + l.value();
            case NAMED -> named(l.name(), columns, isStart);
            case AUTO, SPAN -> Integer.MIN_VALUE;
        };
    }

    /**
     * A named line: an area's edge ({@code a}, {@code a-start}, {@code a-end}). An unknown name is the first implicit
     * line after the explicit grid (all implicit lines are assumed to have every name).
     */
    private int named(String name, boolean columns, boolean isStart) {
        boolean start = isStart;
        String area = name;
        if (name.endsWith("-start")) {
            area = name.substring(0, name.length() - 6);
            start = true;
        } else if (name.endsWith("-end")) {
            area = name.substring(0, name.length() - 4);
            start = false;
        }
        int[] edges = areas.get(area);
        if (edges == null) return (columns ? explicitColumns : explicitRows) + 1;
        return edges[(columns ? 0 : 2) + (start ? 0 : 1)];
    }

    /** Explicit grid size implied by grid-template-areas: {columns, rows}. */
    static int[] areaSize(ComputedStyle s) {
        if (s.gridTemplateAreas == null) return new int[2];
        int columns = 0;
        for (List<String> row : s.gridTemplateAreas) columns = Math.max(columns, row.size());
        return new int[] {columns, s.gridTemplateAreas.size()};
    }

    /** Occupied cells, indexed [major][minor]. */
    private static final class Occupancy {
        private final List<BitSet> lines = new ArrayList<>();

        boolean fits(int major, int majorSpan, int minor, int minorSpan) {
            for (int i = major; i < major + majorSpan; i++) {
                if (i < lines.size() && lines.get(i).get(minor, minor + minorSpan).cardinality() > 0) return false;
            }
            return true;
        }

        void mark(int major, int majorSpan, int minor, int minorSpan) {
            for (int i = major; i < major + majorSpan; i++) {
                while (lines.size() <= i) lines.add(new BitSet());
                lines.get(i).set(minor, minor + minorSpan);
            }
        }
    }
}
