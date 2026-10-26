package dev.vellum.preview.host;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.vellum.engine.scene.SceneBlock;
import dev.vellum.preview.render.MinecraftAssets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Block states as the faces of their models, read from the asset stack: the blockstate file's variant (or multipart
 * parts) for the state, its models with their parents and textures, turned by the variant's x and y. Elements are
 * boxes with textured faces; element rotations are left out (so crosses stand square), and blocks drawn by block
 * entity renderers (chests, beds, signs) have no faces. Water and lava are boxes of their still texture. Enough for
 * the previewer to show a build recognisably, not as the game does.
 */
final class BlockModels {
    /** Six directions: down, up, north, south, west, east, as Minecraft orders them. */
    static final float[][] NORMALS = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
    private static final String[] DIRECTIONS = {"down", "up", "north", "south", "west", "east"};
    /** Blocks that let neighbours' faces show through, though their model is a full cube. */
    private static final Set<String> SEE_THROUGH_WORDS = Set.of("glass", "leaves", "ice", "slime", "honey", "spawner",
            "barrier");
    private static final int GRASS = 0xFF91BD59, FOLIAGE = 0xFF77AB2F, WATER = 0xFF3F76E4;

    /**
     * A face in block coordinates (0 to 1): its corners top-left, top-right, bottom-right, bottom-left as its texture
     * maps onto them, the texture's url and part ({u0, v0, u1, v1}, 0 to 1), the direction it faces (an index into
     * {@link #NORMALS}), the face it is hidden by a neighbour on (-1 for none) and its tint (ARGB, white for none).
     */
    record Face(float[] corners, String texture, float[] uv, int direction, int cullface, int tint) {}

    /** A state's faces, and whether it hides its neighbours' faces against it. */
    record Model(List<Face> faces, boolean opaque) {
        static final Model EMPTY = new Model(List.of(), false);
    }

    private final MinecraftAssets assets;
    private final Map<String, Model> models = new HashMap<>();
    private final Map<String, Optional<JsonObject>> json = new HashMap<>();

    BlockModels(MinecraftAssets assets) {
        this.assets = assets;
    }

    /** The model of a block state in {@code /setblock} syntax. */
    Model of(String state) {
        return models.computeIfAbsent(state, this::load);
    }

