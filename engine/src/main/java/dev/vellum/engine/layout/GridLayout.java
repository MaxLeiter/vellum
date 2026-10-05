package dev.vellum.engine.layout;

import dev.vellum.engine.layout.GridPlacement.Placed;
import dev.vellum.engine.layout.GridTrackSizing.Mode;
import dev.vellum.engine.layout.GridTrackSizing.Track;
import dev.vellum.engine.style.Align;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.GridTrack;
import dev.vellum.engine.style.Length;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * CSS Grid Layout 1: the explicit grid ({@code grid-template-*} with {@code repeat(auto-fill|auto-fit)} expanded
 * against the container, {@code grid-template-areas}), placement ({@link GridPlacement}), implicit tracks from
 * {@code grid-auto-*}, track sizing for columns then rows ({@link GridTrackSizing}), {@code justify-content} /
 * {@code align-content}, and items aligned in their areas ({@code justify-self}/{@code align-self}, stretch by
 * default, auto margins). A grid item's containing block is its grid area.
 */
final class GridLayout implements FormattingContext {
    private final LayoutPass pass;

    GridLayout(LayoutPass pass) {
        this.pass = pass;
    }

    /** A container being laid out or measured: its placed items and tracks per axis. */
    private final class Grid {
        final LayoutBox box;
        final ComputedStyle style;
        final float contentWidth;
        final float columnGap, rowGap;
        final List<Placed> items;
        final Track[] columns;
        Track[] rows;
        private final GridPlacement placement;
        private final GridPlacement.Result placed;
        private final List<GridTrack> rowTemplate;
        private final BitSet autoFitRows = new BitSet();

        Grid(LayoutBox box, float contentWidth, float contentHeight, float percentHeight) {
            this.box = box;
            this.style = box.style;
            this.contentWidth = contentWidth;
            this.columnGap = BoxModel.nonNegative(style.columnGap, contentWidth);
            this.rowGap = BoxModel.nonNegative(style.rowGap, percentHeight);
            float pbHeight = BoxModel.paddingBorderHeight(box);
            // Auto repetitions fill the definite size, else the max size, else the min size.
            float rowSpace = BoxModel.or(contentHeight, BoxModel.or(box.maxHeight, box.minHeight) - pbHeight);
            BitSet autoFitColumns = new BitSet();
            List<GridTrack> columnTemplate = expand(style.gridTemplateColumns, contentWidth, columnGap, autoFitColumns);
            this.rowTemplate = expand(style.gridTemplateRows, rowSpace, rowGap, autoFitRows);
            int[] areas = GridPlacement.areaSize(style);

            List<LayoutBox> boxes = new ArrayList<>();
            for (Box c : box.children) {
                LayoutBox child = (LayoutBox) c;
                if (!child.outOfFlow) boxes.add(child);
            }
            boxes.sort((a, b) -> Integer.compare(a.style.order, b.style.order));
            this.placement = new GridPlacement(style, Math.max(columnTemplate.size(), areas[0]),
                    Math.max(rowTemplate.size(), areas[1]));
            this.placed = placement.place(boxes);
            this.items = placed.items();
            this.columns = tracks(Axis.HORIZONTAL, columnTemplate, style.gridAutoColumns, autoFitColumns, contentWidth);
            this.rows = tracks(Axis.VERTICAL, rowTemplate, style.gridAutoRows, autoFitRows, percentHeight);
        }

        /**
         * The tracks of one axis: explicit ones from the template (empty auto-fit ones collapsed), implicit ones
         * before and after it cycling through the grid-auto-* list.
         */
        private Track[] tracks(Axis axis, List<GridTrack> template, List<GridTrack> auto, BitSet autoFit,
                               float available) {
            int count = placed.count(axis), offset = placed.offset(axis);
            BitSet occupied = new BitSet();
            for (Placed p : items) occupied.set(p.start(axis), p.end(axis));
            Track[] out = new Track[count];
            for (int i = 0; i < count; i++) {
                int explicit = i - offset;
                if (explicit >= 0 && explicit < template.size()) {
                    out[i] = autoFit.get(explicit) && !occupied.get(i) ? Track.collapsedTrack()
                            : Track.of(template.get(explicit), available);
                } else {
                    int k = explicit < 0 ? explicit : explicit - template.size();
                    out[i] = auto.isEmpty() ? Track.of(new GridTrack.Keyword(Length.AUTO), available)
                            : Track.of(auto.get(Math.floorMod(k, auto.size())), available);
                }
            }
            return out;
        }

        /**
         * Percentage rows of a grid without a definite height count as auto at first; once the rows have given the
         * grid its height they resolve against it (as browsers do). Returns false when there are none.
         */
        boolean resolvePercentRows(float height) {
            boolean any = false;
            for (GridTrack t : rowTemplate) any |= hasPercent(t);
            if (any) rows = tracks(Axis.VERTICAL, rowTemplate, style.gridAutoRows, autoFitRows, height);
            return any;
        }

