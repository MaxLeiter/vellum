package dev.vellum.preview.host;

/**
 * Previewer content whose tooltip starts with a name, as in game: an {@code <item tooltip>}'s item, or the block
 * hovered in a {@code <scene>}. The tooltip painter shows its translation.
 */
public interface NamedContent {
    /** The id it names now, or null. */
    String nameId();

    /** Whether the id is a block's, whose block translation comes before its item's. */
    default boolean namesBlock() { return false; }
}
