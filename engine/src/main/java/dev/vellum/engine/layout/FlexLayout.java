package dev.vellum.engine.layout;

import dev.vellum.engine.style.Align;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.FlexWrap;
import dev.vellum.engine.style.Length;

import java.util.ArrayList;
import java.util.List;

/**
 * Flexbox: the CSS Flexbox 1 §9 layout algorithm, written once over main and cross {@link Axis}es. Item flex base
 * sizes, hypothetical sizes clamped by min/max with the automatic minimum size, line breaking, resolving flexible
 * lengths with freezing, line and item cross sizes (stretch, baseline), main-axis distribution with auto margins and
 * {@code justify-content}, cross-axis alignment, {@code align-content}, gaps, {@code order} and the reverse
 * directions. Intrinsic sizes follow the spec's intrinsic main size rules as browsers implement them.
 */
final class FlexLayout implements FormattingContext {
    private static final float EPSILON = 0.01f;

    private final LayoutPass pass;

    FlexLayout(LayoutPass pass) {
        this.pass = pass;
    }

    /** One flex item through the algorithm. Sizes are border-box; positions are flex-relative margin-box offsets. */
    private static final class Item {
        final LayoutBox box;
        final ComputedStyle style;
        Align align;
        float flexBasis, minMain, maxMain, hypotheticalMain, targetMain;
        boolean frozen, definiteBasis;
        float violation;
        float hypotheticalCross, cross;
        /** Distance from the margin-box cross start to the baseline, for baseline alignment. */
        float ascent;
        boolean stretched;
        float mainOffset, crossOffset;

        Item(LayoutBox box) {
            this.box = box;
            this.style = box.style;
        }
    }

    private static final class Line {
        final List<Item> items = new ArrayList<>();
        float cross, maxAscent, crossOffset;
    }

    /** The container being laid out or measured: its axes, content-box sizes, gaps and items. */
    private final class Container {
        final LayoutBox box;
        final ComputedStyle style;
        final Axis main, cross;
        final boolean reverse, wrap, wrapReverse;
        /** The content width and the height item percentages resolve against (NaN when indefinite). */
        final float contentWidth, percentHeight;
        /** Inner (content-box) main and cross sizes; NaN while indefinite. */
        float innerMain, innerCross;
        /** The container's used min/max height as content-box sizes (NaN when none). */
        final float minInnerHeight, maxInnerHeight;
        final float mainGap, crossGap;
        final List<Item> items = new ArrayList<>();

        Container(LayoutBox box, float contentWidth, float contentHeight, float percentHeight) {
            this.box = box;
            this.style = box.style;
            this.main = style.flexDirection.isRow() ? Axis.HORIZONTAL : Axis.VERTICAL;
            this.cross = main.other();
            this.reverse = style.flexDirection.isReverse();
            this.wrap = style.flexWrap != FlexWrap.NOWRAP;
            this.wrapReverse = style.flexWrap == FlexWrap.WRAP_REVERSE;
            this.contentWidth = contentWidth;
            this.percentHeight = percentHeight;
            this.innerMain = main.pick(contentWidth, contentHeight);
            this.innerCross = cross.pick(contentWidth, contentHeight);
            this.mainGap = BoxModel.nonNegative(main.gap(style), main.pick(contentWidth, percentHeight));
            this.crossGap = BoxModel.nonNegative(cross.gap(style), cross.pick(contentWidth, percentHeight));
            float pbHeight = BoxModel.paddingBorderHeight(box);
            this.minInnerHeight = box.minHeight - pbHeight;
            this.maxInnerHeight = box.maxHeight - pbHeight;
            for (Box c : box.children) {
                LayoutBox child = (LayoutBox) c;
                if (child.outOfFlow) continue;
                Item item = new Item(child);
                BoxModel.resolveEdges(child, contentWidth);
                Align self = Alignment.self(child.style.alignSelf, style.alignItems);
                item.align = self == Align.NORMAL ? Align.STRETCH : self;
                items.add(item);
            }
            items.sort((a, b) -> Integer.compare(a.style.order, b.style.order));
        }

