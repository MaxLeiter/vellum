package dev.vellum.engine.layout;

import dev.vellum.engine.host.FontMetrics;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Length;

/**
 * One layout pass: dispatches boxes to their formatting context, caches measurements, and implements the sizing
 * rules every formatting context shares (used widths, definite heights, min/max clamping, replaced sizing,
 * intrinsic sizes and contributions).
 *
 * <p>Layout is width-in, height-out: a parent always decides a child's border-box width (using {@link #usedWidth},
 * shrink-to-fit or its own algorithm), and may impose its height. {@link #measure} runs a box's algorithm without
 * committing it and caches the result per inputs; {@link #layout} runs it for real. Every box gets exactly one
 * {@link #layout} per pass, after all measurements of it, so measurements never need to be undone.
 */
final class LayoutPass {
    final FontMetrics fonts;
    final float viewportWidth, viewportHeight;
    final InlineLayout inline = new InlineLayout(this);
    private final BlockLayout block = new BlockLayout(this);
    private final FlexLayout flex = new FlexLayout(this);
    private final GridLayout grid = new GridLayout(this);
    private final LeafLayout leaf = new LeafLayout(this);

    LayoutPass(FontMetrics fonts, float viewportWidth, float viewportHeight) {
        this.fonts = fonts;
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;
    }

    private FormattingContext formattingContext(LayoutBox box) {
        return switch (box.context) {
            case FLOW -> block;
            case FLEX -> flex;
            case GRID -> grid;
            case LEAF -> leaf;
        };
    }

    // ---- Layout ----

    /**
     * Lays out {@code box} for real: sets its size and baselines and lays out its subtree. The caller positions it.
     *
     * @param width border-box width
     * @param height border-box height imposed by the parent (flex/grid stretch, flexed main size), or NaN to let
     *               the box size itself from its style and content
     * @param heightDefinite whether an imposed height counts as definite for the children's percentages
     * @param cbWidth containing block width for percentages (NaN if unknown)
     * @param cbHeight containing block height for percentages (NaN if indefinite)
     */
    LayoutResult layout(LayoutBox box, float width, float height, boolean heightDefinite, float cbWidth,
                        float cbHeight) {
        LayoutResult r = run(box, width, height, heightDefinite, cbWidth, cbHeight, false);
        box.width = width;
        box.height = r.height();
        box.baseline = r.firstBaseline();
        box.lastBaseline = r.lastBaseline();
        return r;
    }

    /** {@link #layout} with an imposed height (if any) that is definite. */
    LayoutResult layout(LayoutBox box, float width, float height, float cbWidth, float cbHeight) {
        return layout(box, width, height, true, cbWidth, cbHeight);
    }

    /** What {@link #layout} would produce, without committing it. Cached for the pass. */
    LayoutResult measure(LayoutBox box, float width, float height, boolean heightDefinite, float cbWidth,
                         float cbHeight) {
        LayoutResult r = box.cached(width, height, heightDefinite, cbWidth, cbHeight);
        if (r == null) {
            r = run(box, width, height, heightDefinite, cbWidth, cbHeight, true);
            box.cache(width, height, heightDefinite, cbWidth, cbHeight, r);
        }
        return r;
    }

    /** {@link #measure} with an imposed height (if any) that is definite. */
    LayoutResult measure(LayoutBox box, float width, float height, float cbWidth, float cbHeight) {
        return measure(box, width, height, true, cbWidth, cbHeight);
    }

    /** The border-box height {@code box} gets at {@code width}: a shorthand for {@link #measure}. */
    float measureHeight(LayoutBox box, float width, float height, float cbWidth, float cbHeight) {
        return measure(box, width, height, cbWidth, cbHeight).height();
    }

    /**
     * The height of the box's content plus padding and border at {@code width}, ignoring its own height, min/max
     * height and aspect-ratio: the content size used for flex base sizes and automatic minimum sizes.
     */
    float measureContentHeight(LayoutBox box, float width, float cbWidth, float cbHeight) {
        return measure(box, width, CONTENT_HEIGHT, false, cbWidth, cbHeight).height();
    }

    /** The {@code height} argument of {@link #measure} that requests the content-based height. */
    private static final float CONTENT_HEIGHT = Float.NEGATIVE_INFINITY;