    private Model load(String state) {
        int bracket = state.indexOf('[');
        String id = SceneBlock.id(state);
        Map<String, String> props = new HashMap<>();
        if (bracket >= 0 && state.endsWith("]")) {
            for (String pair : state.substring(bracket + 1, state.length() - 1).split(",")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) props.put(kv[0].strip(), kv[1].strip());
            }
        }
        String path = id.substring(id.indexOf(':') + 1);
        int tint = path.contains("grass") || path.contains("fern") ? GRASS
                : path.endsWith("leaves") || path.equals("vine") ? FOLIAGE : path.equals("lily_pad") ? 0xFF208030 : -1;
        if (path.equals("water") || path.equals("lava")) return fluid(id, path, props);
        Optional<JsonObject> blockstate = file(MinecraftAssets.assetUrl(id, "blockstates/", ".json"));
        if (blockstate.isEmpty()) return Model.EMPTY;
        List<Face> faces = new ArrayList<>();
        boolean fullCube = false;
        for (JsonObject variant : variants(blockstate.get(), props)) {
            String model = variant.has("model") ? variant.get("model").getAsString() : null;
            if (model == null) continue;
            int x = variant.has("x") ? variant.get("x").getAsInt() : 0, y = variant.has("y") ? variant.get("y").getAsInt() : 0;
            fullCube |= addModel(model, x, y, tint, faces);
        }
        boolean seeThrough = SEE_THROUGH_WORDS.stream().anyMatch(path::contains);
        return new Model(List.copyOf(faces), fullCube && !seeThrough);
    }

    /** The variant objects that apply to a state: one of {@code variants}, or every {@code multipart} part whose condition holds. */
    private static List<JsonObject> variants(JsonObject blockstate, Map<String, String> props) {
        List<JsonObject> out = new ArrayList<>();
        if (blockstate.has("variants")) {
            JsonObject variants = blockstate.getAsJsonObject("variants");
            for (Map.Entry<String, JsonElement> e : variants.entrySet()) {
                if (matches(e.getKey(), props)) {
                    out.add(first(e.getValue()));
                    return out;
                }
            }
        } else if (blockstate.has("multipart")) {
            for (JsonElement part : blockstate.getAsJsonArray("multipart")) {
                JsonObject p = part.getAsJsonObject();
                if (!p.has("when") || when(p.get("when").getAsJsonObject(), props)) out.add(first(p.get("apply")));
            }
        }
        return out;
    }

    private static JsonObject first(JsonElement e) {
        return e.isJsonArray() ? e.getAsJsonArray().get(0).getAsJsonObject() : e.getAsJsonObject();
    }

    /** A variant key ({@code facing=east,half=top}, or empty) against the state's properties. */
    private static boolean matches(String key, Map<String, String> props) {
        if (key.isEmpty()) return true;
        for (String pair : key.split(",")) {
            String[] kv = pair.split("=", 2);
            // A property the state leaves out matches, so states written without their defaults still show.
            if (kv.length == 2 && !kv[1].equals(props.getOrDefault(kv[0], kv[1]))) return false;
        }
        return true;
    }

    private static boolean when(JsonObject when, Map<String, String> props) {
        if (when.has("OR")) {
            for (JsonElement e : when.getAsJsonArray("OR")) if (when(e.getAsJsonObject(), props)) return true;
            return false;
        }
        if (when.has("AND")) {
            for (JsonElement e : when.getAsJsonArray("AND")) if (!when(e.getAsJsonObject(), props)) return false;
            return true;
        }
        for (Map.Entry<String, JsonElement> e : when.entrySet()) {
            String value = props.getOrDefault(e.getKey(), "false");
            boolean any = false;
            for (String option : e.getValue().getAsString().split("\\|")) {
                boolean negate = option.startsWith("!");
                any |= negate != option.substring(negate ? 1 : 0).equals(value);
            }
            if (!any) return false;
        }
        return true;
    }

    /** Adds a model's faces, turned by x then y degrees; returns whether it is one full cube. */
    private boolean addModel(String modelId, int rx, int ry, int tint, List<Face> out) {
        Map<String, String> textures = new HashMap<>();
        JsonArray elements = null;
        String id = modelId;
        for (int depth = 0; id != null && depth < 16; depth++) {
            Optional<JsonObject> model = file(MinecraftAssets.assetUrl(id, "models/", ".json"));
            if (model.isEmpty()) break;
            JsonObject m = model.get();
            if (m.has("textures")) {
                for (Map.Entry<String, JsonElement> t : m.getAsJsonObject("textures").entrySet()) {
                    if (t.getValue().isJsonPrimitive()) textures.putIfAbsent(t.getKey(), t.getValue().getAsString());
                }
            }
            if (elements == null && m.has("elements")) elements = m.getAsJsonArray("elements");
            id = m.has("parent") ? m.get("parent").getAsString() : null;
        }
        if (elements == null) return false;
        boolean full = elements.size() == 1;
        for (JsonElement element : elements) {
            JsonObject e = element.getAsJsonObject();
            float[] from = floats(e.getAsJsonArray("from")), to = floats(e.getAsJsonArray("to"));
            full &= from[0] == 0 && from[1] == 0 && from[2] == 0 && to[0] == 16 && to[1] == 16 && to[2] == 16;
            JsonObject faces = e.getAsJsonObject("faces");
            if (faces == null) continue;
            for (int d = 0; d < 6; d++) {
                JsonObject f = faces.getAsJsonObject(DIRECTIONS[d]);
                if (f == null) continue;
                String texture = resolve(f.get("texture").getAsString(), textures);
                if (texture == null) continue;
                float[] uv = f.has("uv") ? floats(f.getAsJsonArray("uv")) : defaultUv(d, from, to);
                int rotation = f.has("rotation") ? f.get("rotation").getAsInt() : 0;
                int cull = f.has("cullface") ? direction(f.get("cullface").getAsString()) : -1;
                float[] corners = turn(corners(d, from, to), rotation / 90);
                for (int i = 0; i < 4; i++) rotate(corners, i, rx, ry);
                out.add(new Face(corners, MinecraftAssets.assetUrl(texture, "textures/", ".png"),
                        new float[] {uv[0] / 16, uv[1] / 16, uv[2] / 16, uv[3] / 16}, rotated(d, rx, ry),
                        cull < 0 ? -1 : rotated(cull, rx, ry), f.has("tintindex") ? tint : -1));
            }
        }
        return full;
    }

    /** A fluid source or flow: a box of its still texture, a little lower than a block. */
    private Model fluid(String id, String path, Map<String, String> props) {
        String texture = MinecraftAssets.assetUrl(id.replace(path, "block/" + path + "_still"), "textures/", ".png");
        float[] from = {0, 0, 0}, to = {16, 14, 16};
        int tint = path.equals("water") ? (WATER & 0xFFFFFF) | 0xB0000000 : -1;
        List<Face> faces = new ArrayList<>();
        for (int d = 0; d < 6; d++) {
            float[] uv = defaultUv(d, from, to);
            faces.add(new Face(corners(d, from, to), texture, new float[] {uv[0] / 16, uv[1] / 16, uv[2] / 16, uv[3] / 16},
                    d, d == 1 ? -1 : d, tint));
        }
        return new Model(List.copyOf(faces), false);
    }

    private static String resolve(String ref, Map<String, String> textures) {
        for (int i = 0; i < 8 && ref != null && ref.startsWith("#"); i++) ref = textures.get(ref.substring(1));
        return ref == null || ref.startsWith("#") ? null : ref;
    }

    private static int direction(String name) {
        for (int d = 0; d < 6; d++) if (DIRECTIONS[d].equals(name)) return d;
        return name.equals("bottom") ? 0 : -1;
    }

    /** Minecraft's uv for a face without one: the element's extent on that face. */
    private static float[] defaultUv(int d, float[] f, float[] t) {
        return switch (d) {
            case 0 -> new float[] {f[0], 16 - t[2], t[0], 16 - f[2]};
            case 1 -> new float[] {f[0], f[2], t[0], t[2]};
            case 2 -> new float[] {16 - t[0], 16 - t[1], 16 - f[0], 16 - f[1]};
            case 3 -> new float[] {f[0], 16 - t[1], t[0], 16 - f[1]};
            case 4 -> new float[] {f[2], 16 - t[1], t[2], 16 - f[1]};
            default -> new float[] {16 - t[2], 16 - t[1], 16 - f[2], 16 - f[1]};
        };
    }

    /** A face's corners in block units: top-left, top-right, bottom-right, bottom-left as seen from outside. */
    private static float[] corners(int d, float[] f, float[] t) {
        float x0 = f[0] / 16, y0 = f[1] / 16, z0 = f[2] / 16, x1 = t[0] / 16, y1 = t[1] / 16, z1 = t[2] / 16;
        return switch (d) {
            case 0 -> new float[] {x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0};
            case 1 -> new float[] {x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1};
            case 2 -> new float[] {x1, y1, z0, x0, y1, z0, x0, y0, z0, x1, y0, z0};
            case 3 -> new float[] {x0, y1, z1, x1, y1, z1, x1, y0, z1, x0, y0, z1};
            case 4 -> new float[] {x0, y1, z0, x0, y1, z1, x0, y0, z1, x0, y0, z0};
            default -> new float[] {x1, y1, z1, x1, y1, z0, x1, y0, z0, x1, y0, z1};
        };
    }

    /** The corners shifted {@code quarters} places, as a face's uv rotation maps its texture. */
    private static float[] turn(float[] corners, int quarters) {
        int q = ((quarters % 4) + 4) % 4;
        if (q == 0) return corners;
        float[] out = new float[12];
        for (int i = 0; i < 4; i++) System.arraycopy(corners, ((i + q) % 4) * 3, out, i * 3, 3);
        return out;
    }

    /** Turns corner {@code i} about the block's middle: {@code rx} degrees about x, then {@code ry} about y. */
    private static void rotate(float[] c, int i, int rx, int ry) {
        float x = c[i * 3] - 0.5f, y = c[i * 3 + 1] - 0.5f, z = c[i * 3 + 2] - 0.5f;
        for (int k = 0; k < ((rx / 90) % 4 + 4) % 4; k++) {
            float t = y;
            y = -z;
            z = t;
        }
        for (int k = 0; k < ((ry / 90) % 4 + 4) % 4; k++) {
            float t = x;
            x = -z;
            z = t;
        }
        c[i * 3] = x + 0.5f;
        c[i * 3 + 1] = y + 0.5f;
        c[i * 3 + 2] = z + 0.5f;
    }

    /** A direction turned as {@link #rotate} turns corners. */
    private static int rotated(int d, int rx, int ry) {
        float[] n = NORMALS[d].clone();
        float[] c = {n[0] + 0.5f, n[1] + 0.5f, n[2] + 0.5f};
        rotate(c, 0, rx, ry);
        for (int k = 0; k < 6; k++) {
            float[] m = NORMALS[k];
            if (Math.abs(m[0] - (c[0] - 0.5f)) < 0.01 && Math.abs(m[1] - (c[1] - 0.5f)) < 0.01 && Math.abs(m[2] - (c[2] - 0.5f)) < 0.01) return k;
        }
        return d;
    }

    private static float[] floats(JsonArray a) {
        float[] out = new float[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsFloat();
        return out;
    }

    private Optional<JsonObject> file(String url) {
        return json.computeIfAbsent(url, assets::json);
    }
}