        /** A size property in {@code axis} as a border-box size, NaN when it does not resolve (auto, content...). */
        float size(Item it, Axis axis, Length l) {
            return pass.resolveSize(it.box, axis, l, contentWidth, percentHeight,
                    contentWidth - Axis.HORIZONTAL.margins(it.box));
        }

        float outerHypotheticalMain(Item it) { return it.hypotheticalMain + main.margins(it.box); }
        float outerTargetMain(Item it) { return it.targetMain + main.margins(it.box); }
        float outerCross(Item it) { return it.cross + cross.margins(it.box); }

        boolean autoMargin(Item it, Axis axis, boolean start) {
            return (start ? axis.marginStart(it.style) : axis.marginEnd(it.style)).isAuto();
        }

        /** Stretched items: align-self stretch, auto cross size, no auto cross margins. */
        boolean stretches(Item it) {
            return it.align == Align.STRETCH && cross.size(it.style).isAuto()
                    && !autoMargin(it, cross, true) && !autoMargin(it, cross, false);
        }

        /** The item's min or max size in {@code axis}, or one transferred from the other axis through aspect-ratio. */
        float limit(Item it, Axis axis, boolean max) {
            return pass.sizeLimit(it.box, axis, max, contentWidth, percentHeight,
                    contentWidth - Axis.HORIZONTAL.margins(it.box));
        }

        /** Clamps a cross size by the item's own min/max cross size, floored at its padding and border. */
        float clampCross(Item it, float size) {
            float min = size(it, cross, cross.minSize(it.style)), max = size(it, cross, cross.maxSize(it.style));
            return Math.max(BoxModel.clamp(size, min, max), cross.paddingBorder(it.box));
        }

        /** The item's cross size when known before layout: its own, or a stretch to a definite single line. */
        float definiteCross(Item it) {
            float size = size(it, cross, cross.size(it.style));
            if (Float.isNaN(size) && !wrap && stretches(it) && !Float.isNaN(innerCross)) {
                size = clampCross(it, innerCross - cross.margins(it.box));
            }
            return size;
        }

        /** The width a column item is measured at for its content height: stretched, else fit-content. */
        float measureWidth(Item it) {
            float available = contentWidth - Axis.HORIZONTAL.margins(it.box);
            return stretches(it) && !Float.isNaN(available)
                    ? pass.clampWidth(it.box, available, contentWidth, percentHeight, available)
                    : pass.usedWidth(it.box, available, contentWidth, percentHeight, false);
        }

        /** The item's content-based main size: min/max-content width, or content height at its measure width. */
        float contentMain(Item it, boolean max) {
            if (main.isHorizontal()) return max ? pass.maxContent(it.box) : pass.minContent(it.box);
            return pass.measureContentHeight(it.box, measureWidth(it), contentWidth, percentHeight);
        }
    }

    // ---- Layout ----

    @Override
    public LayoutResult layoutContent(LayoutBox box, float contentWidth, float contentHeight, float percentHeight,
                                      boolean measure) {
        Container c = new Container(box, contentWidth, contentHeight, percentHeight);
        for (Item it : c.items) baseSizes(c, it, true);
        // An auto-height column wraps at its max-height, and is as tall as its longest line's max-content size.
        List<Line> lines = collectLines(c, Float.isNaN(c.innerMain) ? c.maxInnerHeight : c.innerMain);
        if (Float.isNaN(c.innerMain)) {
            float longest = 0;
            for (Line line : lines) longest = Math.max(longest, intrinsicMainSize(c, line.items, true));
            c.innerMain = BoxModel.clamp(longest, c.minInnerHeight, c.maxInnerHeight);
        }
        for (Line line : lines) resolveFlexibleLengths(c, line);
        crossSizes(c, lines);
        for (Line line : lines) alignMain(c, line);
        alignCross(c, lines);
        setStaticPositions(c);

        float baseline = Float.NaN;
        Line firstLine = lines.isEmpty() ? null : lines.get(c.wrapReverse ? lines.size() - 1 : 0);
        Item baselineItem = firstLine == null ? null : baselineItem(c, firstLine);
        for (Line line : lines) {
            for (Item it : line.items) {
                if (measure && it != baselineItem) continue;
                LayoutResult r = place(c, line, it, measure);
                if (it == baselineItem) {
                    float y = itemPosition(c, line, it)[1];
                    baseline = y + (Float.isNaN(r.firstBaseline()) ? r.height() : r.firstBaseline());
                }
            }
        }
        float height = c.main.isHorizontal() ? c.innerCross : c.innerMain;
        return new LayoutResult(height, baseline, baseline, MarginSet.EMPTY, MarginSet.EMPTY, false);
    }

