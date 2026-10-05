package dev.vellum.engine.layout;

import dev.vellum.engine.style.Align;

/** CSS Box Alignment helpers shared by flex and grid. Offsets are measured from the start of the alignment space. */
final class Alignment {
    private Alignment() {}

    /**
     * Content distribution ({@code justify-content}, {@code align-content}) of {@code free} space among
     * {@code count} subjects: returns {offset of the first subject, extra space between subjects}. Normal, stretch
     * and baseline behave as start. The space-* values fall back as CSS Box Alignment says: space-between to start;
     * space-around and space-evenly to "safe center" (start when the space is negative).
     */
    static float[] distribute(Align align, float free, int count) {
        if (free <= 0 && (align == Align.SPACE_BETWEEN || align == Align.SPACE_AROUND || align == Align.SPACE_EVENLY)) {
            return new float[] {0, 0};
        }
        return switch (align) {
            case SPACE_BETWEEN -> count > 1 ? new float[] {0, free / (count - 1)} : new float[] {0, 0};
            case SPACE_AROUND -> count > 0 ? new float[] {free / count / 2, free / count} : new float[] {free / 2, 0};
            case SPACE_EVENLY -> new float[] {free / (count + 1), free / (count + 1)};
            default -> new float[] {position(align, free), 0};
        };
    }

    /** An item's {@code align-self}/{@code justify-self}: {@code auto} takes the container's {@code *-items}. */
    static Align self(Align self, Align items) {
        return self == Align.AUTO ? items : self;
    }

    /** Self alignment: the offset of one subject in {@code free} space (start, center or end; others as start). */
    static float position(Align align, float free) {
        return switch (align) {
            case CENTER -> free / 2;
            case END, FLEX_END, RIGHT -> free;
            default -> 0;
        };
    }
}
