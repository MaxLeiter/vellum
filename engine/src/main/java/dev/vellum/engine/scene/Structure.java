package dev.vellum.engine.scene;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A structure file, as structure blocks save them ({@code StructureTemplate}'s NBT): its size and blocks, with their
 * states in {@code /setblock} syntax and block entity data as SNBT. Air and structure voids are left out, and so are
 * entities.
 */
record Structure(int sizeX, int sizeY, int sizeZ, List<SceneBlock> blocks) {
    static Structure read(byte[] bytes) throws IOException {
        Map<String, Object> root = Nbt.read(bytes);
        int[] size = ints(root.get("size"));
        List<?> palette = root.get("palette") instanceof List<?> p ? p
                : root.get("palettes") instanceof List<?> ps && !ps.isEmpty() && ps.get(0) instanceof List<?> first ? first
                : null;
        if (size == null || palette == null || !(root.get("blocks") instanceof List<?> entries)) {
            throw new IOException("not a structure file (no size, palette or blocks)");
        }
        if (entries.size() > SceneModel.MAX_BLOCKS) throw new IOException("more than " + SceneModel.MAX_BLOCKS + " blocks");
        List<String> states = new ArrayList<>(palette.size());
        for (Object entry : palette) states.add(entry instanceof Map<?, ?> m ? state(m) : null);
        List<SceneBlock> blocks = new ArrayList<>(entries.size());
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> m) || !(m.get("state") instanceof Integer index)) continue;
            int[] pos = ints(m.get("pos"));
            String state = index >= 0 && index < states.size() ? states.get(index) : null;
            if (pos == null || state == null || SceneModel.isAir(state) || state.equals("minecraft:structure_void")) continue;
            String nbt = m.get("nbt") instanceof Map<?, ?> tag && !tag.isEmpty() ? Nbt.snbt(tag) : null;
            blocks.add(new SceneBlock(pos[0], pos[1], pos[2], state, nbt));
        }
        return new Structure(size[0], size[1], size[2], List.copyOf(blocks));
    }

    /** {@code Name[key=value,...]} from a palette entry, or null when it has no name. */
    private static String state(Map<?, ?> entry) {
        if (!(entry.get("Name") instanceof String name)) return null;
        if (!(entry.get("Properties") instanceof Map<?, ?> props) || props.isEmpty()) return name;
        StringBuilder out = new StringBuilder(name).append('[');
        boolean first = true;
        for (Map.Entry<?, ?> e : props.entrySet()) {
            if (!first) out.append(',');
            first = false;
            out.append(e.getKey()).append('=').append(e.getValue());
        }
        return out.append(']').toString();
    }

    /** Three whole numbers from a list of ints, or null. */
    private static int[] ints(Object value) {
        if (!(value instanceof List<?> list) || list.size() != 3) return null;
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            if (!(list.get(i) instanceof Integer n) || Math.abs(n) > SceneModel.MAX_COORD) return null;
            out[i] = n;
        }
        return out;
    }
}