    /** Out-of-flow children are placed as if they were the only item: aligned in the content box. */
    private void setStaticPositions(Container c) {
        LayoutBox box = c.box;
        // The alignment of a lone item in one unit of free space is its alignment factor (flex-relative).
        float mainAlign = Alignment.distribute(mainAlign(c, c.style.justifyContent), 1, 1)[0];
        if (c.reverse) mainAlign = 1 - mainAlign;
        for (Box child : box.children) {
            LayoutBox b = (LayoutBox) child;
            if (!b.outOfFlow) continue;
            Align self = Alignment.self(b.style.alignSelf, c.style.alignItems);
            float crossAlign = Alignment.position(crossAlign(c, self), 1);
            if (c.wrapReverse && self != Align.BASELINE) crossAlign = 1 - crossAlign;
            b.staticPosition = new LayoutBox.Area(box, box.contentX(), box.contentY(),
                    c.main.pick(c.innerMain, c.innerCross), c.main.pick(c.innerCross, c.innerMain),
                    c.main.pick(mainAlign, crossAlign), c.main.pick(crossAlign, mainAlign));
        }
    }

    /**
     * Flex base size and hypothetical main size (§9.2.3), with the automatic minimum size (§4.5). {@code max}
     * says whether a content-based basis uses the max-content (layout) or min-content (intrinsic sizing) size.
     */
    private void baseSizes(Container c, Item it, boolean max) {
        ComputedStyle s = it.style;
        Axis main = c.main;
        float pb = main.paddingBorder(it.box);
        Length basis = s.flexBasis.isAuto() ? main.size(s) : s.flexBasis;
        float b = c.size(it, main, basis);
        if (Float.isNaN(b)) b = BoxModel.transfer(it.box, c.definiteCross(it), c.cross);
        it.definiteBasis = !Float.isNaN(b);
        if (Float.isNaN(b)) b = c.contentMain(it, max);
        it.flexBasis = Math.max(b, pb);

        // The item's own min/max bound its flexible length; limits transferred from the cross axis through
        // aspect-ratio only bound its hypothetical size (and cap its automatic minimum).
        it.maxMain = c.size(it, main, main.maxSize(s));
        float limitMax = c.limit(it, main, true);
        float min = c.size(it, main, main.minSize(s));
        if (main.minSize(s).isAuto() && !s.isScrollContainer()) {
            // Content-based minimum: the min-content size, capped by a specified or transferred size and the max.
            min = c.contentMain(it, false);
            float specified = c.size(it, main, main.size(s));
            if (!Float.isNaN(specified)) min = Math.min(min, specified);
            float transferred = BoxModel.transfer(it.box, c.definiteCross(it), c.cross);
            if (!Float.isNaN(transferred)) min = Math.min(min, transferred);
            if (!Float.isNaN(limitMax)) min = Math.min(min, limitMax);
        }
        it.minMain = Math.max(BoxModel.or(min, 0), pb);
        float limitMin = Math.max(it.minMain, BoxModel.or(c.limit(it, main, false), 0));
        it.hypotheticalMain = Math.max(BoxModel.clamp(it.flexBasis, limitMin, limitMax), pb);
    }

    /** Collects items into lines (§9.3.5) no longer than {@code limit}; with no limit (NaN), into one line. */
    private List<Line> collectLines(Container c, float limit) {
        List<Line> lines = new ArrayList<>();
        Line line = null;
        float used = 0;
        boolean wraps = c.wrap && !Float.isNaN(limit);
        for (Item it : c.items) {
            float outer = c.outerHypotheticalMain(it);
            if (line == null || (wraps && used + c.mainGap + outer > limit + EPSILON)) {
                line = new Line();
                lines.add(line);
                used = outer;
            } else {
                used += c.mainGap + outer;
            }
            line.items.add(it);
        }
        return lines;
    }

