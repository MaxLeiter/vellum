package dev.vellum.engine.scene;

/**
 * A block in a {@code <scene>}: its position in block coordinates, its block state in {@code /setblock} syntax
 * ({@code minecraft:furnace[facing=north,lit=true]}), and its block entity's data as SNBT, or null for none.
 */
public record SceneBlock(int x, int y, int z, String state, String nbt) {
    /** The block's id ({@link #id(String)}). */
    public String id() {
        return id(state);
    }

    /**
     * The id of a block state in {@code /setblock} syntax: the state without its properties, in {@code minecraft}
     * unless it names a namespace.
     */
    public static String id(String state) {
        int bracket = state.indexOf('[');
        String id = (bracket < 0 ? state : state.substring(0, bracket)).strip();
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }
}