        Track[] tracks(Axis axis) { return axis.isHorizontal() ? columns : rows; }
        float gap(Axis axis) { return axis.isHorizontal() ? columnGap : rowGap; }

        /**
         * The {start, end} edges, in the container's border-box space, of an out-of-flow child's grid area in one
         * axis; auto sides are the padding edges ({@code paddingStart} and {@code paddingStart + paddingSize}).
         */
        float[] areaEdges(LayoutBox b, Axis axis, float paddingStart, float paddingSize) {
            int[] lines = placement.absoluteArea(b.style, axis, placed);
            float start = lines[0] < 0 ? paddingStart : linePosition(axis, lines[0], true);
            float end = lines[1] < 0 ? paddingStart + paddingSize : linePosition(axis, lines[1], false);
            return new float[] {start, Math.max(start, end)};
        }

        /**
         * A grid line's position in the container's border-box space: the start of the track after it, or (for an
         * end line, so excluding the gap) the end of the track before it.
         */
        private float linePosition(Axis axis, int line, boolean startEdge) {
            Track[] t = tracks(axis);
            float offset;
            if (t.length == 0) offset = 0;
            else if (line == t.length || (!startEdge && line > 0)) offset = t[line - 1].offset + t[line - 1].base;
            else offset = t[line].offset;
            return axis.contentStart(box) + offset;
        }

        /** The size of an item's area: its tracks and the gaps between them. */
        float area(Placed item, Axis axis) {
            Track[] t = tracks(axis);
            Track last = t[item.end(axis) - 1];
            return last.offset + last.base - t[item.start(axis)].offset;
        }

        Align justify(LayoutBox b) {
            return selfAlign(b, b.style.justifySelf, style.justifyItems, false);
        }

        Align alignment(LayoutBox b) {
            return selfAlign(b, b.style.alignSelf, style.alignItems, true);
        }

        /**
         * {@code normal} stretches, except replaced elements, and boxes with an aspect-ratio in the block axis (where
         * their height follows from their width).
         */
        private static Align selfAlign(LayoutBox b, Align self, Align items, boolean blockAxis) {
            Align a = Alignment.self(self, items);
            if (a != Align.NORMAL) return a;
            boolean ratio = b.isReplaced() || (blockAxis && !Float.isNaN(b.style.aspectRatio));
            return ratio ? Align.START : Align.STRETCH;
        }

        /** The item's border-box width in an area {@code areaWidth} wide: stretched, or fit-content. */
        float itemWidth(Placed item, float areaWidth) {
            LayoutBox b = item.box;
            BoxModel.resolveEdges(b, areaWidth);
            float available = areaWidth - b.marginLeft - b.marginRight;
            if (stretches(item, Axis.HORIZONTAL, justify(b))) {
                return pass.clampWidth(b, available, areaWidth, Float.NaN, available);
            }
            return pass.usedWidth(b, available, areaWidth, Float.NaN, false);
        }

        boolean stretches(Placed item, Axis axis, Align align) {
            ComputedStyle s = item.box.style;
            return align == Align.STRETCH && axis.size(s).isAuto() && !axis.marginStart(s).isAuto()
                    && !axis.marginEnd(s).isAuto();
        }

        /** Sizes the tracks of one axis (rows need the columns sized first). */
        void size(Axis axis, Mode mode, float available) {
            float pb = axis.paddingBorder(box);
            float minSize = axis.isHorizontal() ? Float.NaN : box.minHeight - pb;
            float maxSize = axis.isHorizontal() ? Float.NaN : box.maxHeight - pb;
            Align content = axis.isHorizontal() ? style.justifyContent : style.alignContent;
            GridTrackSizing.size(tracks(axis), items, axis, mode, available, gap(axis), contributions(axis),
                    content == Align.NORMAL || content == Align.STRETCH, minSize, maxSize);
        }

        /** Places the tracks of one axis by {@code justify-content}/{@code align-content}; returns their extent. */
        float position(Axis axis, float available) {
            Track[] t = tracks(axis);
            float gap = gap(axis);
            float used = GridTrackSizing.gapTotal(t, gap);
            int visible = 0;
            for (Track track : t) {
                used += track.base;
                if (!track.collapsed) visible++;
            }
            Align content = axis.isHorizontal() ? style.justifyContent : style.alignContent;
            float[] distribution = Float.isNaN(available) ? new float[] {0, 0}
                    : Alignment.distribute(content, available - used, visible);
            float offset = distribution[0];
            for (Track track : t) {
                track.offset = offset;
                offset += track.base + (track.collapsed ? 0 : gap + distribution[1]);
            }
            return used;
        }

