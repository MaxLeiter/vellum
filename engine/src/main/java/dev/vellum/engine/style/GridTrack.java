package dev.vellum.engine.style;

import java.util.List;

/** A track sizing function from {@code grid-template-columns/rows} or {@code grid-auto-columns/rows}. */
public sealed interface GridTrack {
    /** A length or percentage track. */
    record Fixed(Length size) implements GridTrack {}
    /** A flexible {@code <n>fr} track. */
    record Flex(float fr) implements GridTrack {}
    /** {@code auto}, {@code min-content} or {@code max-content}: carried as the corresponding {@link Length} keyword. */
    record Keyword(Length keyword) implements GridTrack {}
    /** {@code minmax(min, max)}; each side is a non-repeat track. */
    record MinMax(GridTrack min, GridTrack max) implements GridTrack {}
    /** {@code fit-content(limit)}. */
    record FitContent(Length limit) implements GridTrack {}
    /**
     * {@code repeat(count, tracks)}. Integer repeats are expanded when the style is computed; this record survives
     * only for {@code auto-fill} / {@code auto-fit}, which need the container size and are expanded during layout.
     */
    record Repeat(RepeatKind kind, int count, List<GridTrack> tracks, List<List<String>> lineNames) implements GridTrack {}

    enum RepeatKind { COUNT, AUTO_FILL, AUTO_FIT }
}
