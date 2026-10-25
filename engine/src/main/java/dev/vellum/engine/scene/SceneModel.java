package dev.vellum.engine.scene;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Node;
import dev.vellum.engine.event.CustomEvent;
import dev.vellum.engine.event.Event;
import dev.vellum.engine.event.MouseEvent;
import dev.vellum.engine.host.Host;
import dev.vellum.engine.input.Turntable;
import dev.vellum.engine.layout.Box;
import dev.vellum.engine.paint.Affine;
import dev.vellum.engine.paint.Coordinates;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Display;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What a {@code <scene>} shows, read from its element: a small world of blocks from a structure file and the
 * {@code <block>} children applied on top, split into {@link SceneGroup}s, plus entities and anchors, seen through a
 * {@link SceneCamera}. Its host's content creates one; the engine calls {@link #update} every frame after layout,
 * and the host draws from {@link #groups()}, {@link #entities()} and {@link #camera()}.
 *
 * <ul>
 *   <li>{@code structure="ns:path/file.nbt"} loads a structure file ({@link Host#loadBytes}).</li>
 *   <li>{@code <block at="x y z" state="...">} adds or replaces a block, in document order; {@code minecraft:air}
 *       erases one. {@code nbt} gives its block entity data as SNBT.</li>
 *   <li>{@code <block-group x="3..7" y="2" z="3..6">} claims the blocks in that box that no earlier group claimed,
 *       and its own {@code <block>} children. Each range is inclusive, a single number is one layer, and an axis left
 *       out is all of it ({@code <block-group y="2">} is a layer). The rest are the base. A group shows, fades and moves
 *       by its style ({@code display}, {@code opacity}, {@code -mc-translate}, {@code -mc-rotate}).</li>
 *   <li>{@code <entity at="x y z">} children stand at that point, moved by their {@code -mc-translate}.</li>
 *   <li>{@code <scene-anchor at="x y z">} children are laid out as HTML and moved every frame so their top-left
 *       corner is where that point is seen.</li>
 * </ul>
 *
 * <p>Changing any of these from a script updates the scene on the next frame: the blocks are read again only after
 * the document changed, and a group's version (so its mesh) only changes when its blocks do. The camera follows
 * {@code -mc-yaw}, {@code -mc-pitch}, {@code -mc-model-scale}, {@code -mc-scene-center} and, with
 * {@code rotatable}, dragging.
 *
 * <p>The block under the pointer (whole cubes, nearest first, in groups that show) fires {@code blockhover} when it
 * changes and {@code blockclick} on a click that did not drag, both bubbling, with {@code detail}
 * {@code {x, y, z, state, group}} ({@code blockhover} has a null detail when the pointer leaves the blocks). Hosts
 * show its name as a tooltip unless the scene has {@code notooltips} ({@link #tooltipBlock()}).
 */
public final class SceneModel {
    public static final String TAG = "scene", BLOCK = "block", GROUP = "block-group", ENTITY = "entity",
            ANCHOR = "scene-anchor";
    /** The default size of a scene's box, in px. */
    public static final float WIDTH = 160, HEIGHT = 120;
    /** The most blocks a scene holds, from its structure and children together. */
    public static final int MAX_BLOCKS = 65536;
    /** How far from the origin a block may be, in each axis. */
    public static final int MAX_COORD = 1 << 16;
    /** How far the pointer may move between press and release for a click to pick a block, in px. */
    private static final float CLICK_SLOP = 3;
    private static final Set<String> AIR = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air");

    private final Element element;
    private final SceneGroup base = new SceneGroup(null);
    /** The base, then the groups in document order. */
    private List<SceneGroup> groups = List.of(base);
    private List<SceneEntity> entities = List.of();
    private List<Element> entityElements = List.of(), anchors = List.of();
    /** What the blocks were last built from, and the document version they were read at. */
    private List<Object> spec;
    private int syncedVersion = Integer.MIN_VALUE;
    private String structureUrl;
    private Structure structure;
    private float[] bounds = {0, 0, 0, 1, 1, 1};
    private SceneCamera camera;
    private float[] matrix, inverse;
    private SceneBlock hovered;
    private float pressX = Float.NaN, pressY;
    /** Problems already logged, so an edit loop does not repeat them. */
    private final Set<String> warned = new HashSet<>();

    public SceneModel(Element element) {
        this.element = element;
        element.addEventListener("mousedown", this::pressed);
        element.addEventListener("click", this::clicked);
    }

    public Element element() { return element; }

    /** The base first, then the {@code <block-group>}s in document order. */
    public List<SceneGroup> groups() { return groups; }

    /** The entities that show, where they stand this frame. */
    public List<SceneEntity> entities() { return entities; }

    /** This frame's camera, or null when the scene has no box. */
    public SceneCamera camera() { return camera; }

    /** The bounds every block lies in (and the structure's size), {minX, minY, minZ, maxX, maxY, maxZ}. */
    public float[] bounds() { return bounds.clone(); }

    /** The block under the pointer, or null. */
    public SceneBlock hovered() { return hovered; }

    /** The block whose name the host shows as a tooltip now: the hovered one, unless {@code notooltips} or dragging. */
    public SceneBlock tooltipBlock() {
        return hovered == null || element.hasAttribute("notooltips") || element.isActive() ? null : hovered;
    }

    /**
     * Brings the model up to date with the document, the styles and the layout, places the anchors and picks the
     * hovered block. Returns whether anything the scene paints changed.
     */
    public boolean update() {
        boolean changed = sync();
        for (SceneGroup g : groups) changed |= g.readStyle();
        changed |= placeEntities();
        changed |= updateCamera();
        changed |= placeAnchors();
        updateHover();
        return changed;
    }

    // ---- Reading the document ----

    /** Position key: each coordinate in 21 bits. */
    static long key(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }

    static boolean isAir(String state) {
        String id = state.indexOf('[') < 0 ? state : state.substring(0, state.indexOf('['));
        return AIR.contains(id.contains(":") ? id : "minecraft:" + id);
    }

    private record BlockSpec(int x, int y, int z, String state, String nbt, Element group) {}

    private record GroupSpec(Element element, List<Integer> box) {}

    /** Reads the children again after the document changed; returns whether the blocks or groups changed. */
    private boolean sync() {
        Document doc = element.ownerDocument();
        if (doc.domVersion() == syncedVersion) return false;
        syncedVersion = doc.domVersion();
        boolean structureChanged = loadStructure();
        List<Object> next = new ArrayList<>();
        List<Element> entityEls = new ArrayList<>(), anchorEls = new ArrayList<>();
        for (Node n : element.childNodes()) {
            if (!(n instanceof Element child)) continue;
            switch (child.tagName()) {
                case BLOCK -> readBlock(child, null, next);
                case GROUP -> {
                    int[] box = box(child);
                    next.add(new GroupSpec(child, box == null ? null : List.of(box[0], box[1], box[2],
                            box[3], box[4], box[5])));
                    for (Element block : child.children()) {
                        if (block.tagName().equals(BLOCK)) readBlock(block, child, next);
                    }
                }
                case ENTITY -> entityEls.add(child);
                case ANCHOR -> anchorEls.add(child);
                default -> { }
            }
        }
        entityElements = entityEls;
        anchors = anchorEls;
        if (!structureChanged && next.equals(spec)) return false;
        spec = next;
        compose(next);
        return true;
    }

    private void readBlock(Element e, Element group, List<Object> out) {
        int[] at = wholeNumbers(e.getAttribute("at"), 3);
        String state = e.getAttribute("state");
        if (at == null || state == null || state.isBlank()) {
            warn("<block> needs at=\"x y z\" (whole numbers) and a state: " + e.outerHTML());
            return;
        }
        String nbt = e.getAttribute("nbt");
        out.add(new BlockSpec(at[0], at[1], at[2], state.strip(), nbt == null || nbt.isBlank() ? null : nbt.strip(), group));
    }

    /**
     * The box a group's {@code x}, {@code y} and {@code z} ranges claim ({x1, y1, z1, x2, y2, z2}, inclusive), an axis
     * left out spanning everything; null when it has none of them (or one doesn't parse), so it claims only its own
     * {@code <block>} children.
     */
    private int[] box(Element group) {
        int[] box = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        boolean any = false;
        String[] axes = {"x", "y", "z"};
        for (int a = 0; a < 3; a++) {
            String attr = group.getAttribute(axes[a]);
            if (attr == null) continue;
            int[] r = range(attr);
            if (r == null) {
                warn("<block-group " + axes[a] + "> needs a whole number or a range like 2..5: " + attr);
                return null;
            }
            box[a] = r[0];
            box[a + 3] = r[1];
            any = true;
        }
        return any ? box : null;
    }

    /** {@code "3"} or {@code "2..5"} (either order, negatives allowed) as {low, high}; null when it isn't one. */
    static int[] range(String text) {
        String s = text.strip();
        int dots = s.indexOf("..");
        try {
            if (dots < 0) {
                int v = Integer.parseInt(s);
                return new int[] {v, v};
            }
            int a = Integer.parseInt(s.substring(0, dots).strip()), b = Integer.parseInt(s.substring(dots + 2).strip());
            return new int[] {Math.min(a, b), Math.max(a, b)};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Loads the structure when its URL changed; returns whether the blocks it gives changed. */
    private boolean loadStructure() {
        String attr = element.getAttribute("structure");
        Document doc = element.ownerDocument();
        String url = attr == null || attr.isBlank() ? null : doc.host().resolveUrl(doc.url(), attr.strip());
        if (Objects.equals(url, structureUrl)) return false;
        structureUrl = url;
        Structure loaded = null;
        if (url != null) {
            byte[] bytes = doc.host().loadBytes(url);
            if (bytes == null) {
                warn("Structure not found: " + url);
            } else {
                try {
                    loaded = Structure.read(bytes);
                } catch (IOException | RuntimeException e) {
                    warn("Cannot read structure " + url + ": " + e.getMessage());
                }
            }
        }
        boolean changed = !Objects.equals(loaded, structure);
        structure = loaded;
        return changed;
    }

    /** Builds the world from the structure and the block specs, and splits it among the groups. */
    private void compose(List<Object> next) {
        record Placed(SceneBlock block, Element group) {}
        Map<Long, Placed> world = new HashMap<>();
        if (structure != null) for (SceneBlock b : structure.blocks()) world.put(key(b.x(), b.y(), b.z()), new Placed(b, null));
        Map<Element, SceneGroup> previous = new IdentityHashMap<>();
        for (SceneGroup g : groups) if (g.element() != null) previous.put(g.element(), g);
        List<SceneGroup> nextGroups = new ArrayList<>();
        nextGroups.add(base);
        for (Object o : next) {
            if (o instanceof GroupSpec gs) {
                SceneGroup g = previous.getOrDefault(gs.element(), new SceneGroup(gs.element()));
                g.box = gs.box() == null ? null : gs.box().stream().mapToInt(Integer::intValue).toArray();
                nextGroups.add(g);
            } else if (o instanceof BlockSpec b) {
                long k = key(b.x(), b.y(), b.z());
                if (isAir(b.state())) world.remove(k);
                else world.put(k, new Placed(new SceneBlock(b.x(), b.y(), b.z(), b.state(), b.nbt()), b.group()));
            }
        }
        if (world.size() > MAX_BLOCKS) warn("A scene shows at most " + MAX_BLOCKS + " blocks; some are left out");
        Map<Element, SceneGroup> byElement = new IdentityHashMap<>();
        for (SceneGroup g : nextGroups) if (g.element() != null) byElement.put(g.element(), g);
        Map<SceneGroup, List<SceneBlock>> split = new LinkedHashMap<>();
        for (SceneGroup g : nextGroups) split.put(g, new ArrayList<>());
        float[] b = structure == null ? null : new float[] {0, 0, 0, structure.sizeX(), structure.sizeY(), structure.sizeZ()};
        int count = 0;
        for (Placed p : world.values()) {
            if (++count > MAX_BLOCKS) break;
            SceneBlock block = p.block();
            SceneGroup owner = p.group() == null ? null : byElement.get(p.group());
            for (int i = 1; owner == null && i < nextGroups.size(); i++) {
                if (nextGroups.get(i).claims(block.x(), block.y(), block.z())) owner = nextGroups.get(i);
            }
            split.get(owner == null ? base : owner).add(block);
            if (b == null) b = new float[] {block.x(), block.y(), block.z(), block.x() + 1, block.y() + 1, block.z() + 1};
            b[0] = Math.min(b[0], block.x());
            b[1] = Math.min(b[1], block.y());
            b[2] = Math.min(b[2], block.z());
            b[3] = Math.max(b[3], block.x() + 1);
            b[4] = Math.max(b[4], block.y() + 1);
            b[5] = Math.max(b[5], block.z() + 1);
        }
        Comparator<SceneBlock> order = Comparator.comparingInt(SceneBlock::y).thenComparingInt(SceneBlock::z)
                .thenComparingInt(SceneBlock::x);
        split.forEach((g, blocks) -> {
            blocks.sort(order);
            Map<Long, SceneBlock> positions = new HashMap<>();
            for (SceneBlock s : blocks) positions.put(key(s.x(), s.y(), s.z()), s);
            g.setBlocks(blocks, positions);
        });
        groups = List.copyOf(nextGroups);
        bounds = b == null ? new float[] {0, 0, 0, 1, 1, 1} : b;
    }

    // ---- Each frame ----

    /** Where the entities stand; returns whether that changed. */
    private boolean placeEntities() {
        List<SceneEntity> next = new ArrayList<>(entityElements.size());
        for (Element e : entityElements) {
            ComputedStyle s = e.style;
            float[] at = numbers(e.getAttribute("at"), 3);
            if (s == null || s.display == Display.NONE || at == null) continue;
            next.add(new SceneEntity(e, at[0] + s.translateX, at[1] + s.translateY, at[2] + s.translateZ));
        }
        boolean changed = !next.equals(entities);
        entities = next;
        return changed;
    }

    private boolean updateCamera() {
        Box box = element.box;
        if (box == null || element.replaced == null) {
            boolean had = camera != null;
            camera = null;
            return had;
        }
        ComputedStyle s = element.computedStyle();
        float[] fitted = Coordinates.objectBox(box, s, element.replaced);
        SceneCamera next = SceneCamera.fit(bounds, new float[] {s.sceneCenterX, s.sceneCenterY, s.sceneCenterZ},
                s.modelYaw + Turntable.yaw(element), s.modelPitch + Turntable.pitch(element), s.modelScale,
                fitted[0], fitted[1], fitted[2], fitted[3]);
        if (next.equals(camera)) return false;
        camera = next;
        matrix = next.matrix();
        inverse = Mat.invert(matrix);
        return true;
    }

    /** Moves each anchor so its top-left corner is where its point is seen; returns whether any moved. */
    private boolean placeAnchors() {
        boolean changed = false;
        float[] p = new float[3];
        for (Element a : anchors) {
            Box box = a.box;
            if (box == null || !box.anchored || box.parent != element.box) continue;
            float[] at = numbers(a.getAttribute("at"), 3);
            ComputedStyle s = a.computedStyle();
            float x = 0, y = 0;
            if (at != null && matrix != null) {
                Mat.apply(matrix, at[0] + s.translateX, at[1] + s.translateY, at[2] + s.translateZ, p);
                x = camera.x() + p[0] - box.x;
                y = camera.y() + p[1] - box.y;
            }
            if (x != box.anchorX || y != box.anchorY) {
                box.anchorX = x;
                box.anchorY = y;
                changed = true;
            }
        }
        return changed;
    }

    // ---- Pointer ----

    private void updateHover() {
        Document doc = element.ownerDocument();
        float[] pointer = doc.input().pointer();
        SceneBlock now = doc.input().hoverTarget() == element && pointer != null ? pickAt(pointer[0], pointer[1]) : null;
        if (Objects.equals(now, hovered)) return;
        hovered = now;
        element.dispatchEvent(new CustomEvent("blockhover", true, false, now == null ? null : detail(now)));
    }

    /** The block seen at a viewport point, or null. */
    public SceneBlock pickAt(float viewportX, float viewportY) {
        Box box = element.box;
        Affine toLocal = new Affine();
        if (box == null || camera == null || !Coordinates.fromViewport(box, toLocal)) return null;
        float x = toLocal.mapX(viewportX, viewportY) - camera.x(), y = toLocal.mapY(viewportX, viewportY) - camera.y();
        if (x < 0 || y < 0 || x > camera.width() || y > camera.height()) return null;
        return pick(x, y);
    }

    /** The nearest block seen at a point of the painted box, or null. */
    SceneBlock pick(float x, float y) {
        if (inverse == null) return null;
        // From in front of everything, straight into the screen.
        float[] origin = Mat.apply(inverse, x, y, -1e5f, new float[3]);
        float[] direction = Mat.applyDirection(inverse, 0, 0, 1, new float[3]);
        SceneBlock best = null;
        float bestT = Float.POSITIVE_INFINITY;
        SceneBlock[] hit = new SceneBlock[1];
        for (SceneGroup g : groups) {
            if (!g.visible()) continue;
            float t = g.cast(origin, direction, hit);
            if (t < bestT) {
                bestT = t;
                best = hit[0];
            }
        }
        return best;
    }

    private void pressed(Event e) {
        if (e.target() != element || !(e instanceof MouseEvent m)) return;
        pressX = m.clientX;
        pressY = m.clientY;
    }

    private void clicked(Event e) {
        if (e.target() != element || !(e instanceof MouseEvent m) || Float.isNaN(pressX)) return;
        boolean still = Math.abs(m.clientX - pressX) <= CLICK_SLOP && Math.abs(m.clientY - pressY) <= CLICK_SLOP;
        pressX = Float.NaN;
        if (!still) return;
        SceneBlock block = pickAt(m.clientX, m.clientY);
        if (block != null) element.dispatchEvent(new CustomEvent("blockclick", true, false, detail(block)));
    }

    private Map<String, Object> detail(SceneBlock b) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("x", b.x());
        d.put("y", b.y());
        d.put("z", b.z());
        d.put("state", b.state());
        Element group = null;
        for (SceneGroup g : groups) if (g.element() != null && g.byPosition().get(key(b.x(), b.y(), b.z())) == b) group = g.element();
        d.put("group", group);
        return d;
    }

    // ---- Parsing ----

    /** {@code count} numbers separated by spaces or commas, or null. */
    static float[] numbers(String text, int count) {
        if (text == null) return null;
        String[] parts = text.strip().split("[\\s,]+");
        if (parts.length != count) return null;
        float[] out = new float[count];
        for (int i = 0; i < count; i++) {
            try {
                out[i] = Float.parseFloat(parts[i]);
            } catch (NumberFormatException e) {
                return null;
            }
            if (!Float.isFinite(out[i]) || Math.abs(out[i]) > MAX_COORD) return null;
        }
        return out;
    }

    /** {@code count} whole numbers separated by spaces or commas, or null. */
    static int[] wholeNumbers(String text, int count) {
        float[] f = numbers(text, count);
        if (f == null) return null;
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            if (f[i] != (int) f[i]) return null;
            out[i] = (int) f[i];
        }
        return out;
    }

    private void warn(String message) {
        if (warned.size() < 64 && warned.add(message)) {
            element.ownerDocument().host().log(Host.LogLevel.WARN, message);
        }
    }
}