        /**
         * Items' contributions in one axis: widths from their intrinsic sizes, heights from laying them out at the
         * width their columns give them.
         */
        private GridTrackSizing.Contributions contributions(Axis axis) {
            return new GridTrackSizing.Contributions() {
                public float minContent(Placed item) {
                    return axis.isHorizontal() ? pass.contribution(item.box, false) : outerHeight(item);
                }

                public float maxContent(Placed item) {
                    return axis.isHorizontal() ? pass.contribution(item.box, true) : outerHeight(item);
                }

                public float definiteMinimum(Placed item) {
                    LayoutBox b = item.box;
                    ComputedStyle s = b.style;
                    float pb = BoxModel.paddingBorder(s, axis, Float.NaN);
                    float min = BoxModel.or(size(b, axis, axis.size(s), pb), size(b, axis, axis.minSize(s), pb));
                    if (Float.isNaN(min)) return Float.NaN;
                    // Heights come from layouts at the area width, so their margins are resolved; widths' are not.
                    float margins = axis.isHorizontal() ? BoxModel.margins(s, axis, Float.NaN) : axis.margins(b);
                    return Math.max(min, pb) + margins;
                }

                public boolean scrolls(Placed item) {
                    return item.box.style.isScrollContainer();
                }
            };
        }

        /** A size property of an item for track sizing, where percentages (of its unknown area) do not resolve. */
        private float size(LayoutBox b, Axis axis, Length l, float pb) {
            return axis.isHorizontal() ? pass.resolveWidth(b, l, Float.NaN, Float.NaN, pb)
                    : BoxModel.borderBoxSize(b.style, l, Float.NaN, pb);
        }

        /** An item's margin-box height at the width its columns give it. */
        private float outerHeight(Placed item) {
            float areaWidth = area(item, Axis.HORIZONTAL);
            float width = itemWidth(item, areaWidth);
            LayoutBox b = item.box;
            return pass.measureHeight(b, width, Float.NaN, areaWidth, Float.NaN) + b.marginTop + b.marginBottom;
        }
    }

    // ---- Layout ----

    @Override
    public LayoutResult layoutContent(LayoutBox box, float contentWidth, float contentHeight, float percentHeight,
                                      boolean measure) {
        Grid g = new Grid(box, contentWidth, contentHeight, percentHeight);
        g.size(Axis.HORIZONTAL, Mode.DEFINITE, contentWidth);
        g.position(Axis.HORIZONTAL, contentWidth);
        g.size(Axis.VERTICAL, Float.isNaN(contentHeight) ? Mode.MAX_CONTENT : Mode.DEFINITE, contentHeight);
        float height = g.position(Axis.VERTICAL, contentHeight);
        if (Float.isNaN(percentHeight) && g.resolvePercentRows(height)) {
            g.size(Axis.VERTICAL, Mode.DEFINITE, height);
            g.position(Axis.VERTICAL, height);
        }
        // Out-of-flow children are placed against the final (min/max-clamped) height.
        float pb = BoxModel.paddingBorderHeight(box);
        placeOutOfFlow(g, BoxModel.or(contentHeight, BoxModel.clamp(height + pb, box.minHeight, box.maxHeight) - pb));

        Placed first = null;
        for (Placed item : g.items) {
            if (first == null || item.rowStart < first.rowStart
                    || (item.rowStart == first.rowStart && item.columnStart < first.columnStart)) first = item;
        }
        float baseline = Float.NaN;
        for (Placed item : g.items) {
            if (measure && item != first) continue;
            LayoutResult r = place(g, item, measure);
            if (item == first) {
                baseline = item.box.y + (Float.isNaN(r.firstBaseline()) ? r.height() : r.firstBaseline());
            }
        }
        return new LayoutResult(height, baseline, baseline, MarginSet.EMPTY, MarginSet.EMPTY, false);
    }

    /** Lays out (or measures) an item in its area and aligns it there. */
    private LayoutResult place(Grid g, Placed item, boolean measure) {
        LayoutBox b = item.box;
        float areaWidth = g.area(item, Axis.HORIZONTAL), areaHeight = g.area(item, Axis.VERTICAL);
        float width = g.itemWidth(item, areaWidth);
        Align align = g.alignment(b);
        float height = g.stretches(item, Axis.VERTICAL, align)
                ? pass.clampHeight(b, areaHeight - b.marginTop - b.marginBottom, areaWidth, areaHeight) : Float.NaN;
        LayoutResult r = measure ? pass.measure(b, width, height, areaWidth, areaHeight)
                : pass.layout(b, width, height, areaWidth, areaHeight);
        b.x = g.box.contentX() + g.columns[item.columnStart].offset
                + alignInArea(b, Axis.HORIZONTAL, areaWidth, width, g.justify(b));
        b.y = g.box.contentY() + g.rows[item.rowStart].offset
                + alignInArea(b, Axis.VERTICAL, areaHeight, r.height(), align);
        if (!measure) PositionedLayout.applyRelativeOffset(b, areaWidth, areaHeight);
        return r;
    }

