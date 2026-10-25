package dev.vellum.engine.scene;

/**
 * A block in a {@code <scene>}: its position in block coordinates, its block state in {@code /setblock} syntax
 * ({@code minecraft:furnace[facing=north,lit=true]}), and its block entity's data as SNBT, or null for none.
 */
public record SceneBlock(int x, int y, int z, String state, String nbt) {
    /** The block's id: its state without the properties. */
    public String id() {
        int bracket = state.indexOf('[');
        return bracket < 0 ? state : state.substring(0, bracket);
    }
}