    /** Resolves the flexible lengths of a line's items (§9.7): grow or shrink with freezing of min/max violations. */
    private void resolveFlexibleLengths(Container c, Line line) {
        float gaps = c.mainGap * (line.items.size() - 1);
        float hypothetical = gaps;
        for (Item it : line.items) hypothetical += c.outerHypotheticalMain(it);
        boolean growing = hypothetical < c.innerMain, shrinking = hypothetical > c.innerMain;
        for (Item it : line.items) {
            it.targetMain = it.hypotheticalMain;
            float factor = growing ? grow(it) : shrink(it);
            it.frozen = (!growing && !shrinking) || factor == 0
                    || (growing && it.flexBasis > it.hypotheticalMain)
                    || (shrinking && it.flexBasis < it.hypotheticalMain);
        }
        float initialFree = c.innerMain - usedSpace(c, line, gaps);
        while (true) {
            float factors = 0, scaledShrink = 0;
            boolean anyUnfrozen = false;
            for (Item it : line.items) {
                if (it.frozen) continue;
                anyUnfrozen = true;
                factors += growing ? grow(it) : shrink(it);
                scaledShrink += shrink(it) * innerBasis(c, it);
            }
            if (!anyUnfrozen) break;
            float free = c.innerMain - usedSpace(c, line, gaps);
            if (factors < 1 && Math.abs(initialFree * factors) < Math.abs(free)) free = initialFree * factors;

            float totalViolation = 0;
            for (Item it : line.items) {
                if (it.frozen) continue;
                if (growing && factors > 0) {
                    it.targetMain = it.flexBasis + free * grow(it) / factors;
                } else if (shrinking && scaledShrink > 0) {
                    it.targetMain = it.flexBasis + free * shrink(it) * innerBasis(c, it) / scaledShrink;
                }
                float clamped = Math.max(BoxModel.clamp(it.targetMain, it.minMain, it.maxMain), 0);
                it.violation = clamped - it.targetMain;
                it.targetMain = clamped;
                totalViolation += it.violation;
            }
            for (Item it : line.items) {
                if (it.frozen) continue;
                it.frozen = totalViolation == 0 || (totalViolation > 0 ? it.violation > 0 : it.violation < 0);
            }
        }
    }

    private static float grow(Item it) { return Math.max(0, it.style.flexGrow); }
    private static float shrink(Item it) { return Math.max(0, it.style.flexShrink); }

    private static float innerBasis(Container c, Item it) {
        return it.flexBasis - c.main.paddingBorder(it.box);
    }

    /** Space taken on a line: frozen items at their target size, the others at their flex base size. */
    private static float usedSpace(Container c, Line line, float gaps) {
        float used = gaps;
        for (Item it : line.items) {
            used += (it.frozen ? it.targetMain : it.flexBasis) + c.main.margins(it.box);
        }
        return used;
    }

