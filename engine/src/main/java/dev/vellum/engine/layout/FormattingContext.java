package dev.vellum.engine.layout;

/** A layout algorithm for the content of a box (block flow, flex, grid, leaf). One instance per pass. */
interface FormattingContext {
    /**
     * Lays out (or, when {@code measure}, only measures) the in-flow content of {@code box}, whose padding and
     * border are resolved. Children are positioned in the box's border-box space.
     *
     * @param contentWidth the definite content-box width
     * @param contentHeight the content-box height when known before the content (fixed, or imposed by the
     *                      parent), else NaN
     * @param percentHeight the height children's percentages resolve against: {@code contentHeight} when it is
     *                      definite, NaN when it is not (an auto height, or one imposed by a parent whose own size
     *                      is indefinite, such as a flexed item in an auto-height column)
     * @return the result with {@code height} = the content-box height the content needs (the auto height)
     */
    LayoutResult layoutContent(LayoutBox box, float contentWidth, float contentHeight, float percentHeight,
                               boolean measure);

    /** The min-content ({@code max == false}) or max-content width of the box's content box. */
    float intrinsicContentWidth(LayoutBox box, boolean max);
}