    /** The border-box offset of an item in its area: auto margins take the free space, else self alignment. */
    private static float alignInArea(LayoutBox b, Axis axis, float area, float size, Align align) {
        float free = area - size - axis.margins(b);
        if (BoxModel.resolveAutoMargins(b, axis, free)) return axis.marginStart(b);
        return axis.marginStart(b) + Alignment.position(align, free);
    }

    /**
     * Out-of-flow children get their grid area, which is their containing block when the grid container is it
     * (auto lines being the padding edges), and a static position as if they were the only item in an area
     * covering the content box (CSS Grid §9.4, §10.1).
     */
    private void placeOutOfFlow(Grid g, float contentHeight) {
        LayoutBox box = g.box;
        float paddingWidth = g.contentWidth + box.paddingLeft + box.paddingRight;
        float paddingHeight = contentHeight + box.paddingTop + box.paddingBottom;
        for (Box child : box.children) {
            LayoutBox b = (LayoutBox) child;
            if (!b.outOfFlow) continue;
            float[] x = g.areaEdges(b, Axis.HORIZONTAL, box.borderLeft, paddingWidth);
            float[] y = g.areaEdges(b, Axis.VERTICAL, box.borderTop, paddingHeight);
            float alignX = Alignment.position(g.justify(b), 1), alignY = Alignment.position(g.alignment(b), 1);
            b.gridArea = new LayoutBox.Area(box, x[0], y[0], x[1] - x[0], y[1] - y[0], alignX, alignY);
            b.staticPosition = new LayoutBox.Area(box, box.contentX(), box.contentY(), g.contentWidth, contentHeight,
                    alignX, alignY);
        }
    }

    @Override
    public float intrinsicContentWidth(LayoutBox box, boolean max) {
        Grid g = new Grid(box, Float.NaN, Float.NaN, Float.NaN);
        g.size(Axis.HORIZONTAL, max ? Mode.MAX_CONTENT : Mode.MIN_CONTENT, Float.NaN);
        return g.position(Axis.HORIZONTAL, Float.NaN);
    }

    // ---- Explicit grid ----

    /**
     * Expands {@code repeat(auto-fill | auto-fit, ...)} (integer repeats are expanded by the style engine): the
     * largest number of repetitions that fits {@code available} (at least one), sizing each track by its fixed max
     * or else fixed min function. Marks the indices of auto-fit tracks in {@code autoFit}.
     */
    private static List<GridTrack> expand(List<GridTrack> template, float available, float gap, BitSet autoFit) {
        int repeatIndex = -1;
        for (int i = 0; i < template.size(); i++) if (template.get(i) instanceof GridTrack.Repeat) repeatIndex = i;
        if (repeatIndex < 0) return template;
        GridTrack.Repeat repeat = (GridTrack.Repeat) template.get(repeatIndex);
        int count = 1;
        if (!Float.isNaN(available)) {
            float fixed = 0, unit = 0;
            for (GridTrack t : template) if (!(t instanceof GridTrack.Repeat)) fixed += fixedSize(t, available) + gap;
            for (GridTrack t : repeat.tracks()) unit += fixedSize(t, available) + gap;
            // n repetitions fit when fixed + n * unit - gap <= available.
            if (unit > 0) count = Math.max(1, (int) Math.floor((available - fixed + gap) / unit));
        }
        List<GridTrack> out = new ArrayList<>(template.subList(0, repeatIndex));
        for (int r = 0; r < count; r++) {
            for (GridTrack t : repeat.tracks()) {
                if (repeat.kind() == GridTrack.RepeatKind.AUTO_FIT) autoFit.set(out.size());
                out.add(t);
            }
        }
        out.addAll(template.subList(repeatIndex + 1, template.size()));
        return out;
    }

    private static boolean hasPercent(GridTrack t) {
        return switch (t) {
            case GridTrack.Fixed f -> f.size().hasPercent();
            case GridTrack.MinMax m -> hasPercent(m.min()) || hasPercent(m.max());
            case GridTrack.FitContent f -> f.limit().hasPercent();
            default -> false;
        };
    }

    /** A track's size for counting auto repetitions: its fixed max, else its fixed min, else 0. */
    private static float fixedSize(GridTrack t, float available) {
        Track track = Track.of(t, available);
        if (track.maxKind == GridTrackSizing.Kind.FIXED) return track.maxValue;
        return track.minKind == GridTrackSizing.Kind.FIXED ? track.minValue : 0;
    }
}