    /**
     * Hypothetical and used cross sizes of items, line cross sizes with baseline alignment and
     * {@code align-content: stretch}, and the container's inner cross size (§9.4).
     */
    private void crossSizes(Container c, List<Line> lines) {
        Axis cross = c.cross;
        for (Line line : lines) {
            for (Item it : line.items) {
                float pb = cross.paddingBorder(it.box);
                float size;
                if (cross.isHorizontal()) {
                    float transferred = it.style.width.isAuto()
                            ? BoxModel.transfer(it.box, it.targetMain, Axis.VERTICAL) : Float.NaN;
                    size = !Float.isNaN(transferred) ? c.clampCross(it, transferred)
                            : pass.usedWidth(it.box, c.contentWidth - cross.margins(it.box), c.contentWidth,
                            c.percentHeight, false);
                } else {
                    size = pass.measureHeight(it.box, it.targetMain, Float.NaN, c.contentWidth, c.percentHeight);
                }
                it.hypotheticalCross = Math.max(size, pb);
                if (participatesInBaseline(c, it)) {
                    LayoutResult r = pass.measure(it.box, it.targetMain, Float.NaN, c.contentWidth, c.percentHeight);
                    it.ascent = it.box.marginTop + (Float.isNaN(r.firstBaseline()) ? r.height() : r.firstBaseline());
                }
            }
        }

        boolean singleLineDefinite = !c.wrap && !Float.isNaN(c.innerCross);
        float total = c.crossGap * Math.max(0, lines.size() - 1);
        for (Line line : lines) {
            float maxDescent = 0, maxOuter = 0;
            for (Item it : line.items) {
                float outer = it.hypotheticalCross + cross.margins(it.box);
                if (participatesInBaseline(c, it)) {
                    line.maxAscent = Math.max(line.maxAscent, it.ascent);
                    maxDescent = Math.max(maxDescent, outer - it.ascent);
                } else {
                    maxOuter = Math.max(maxOuter, outer);
                }
            }
            line.cross = singleLineDefinite ? c.innerCross : Math.max(maxOuter, line.maxAscent + maxDescent);
            if (!c.wrap && cross == Axis.VERTICAL) {
                // A single line is clamped by the container's min/max cross size.
                line.cross = BoxModel.clamp(line.cross, c.minInnerHeight, c.maxInnerHeight);
            }
            total += line.cross;
        }
        if (Float.isNaN(c.innerCross)) {
            c.innerCross = Math.max(0, BoxModel.clamp(total, c.minInnerHeight, c.maxInnerHeight));
        }
        Align alignContent = c.style.alignContent;
        if (c.wrap && (alignContent == Align.STRETCH || alignContent == Align.NORMAL) && c.innerCross > total) {
            float extra = (c.innerCross - total) / lines.size();
            for (Line line : lines) line.cross += extra;
        }

        for (Line line : lines) {
            for (Item it : line.items) {
                it.stretched = c.stretches(it);
                it.cross = it.stretched
                        ? c.clampCross(it, line.cross - cross.margins(it.box))
                        : it.hypotheticalCross;
            }
        }
    }

    /** Baseline alignment applies to row items without auto cross margins (wrap-reverse lines align at their start). */
    private static boolean participatesInBaseline(Container c, Item it) {
        return it.align == Align.BASELINE && c.main.isHorizontal() && !c.wrapReverse
                && !c.autoMargin(it, c.cross, true) && !c.autoMargin(it, c.cross, false);
    }

    /** Main-axis alignment (§9.5): auto margins take positive free space, else {@code justify-content}. */
    private void alignMain(Container c, Line line) {
        Axis main = c.main;
        float free = c.innerMain - c.mainGap * (line.items.size() - 1);
        int autoMargins = 0;
        for (Item it : line.items) {
            free -= c.outerTargetMain(it);
            if (c.autoMargin(it, main, true)) autoMargins++;
            if (c.autoMargin(it, main, false)) autoMargins++;
        }
        float[] distribution = {0, 0};
        if (autoMargins > 0) {
            float share = Math.max(0, free) / autoMargins;
            for (Item it : line.items) {
                main.setMargins(it.box, main.marginStart(it.box) + (c.autoMargin(it, main, true) ? share : 0),
                        main.marginEnd(it.box) + (c.autoMargin(it, main, false) ? share : 0));
            }
        } else {
            distribution = Alignment.distribute(mainAlign(c, c.style.justifyContent), free, line.items.size());
        }
        float offset = distribution[0];
        for (Item it : line.items) {
            it.mainOffset = offset;
            offset += c.outerTargetMain(it) + c.mainGap + distribution[1];
        }
    }

    /** Cross-axis alignment (§9.6): auto margins, align-self within lines, then align-content for the lines. */
    private void alignCross(Container c, List<Line> lines) {
        Axis cross = c.cross;
        for (Line line : lines) {
            for (Item it : line.items) {
                float free = line.cross - c.outerCross(it);
                it.crossOffset = 0;
                if (BoxModel.resolveAutoMargins(it.box, cross, free)) continue;
                it.crossOffset = participatesInBaseline(c, it) ? line.maxAscent - it.ascent
                        : Alignment.position(crossAlign(c, it.align), free);
            }
        }
        float total = c.crossGap * (lines.size() - 1);
        for (Line line : lines) total += line.cross;
        float[] distribution = c.wrap
                ? Alignment.distribute(crossAlign(c, c.style.alignContent), c.innerCross - total, lines.size())
                : new float[] {0, 0};
        float offset = distribution[0];
        for (Line line : lines) {
            line.crossOffset = offset;
            offset += line.cross + c.crossGap + distribution[1];
        }
    }