    private LayoutResult run(LayoutBox box, float width, float height, boolean heightDefinite, float cbWidth,
                             float cbHeight, boolean measure) {
        BoxModel.resolvePaddingBorder(box, cbWidth);
        ComputedStyle s = box.style;
        float pbHeight = BoxModel.paddingBorderHeight(box);
        // The height known before the content: imposed by the parent, the box's own, or from its aspect-ratio. A
        // ratio-derived height still grows to fit the content unless min-height says otherwise.
        float definite = Float.NaN;
        boolean ratioDependent = false;
        if (height == CONTENT_HEIGHT) {
            box.minHeight = box.maxHeight = Float.NaN;
        } else {
            setHeightLimits(box, cbWidth, cbHeight);
            definite = Float.isNaN(height) ? definiteHeight(box, width, cbHeight) : height;
            float fromRatio = Float.isNaN(definite) && !box.isReplaced()
                    ? BoxModel.transfer(box, width, Axis.HORIZONTAL) : Float.NaN;
            if (!Float.isNaN(fromRatio)) {
                definite = clampHeight(box, fromRatio);
                ratioDependent = s.minHeight.isAuto() && !s.isScrollContainer();
            }
        }
        float contentWidth = Math.max(0, width - BoxModel.paddingBorderWidth(box));
        float contentHeight = Float.isNaN(definite) ? Float.NaN : Math.max(0, definite - pbHeight);
        boolean imposed = !Float.isNaN(height) && height != CONTENT_HEIGHT;
        float percentHeight = imposed && !heightDefinite ? Float.NaN : contentHeight;
        LayoutResult content = formattingContext(box).layoutContent(box, contentWidth, contentHeight, percentHeight,
                measure);
        float autoHeight = content.height() + pbHeight;
        float used;
        if (Float.isNaN(definite)) {
            used = clampHeight(box, autoHeight);
        } else if (ratioDependent) {
            float ownMax = BoxModel.borderBoxSize(s, s.maxHeight, cbHeight, pbHeight);
            used = Math.max(definite, BoxModel.clamp(autoHeight, Float.NaN, ownMax));
        } else {
            used = definite;
        }
        boolean minHeightWon = used > autoHeight;
        return new LayoutResult(used, content.firstBaseline(), content.lastBaseline(), content.top(),
                minHeightWon ? MarginSet.EMPTY : content.bottom(), content.collapsesThrough() && used == 0);
    }

    // ---- Widths ----

    /**
     * The used border-box width of a box from its style: a definite or keyword {@code width}; else for replaced
     * elements their natural size; else a width transferred from a definite height through {@code aspect-ratio};
     * else {@code available} ({@code fill}, block-level boxes) or shrink-to-fit within it. Then clamped by
     * {@code min-width}/{@code max-width}. The box's edges must be resolved.
     *
     * @param available the width an auto width fills: the containing block minus the box's margins
     */
    float usedWidth(LayoutBox box, float available, float cbWidth, float cbHeight, boolean fill) {
        ComputedStyle s = box.style;
        float w = resolveSize(box, Axis.HORIZONTAL, s.width, cbWidth, cbHeight, available);
        if (Float.isNaN(w) && box.isReplaced()) {
            w = replacedAutoWidth(box, cbHeight);
        } else if (Float.isNaN(w)) {
            float height = resolveSize(box, Axis.VERTICAL, s.height, cbWidth, cbHeight, available);
            w = BoxModel.transfer(box, height, Axis.VERTICAL);
            if (Float.isNaN(w)) {
                w = fill ? available : fitContent(box, available);
            } else if (s.minWidth.isAuto() && !s.isScrollContainer()) {
                // The automatic minimum in a ratio-dependent axis is the min-content size, capped by max-width.
                float max = resolveSize(box, Axis.HORIZONTAL, s.maxWidth, cbWidth, cbHeight, available);
                w = Math.max(w, BoxModel.clamp(minContent(box), Float.NaN, max));
            }
        }
        return clampWidth(box, w, cbWidth, cbHeight, available);
    }

