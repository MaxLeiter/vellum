package dev.vellum.engine.layout;

/**
 * What laying out (or measuring) a box tells its parent.
 *
 * @param height border-box height
 * @param firstBaseline offset of the first baseline from the border-box top, NaN if none
 * @param lastBaseline offset of the last line's baseline from the border-box top, NaN if none
 * @param top collapsed margins of in-flow descendants that adjoin this box's top margin (excluding its own)
 * @param bottom likewise for the bottom margin
 * @param collapsesThrough the box is empty and its top and bottom margins collapse through it
 */
record LayoutResult(float height, float firstBaseline, float lastBaseline, MarginSet top, MarginSet bottom,
                    boolean collapsesThrough) {}