    private static Align mainAlign(Container c, Align align) {
        return flexRelative(align, c.reverse, c.main.isHorizontal());
    }

    private static Align crossAlign(Container c, Align align) {
        return flexRelative(align, c.wrapReverse, c.cross.isHorizontal());
    }

    /**
     * Maps physical alignment values ({@code start}/{@code end}, and left/right in a horizontal axis) to
     * flex-relative ones, which swap in a reversed axis (reverse direction, or wrap-reverse in the cross axis).
     */
    private static Align flexRelative(Align align, boolean reversed, boolean horizontal) {
        boolean start = align == Align.START || (horizontal && align == Align.LEFT);
        boolean end = align == Align.END || (horizontal && align == Align.RIGHT);
        if (start) return reversed ? Align.FLEX_END : Align.FLEX_START;
        if (end) return reversed ? Align.FLEX_START : Align.FLEX_END;
        return align;
    }

    /** Border-box {x, y} of an item in the container's border-box space. */
    private static float[] itemPosition(Container c, Line line, Item it) {
        float mainOffset = c.reverse ? c.innerMain - it.mainOffset - c.outerTargetMain(it) : it.mainOffset;
        float crossOffset = line.crossOffset + it.crossOffset;
        if (c.wrapReverse) crossOffset = c.innerCross - crossOffset - c.outerCross(it);
        float mainPos = c.main.contentStart(c.box) + mainOffset + c.main.marginStart(it.box);
        float crossPos = c.cross.contentStart(c.box) + crossOffset + c.cross.marginStart(it.box);
        return new float[] {c.main.pick(mainPos, crossPos), c.main.pick(crossPos, mainPos)};
    }

    /**
     * Lays out (or measures) an item at its final size and, for real layouts, positions it. Its height is imposed
     * but only definite (for its children's percentages) as §9.8 and browsers say: a flexed main size when the
     * container's main size or the item's flex basis is definite, a stretched cross size when the container is
     * single-line with a definite cross size.
     */
    private LayoutResult place(Container c, Line line, Item it, boolean measure) {
        float width = c.main.pick(it.targetMain, it.cross);
        float height = c.main.pick(it.cross, it.targetMain);
        boolean definite = c.main.isHorizontal() ? it.stretched && !c.wrap && !Float.isNaN(c.percentHeight)
                : it.definiteBasis || !Float.isNaN(c.percentHeight);
        if (measure) return pass.measure(it.box, width, height, definite, c.contentWidth, c.percentHeight);
        LayoutResult r = pass.layout(it.box, width, height, definite, c.contentWidth, c.percentHeight);
        float[] pos = itemPosition(c, line, it);
        it.box.x = pos[0];
        it.box.y = pos[1];
        PositionedLayout.applyRelativeOffset(it.box, c.contentWidth, c.percentHeight);
        return r;
    }

    /** The item that gives the container its baseline: the first baseline-aligned item of the line, else the first. */
    private static Item baselineItem(Container c, Line line) {
        for (Item it : line.items) if (participatesInBaseline(c, it)) return it;
        if (line.items.isEmpty()) return null;
        return line.items.get(c.reverse && !c.main.isHorizontal() ? line.items.size() - 1 : 0);
    }

    // ---- Intrinsic sizes ----