    /** Clamps a border-box width by the box's min/max width (see {@link #sizeLimit}), floored at padding + border. */
    float clampWidth(LayoutBox box, float width, float cbWidth, float cbHeight, float available) {
        float min = sizeLimit(box, Axis.HORIZONTAL, false, cbWidth, cbHeight, available);
        float max = sizeLimit(box, Axis.HORIZONTAL, true, cbWidth, cbHeight, available);
        return Math.max(BoxModel.clamp(width, min, max), BoxModel.paddingBorderWidth(box));
    }

    /**
     * The box's min- or max-size in {@code axis} as a border-box size, or NaN when it has none. Without one of its
     * own, a box with an {@code aspect-ratio} takes the other axis's limit through the ratio.
     */
    float sizeLimit(LayoutBox box, Axis axis, boolean max, float cbWidth, float cbHeight, float available) {
        ComputedStyle s = box.style;
        float v = resolveSize(box, axis, max ? axis.maxSize(s) : axis.minSize(s), cbWidth, cbHeight, available);
        if (Float.isNaN(v) && !box.isReplaced()) {
            Axis other = axis.other();
            float o = resolveSize(box, other, max ? other.maxSize(s) : other.minSize(s), cbWidth, cbHeight, available);
            v = BoxModel.transfer(box, o, other);
        }
        return v;
    }

    /** A size property in {@code axis} as a border-box size: {@link #resolveWidth} or a resolved height. */
    float resolveSize(LayoutBox box, Axis axis, Length l, float cbWidth, float cbHeight, float available) {
        return axis.isHorizontal() ? resolveWidth(box, l, cbWidth, available, BoxModel.paddingBorderWidth(box))
                : BoxModel.borderBoxSize(box.style, l, cbHeight, BoxModel.paddingBorderHeight(box));
    }

    /** Shrink-to-fit: {@code min(max(min-content, available), max-content)}; max-content when available is unknown. */
    float fitContent(LayoutBox box, float available) {
        if (Float.isNaN(available)) return maxContent(box);
        return Math.min(Math.max(minContent(box), available), maxContent(box));
    }

    /**
     * A width-like length (width, min-width, max-width, flex-basis) as a border-box width: lengths and percentages
     * (of {@code cbWidth}), and the {@code min-content}/{@code max-content}/{@code fit-content} keywords. NaN for
     * auto, none, and percentages of an unknown width.
     */
    float resolveWidth(LayoutBox box, Length l, float cbWidth, float available, float paddingBorder) {
        return switch (l.kind) {
            case FIXED -> BoxModel.borderBoxSize(box.style, l, cbWidth, paddingBorder);
            case MIN_CONTENT -> minContent(box);
            case MAX_CONTENT -> maxContent(box);
            case FIT_CONTENT -> fitContent(box, available);
            case AUTO, NONE -> Float.NaN;
        };
    }

    // ---- Heights ----

    /**
     * The box's own definite border-box height at {@code width}, clamped by its min/max height: a fixed
     * {@code height} (or a percentage of a definite {@code cbHeight}) or the height of a replaced element; NaN when
     * it depends on content. Needs the limits resolved by {@link #setHeightLimits}.
     */
    private float definiteHeight(LayoutBox box, float width, float cbHeight) {
        ComputedStyle s = box.style;
        float h = BoxModel.borderBoxSize(s, s.height, cbHeight, BoxModel.paddingBorderHeight(box));
        if (Float.isNaN(h) && box.isReplaced()) h = replacedAutoHeight(box, width);
        return Float.isNaN(h) ? h : clampHeight(box, h);
    }

    /** Resolves the box's used min/max border-box heights into {@link LayoutBox#minHeight}/{@code maxHeight}. */
    private void setHeightLimits(LayoutBox box, float cbWidth, float cbHeight) {
        box.minHeight = sizeLimit(box, Axis.VERTICAL, false, cbWidth, cbHeight, Float.NaN);
        box.maxHeight = sizeLimit(box, Axis.VERTICAL, true, cbWidth, cbHeight, Float.NaN);
    }

    /** Clamps a border-box height by the box's min/max height (see {@link #sizeLimit}), floored at padding + border. */
    float clampHeight(LayoutBox box, float height, float cbWidth, float cbHeight) {
        setHeightLimits(box, cbWidth, cbHeight);
        return clampHeight(box, height);
    }

    /** Clamps by the limits {@link #setHeightLimits} resolved. */
    private static float clampHeight(LayoutBox box, float height) {
        return Math.max(BoxModel.clamp(height, box.minHeight, box.maxHeight), BoxModel.paddingBorderHeight(box));
    }

    // ---- Replaced elements ----

    /** Ratio (width / height) of a replaced element's content box: {@code aspect-ratio}, else its natural ratio. */
    private static float replacedRatio(LayoutBox box) {
        if (!Float.isNaN(box.style.aspectRatio)) return box.style.aspectRatio;
        return box.naturalWidth > 0 && box.naturalHeight > 0 ? box.naturalWidth / box.naturalHeight : Float.NaN;
    }

    /** Border-box width of a replaced element with {@code width: auto} (CSS 2.1 §10.3.2). */
    private float replacedAutoWidth(LayoutBox box, float cbHeight) {
        float content = replacedContentWidth(box, cbHeight, BoxModel.paddingBorderHeight(box));
        return content + BoxModel.paddingBorderWidth(box);
    }

    /** Content width of a replaced element with {@code width: auto}: from a definite height and ratio, else natural. */
    private static float replacedContentWidth(LayoutBox box, float cbHeight, float pbHeight) {
        float height = BoxModel.borderBoxSize(box.style, box.style.height, cbHeight, pbHeight);
        float ratio = replacedRatio(box);
        if (!Float.isNaN(height) && !Float.isNaN(ratio)) return (height - pbHeight) * ratio;
        if (!Float.isNaN(box.naturalWidth)) return box.naturalWidth;
        if (!Float.isNaN(box.naturalHeight) && !Float.isNaN(ratio)) return box.naturalHeight * ratio;
        return 0;
    }

    /** Border-box height of a replaced element with {@code height: auto} at a used width (CSS 2.1 §10.6.2). */
    private float replacedAutoHeight(LayoutBox box, float width) {
        float ratio = replacedRatio(box);
        float contentWidth = Math.max(0, width - BoxModel.paddingBorderWidth(box));
        float content = !Float.isNaN(ratio) ? contentWidth / ratio : BoxModel.or(box.naturalHeight, 0);
        return content + BoxModel.paddingBorderHeight(box);
    }

    // ---- Intrinsic sizes ----

    /** The min-content border-box width of the box's content (ignoring its own width and min/max). */
    float minContent(LayoutBox box) {
        if (Float.isNaN(box.minContent)) measureIntrinsic(box);
        return box.minContent;
    }

    /** The max-content border-box width of the box's content (ignoring its own width and min/max). */
    float maxContent(LayoutBox box) {
        if (Float.isNaN(box.maxContent)) measureIntrinsic(box);
        return box.maxContent;
    }

    private void measureIntrinsic(LayoutBox box) {
        float pb = BoxModel.paddingBorder(box.style, Axis.HORIZONTAL, Float.NaN);
        if (box.isReplaced()) {
            float pbHeight = BoxModel.paddingBorder(box.style, Axis.VERTICAL, Float.NaN);
            float content = replacedContentWidth(box, Float.NaN, pbHeight);
            box.minContent = box.maxContent = content + pb;
            return;
        }
        FormattingContext fc = formattingContext(box);
        box.minContent = fc.intrinsicContentWidth(box, false) + pb;
        box.maxContent = Math.max(box.minContent, fc.intrinsicContentWidth(box, true) + pb);
    }

    /**
     * The box's min- or max-content contribution: its margin-box width when sized under that constraint. A definite
     * {@code width} wins over the content; min/max-width clamp; percentages count as auto.
     */
    float contribution(LayoutBox box, boolean max) {
        ComputedStyle s = box.style;
        float pb = BoxModel.paddingBorder(s, Axis.HORIZONTAL, Float.NaN);
        // Under a min-content constraint the available space is zero; under max-content it is infinite.
        float available = max ? Float.POSITIVE_INFINITY : 0;
        float w = resolveWidth(box, s.width, Float.NaN, available, pb);
        if (Float.isNaN(w)) w = max ? maxContent(box) : minContent(box);
        float min = resolveWidth(box, s.minWidth, Float.NaN, available, pb);
        float maxW = resolveWidth(box, s.maxWidth, Float.NaN, available, pb);
        return Math.max(BoxModel.clamp(w, min, maxW), pb) + BoxModel.margins(s, Axis.HORIZONTAL, Float.NaN);
    }
}