    @Override
    public float intrinsicContentWidth(LayoutBox box, boolean max) {
        // The container's own fixed height still counts (items may stretch to it and transfer it through a ratio).
        ComputedStyle s = box.style;
        float pbHeight = BoxModel.paddingBorder(s, Axis.VERTICAL, Float.NaN);
        float height = BoxModel.borderBoxSize(s, s.height, Float.NaN, pbHeight) - pbHeight;
        Container c = new Container(box, Float.NaN, height, height);
        if (c.main.isHorizontal()) {
            for (Item it : c.items) baseSizes(c, it, max);
            return intrinsicMainSize(c, c.items, max);
        }
        // A column is as wide as its lines side by side; it wraps at its height, else its max-height.
        List<Line> lines;
        if (c.wrap) {
            for (Item it : c.items) baseSizes(c, it, true);
            float maxHeight = BoxModel.borderBoxSize(s, s.maxHeight, Float.NaN, pbHeight) - pbHeight;
            lines = collectLines(c, BoxModel.or(height, maxHeight));
        } else {
            lines = collectLines(c, Float.NaN);
        }
        float width = c.crossGap * Math.max(0, lines.size() - 1);
        for (Line line : lines) {
            float lineWidth = 0;
            for (Item it : line.items) lineWidth = Math.max(lineWidth, pass.contribution(it.box, max));
            width += lineWidth;
        }
        return width;
    }

    /**
     * The container's min- or max-content main size from its items' contributions (CSS Flexbox §9.9.1 as Chrome and
     * Firefox implement it: each item contributes its content size clamped by its min/max and, when it cannot grow
     * or shrink, its flex basis). Items' base sizes must be computed.
     */
    private float intrinsicMainSize(Container c, List<Item> items, boolean max) {
        Axis main = c.main;
        float sum = 0, longest = 0;
        for (Item it : items) {
            float margins = main.margins(it.box);
            float pb = main.paddingBorder(it.box);
            float styleMin = c.size(it, main, main.minSize(it.style));
            if (!max && c.wrap) {
                // Under a min-content constraint every item of a wrapping container gets its own line.
                longest = Math.max(longest, Math.max(BoxModel.clamp(it.flexBasis, styleMin, Float.NaN) + margins, pb));
                continue;
            }
            float preferred = c.size(it, main, main.size(it.style));
            float styleMax = c.size(it, main, main.maxSize(it.style));
            float clampingBasis = main.isHorizontal() ? Math.max(it.flexBasis, BoxModel.or(preferred, 0))
                    : it.flexBasis;
            float basisMin = shrink(it) == 0 ? clampingBasis : Float.NaN;
            float basisMax = grow(it) == 0 ? clampingBasis : Float.NaN;
            float minSize = Math.max(maxOf(styleMin, basisMin, it.minMain), it.minMain);
            float maxSize = Float.isNaN(styleMax) ? basisMax
                    : Float.isNaN(basisMax) ? styleMax : Math.min(styleMax, basisMax);
            float contribution;
            if (!Float.isNaN(preferred) && !Float.isNaN(maxSize) && (maxSize <= minSize || maxSize <= preferred)) {
                contribution = Math.max(Math.min(preferred, maxSize), minSize) + margins;
            } else if (!Float.isNaN(maxSize) && maxSize <= minSize) {
                contribution = minSize + margins;
            } else if (it.style.isScrollContainer()) {
                contribution = it.flexBasis + margins;
            } else {
                // A cross size transferred through aspect-ratio floors the content size.
                float transferred = BoxModel.transfer(it.box, c.definiteCross(it), c.cross);
                float content = !Float.isNaN(preferred) ? Math.max(preferred, pb)
                        : BoxModel.clamp(c.contentMain(it, max), transferred, Float.NaN);
                if (!main.isHorizontal()) content = Math.max(content, it.flexBasis);
                contribution = BoxModel.clamp(content + margins, styleMin, styleMax);
            }
            // An item that would have to shrink but cannot (zero inner basis) keeps its basis.
            if (contribution < it.flexBasis && innerBasis(c, it) * Math.max(1, shrink(it)) == 0) {
                contribution = it.flexBasis;
            }
            sum += contribution;
        }
        if (!max && c.wrap) return longest;
        return sum + c.mainGap * Math.max(0, items.size() - 1);
    }

    /** The largest of {@code a} and {@code b} ignoring NaN, or {@code fallback} when both are NaN. */
    private static float maxOf(float a, float b, float fallback) {
        if (Float.isNaN(a)) return Float.isNaN(b) ? fallback : b;
        return Float.isNaN(b) ? a : Math.max(a, b);
    }
}
