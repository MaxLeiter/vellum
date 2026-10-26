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
 * {@link SceneCamera}. Its {@link SceneContent} owns it and updates it every frame after layout, and the host draws
 * from {@link #groups()}, {@link #entities()} and {@link #camera()}.
 *
 * <ul>
 *   <li>{@code structure="ns:path/file.nbt"} loads a structure file ({@link Host#loadBytes}).</li>
 *   <li>{@code <block x="1" y="2" z="3" state="...">} adds or replaces a block, in document order; {@code minecraft:air}
 *       erases one. {@code nbt} gives its block entity data as SNBT.</li>
 *   <li>{@code <block-group x="3..7" y="2" z="3..6">} claims the blocks in that box that no earlier group claimed,
 *       and its own {@code <block>} children. Each range is inclusive, a single number is one layer, and an axis left
 *       out is all of it ({@code <block-group y="2">} is a layer). The rest are the base. A group shows, fades, moves
 *       and turns by its style ({@code display}, {@code opacity}, {@code -mc-translate}, {@code -mc-yaw},
 *       {@code -mc-pitch}, {@code -mc-roll}).</li>
 *   <li>{@code <entity x y z>} children stand at that point, moved by their {@code -mc-translate}.</li>
 *   <li>{@code <scene-anchor x y z>} children are laid out as HTML and moved every frame so their top-left
 *       corner is where that point is seen.</li>
 * </ul>
 *
 * <p>Changing any of these from a script updates the scene on the next frame: the children are read again only after
 * they, their groups' children or the attributes it reads of them changed (never for their styles, which it follows
 * every frame), and a group's version (so its mesh) only changes when its blocks do. The camera follows
 * {@code -mc-yaw}, {@code -mc-pitch}, {@code -mc-model-scale}, {@code -mc-scene-center} and, with
 * {@code rotatable}, dragging.
 *
 * <p>The block under the pointer (whole cubes, nearest first, in groups that show) fires {@code blockhover} when it
 * changes and {@code blockclick} on a click that did not drag, both bubbling, with {@code detail}
 * {@code {x, y, z, state, group}} ({@code blockhover} has a null detail when the pointer leaves the blocks). Hosts
 * show its name as a tooltip unless the scene has {@code notooltips} ({@link #tooltipBlock()}).
 */
public final class SceneModel {
    public static final String BLOCK = "block", GROUP = "block-group", ENTITY = "entity", ANCHOR = "scene-anchor";
    /** The most blocks a scene holds, from its structure and children together. */
    public static final int MAX_BLOCKS = 65536;
    /** How far from the origin a block may be, in each axis. */
    public static final int MAX_COORD = 1 << 16;
    /** How far the pointer may move between press and release for a click to pick a block, in px. */
    private static final float CLICK_SLOP = 3;
    private static final Set<String> AIR = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air");
    /** The attributes of its children, and of its groups' children, that it reads. */
    private static final Set<String> READ = Set.of("x", "y", "z", "state", "nbt");
    private static final Comparator<SceneBlock> ORDER = Comparator.comparingInt(SceneBlock::y)
            .thenComparingInt(SceneBlock::z).thenComparingInt(SceneBlock::x);

    private final Element element;
    private final SceneGroup base = new SceneGroup(null);
    /** The base, then the groups in document order. */
    private List<SceneGroup> groups = List.of(base);
    private int groupsVersion;
    private List<SceneEntity> entities = List.of();
    /** The entities and anchors, with the points their x, y and z give. */
    private List<Placed> entityPoints = List.of(), anchorPoints = List.of();
    /** What the blocks were last built from, and whether the children changed since. */
    private Spec spec;
    private boolean dirty = true;
    private String structureUrl;
    private Structure structure;
    /** The structure's blocks by position ({@link #key}). */
    private Map<Long, SceneBlock> structureBlocks = Map.of();
    private float[] bounds = {0, 0, 0, 1, 1, 1};
    private SceneCamera camera;
    private float[] matrix, inverse;
    private SceneBlock hovered;
    /** Where the last pick looked (NaN for nowhere), and whether what it sees changed since. */
    private float pickedX = Float.NaN, pickedY;
    private boolean pickStale = true;
    private float pressX = Float.NaN, pressY;
    /** Problems already logged, so an edit loop does not repeat them. */
    private final Set<String> warned = new HashSet<>();

    /** A {@code <block>} child, in its group (null for the scene's own). */
    private record BlockSpec(int x, int y, int z, String state, String nbt, Element group) {}

    /** A {@code <block-group>} and the box it claims, or null. */
    private record GroupSpec(Element element, SceneGroup.Box box) {}

    /** What the blocks are built from, in document order. */
    private record Spec(List<BlockSpec> blocks, List<GroupSpec> groups) {}

    /** An entity or anchor, and its point (null when its x, y and z don't give one). */
    private record Placed(Element element, float[] at) {}

    SceneModel(Element element) {
        this.element = element;
        element.addEventListener("mousedown", this::pressed);
        element.addEventListener("click", this::clicked);
    }

    public Element element() { return element; }

    /** The base first, then the {@code <block-group>}s in document order. */
    public List<SceneGroup> groups() { return groups; }

    /** Changes whenever how the groups show does: their blocks, visibility, opacity or matrix, or which there are. */
    public int groupsVersion() { return groupsVersion; }

    /** The entities that show, where they stand this frame. */
    public List<SceneEntity> entities() { return entities; }

    /** This frame's camera, or null when the scene has no box. */
    public SceneCamera camera() { return camera; }

    /** This frame's {@link SceneCamera#matrix()}, computed once a camera change (shared: don't change it), or null. */
    public float[] cameraMatrix() { return matrix; }

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
    boolean update() {
        boolean blocks = sync(), shows = blocks, moved = blocks;
        for (SceneGroup g : groups) {
            int placement = g.placement;
            if (g.readStyle()) {
                shows = true;
                moved |= g.placement != placement;
            }
        }
        if (shows) groupsVersion++;
        boolean changed = placeEntities() | shows;
        boolean turned = updateCamera();
        changed |= placeAnchors() | turned;
        if (moved || turned) pickStale = true;
        updateHover();
        return changed;
    }

    // ---- Reading the document ----

    /** The scene's own attribute changed. */
    void attributeChanged(String name) {
        if (name.equals("structure")) dirty = true;
    }

    /**
     * Something below the scene changed ({@code ReplacedContent.subtreeChanged}): the children are read again when
     * its children or a group's changed, or an attribute it reads of one of them.
     */
    void subtreeChanged(Element changed, String attribute) {
        if (attribute == null ? holdsBlocks(changed) : READ.contains(attribute) && holdsBlocks(changed.parentElement())) {
            dirty = true;
        }
    }

    /** Whether the scene reads {@code e}'s children: it is the scene, or one of its groups. */
    private boolean holdsBlocks(Element e) {
        return e == element || e != null && e.parentElement() == element && e.tagName().equals(GROUP);
    }

    /** Position key: each coordinate in 21 bits. */
    static long key(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }

    static boolean isAir(String state) {
        return AIR.contains(SceneBlock.id(state));
    }

    /** Reads the children again after they changed; returns whether the blocks or groups changed. */
    private boolean sync() {
        if (!dirty) return false;
        dirty = false;
        boolean structureChanged = loadStructure();
        List<BlockSpec> blocks = new ArrayList<>();
        List<GroupSpec> groupSpecs = new ArrayList<>();
        List<Placed> entityEls = new ArrayList<>(), anchorEls = new ArrayList<>();
        for (Node n : element.childNodes()) {
            if (!(n instanceof Element child)) continue;
            switch (child.tagName()) {
                case BLOCK -> readBlock(child, null, blocks);
                case GROUP -> {
                    groupSpecs.add(new GroupSpec(child, box(child)));
                    for (Element block : child.children()) {
                        if (block.tagName().equals(BLOCK)) readBlock(block, child, blocks);
                    }
                }
                case ENTITY -> entityEls.add(new Placed(child, point(child)));
                case ANCHOR -> anchorEls.add(new Placed(child, point(child)));
                default -> { }
            }
        }
        entityPoints = entityEls;
        anchorPoints = anchorEls;
        Spec next = new Spec(blocks, groupSpecs);
        if (!structureChanged && next.equals(spec)) return false;
        spec = next;
        compose(next);
        return true;
    }

    private void readBlock(Element e, Element group, List<BlockSpec> out) {
        float[] at = point(e);
        String state = e.getAttribute("state");
        if (at == null || at[0] != (int) at[0] || at[1] != (int) at[1] || at[2] != (int) at[2] || state == null
                || state.isBlank()) {
            warn("<block> needs whole numbers x, y and z and a state: " + e.outerHTML());
            return;
        }
        String nbt = e.getAttribute("nbt");
        out.add(new BlockSpec((int) at[0], (int) at[1], (int) at[2], state.strip(),
                nbt == null || nbt.isBlank() ? null : nbt.strip(), group));
    }

    /**
     * The box a group's {@code x}, {@code y} and {@code z} ranges claim, an axis left out spanning everything; null
     * when it has none of them (or one doesn't parse), so it claims only its own {@code <block>} children.
     */
    private SceneGroup.Box box(Element group) {
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
        return any ? new SceneGroup.Box(box[0], box[1], box[2], box[3], box[4], box[5]) : null;
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
        if (Objects.equals(loaded, structure)) return false;
        structure = loaded;
        Map<Long, SceneBlock> byKey = new HashMap<>();
        if (loaded != null) for (SceneBlock b : loaded.blocks()) byKey.put(key(b.x(), b.y(), b.z()), b);
        structureBlocks = byKey;
        return true;
    }

    /** Builds the world from the structure and the block specs, and splits it among the groups. */
    private void compose(Spec next) {
        Map<Long, SceneBlock> world = new HashMap<>(structureBlocks);
        Map<Long, Element> placedIn = new HashMap<>();
        for (BlockSpec b : next.blocks()) {
            long k = key(b.x(), b.y(), b.z());
            if (isAir(b.state())) {
                world.remove(k);
                placedIn.remove(k);
                continue;
            }
            world.put(k, new SceneBlock(b.x(), b.y(), b.z(), b.state(), b.nbt()));
            if (b.group() == null) placedIn.remove(k); else placedIn.put(k, b.group());
        }
        if (world.size() > MAX_BLOCKS) warn("A scene shows at most " + MAX_BLOCKS + " blocks; some are left out");
        Map<Element, SceneGroup> kept = new IdentityHashMap<>(), byElement = new IdentityHashMap<>();
        for (SceneGroup g : groups) if (g.element() != null) kept.put(g.element(), g);
        List<SceneGroup> nextGroups = new ArrayList<>();
        nextGroups.add(base);
        for (GroupSpec gs : next.groups()) {
            SceneGroup g = kept.get(gs.element());
            if (g == null) g = new SceneGroup(gs.element());
            g.box = gs.box();
            nextGroups.add(g);
            byElement.put(gs.element(), g);
        }
        Map<SceneGroup, List<SceneBlock>> split = new LinkedHashMap<>();
        for (SceneGroup g : nextGroups) split.put(g, new ArrayList<>());
        int count = 0;
        for (Map.Entry<Long, SceneBlock> e : world.entrySet()) {
            if (++count > MAX_BLOCKS) break;
            SceneBlock block = e.getValue();
            Element in = placedIn.get(e.getKey());
            SceneGroup owner = in == null ? null : byElement.get(in);
            for (int i = 1; owner == null && i < nextGroups.size(); i++) {
                if (nextGroups.get(i).claims(block.x(), block.y(), block.z())) owner = nextGroups.get(i);
            }
            split.get(owner == null ? base : owner).add(block);
        }
        split.forEach((g, blocks) -> {
            blocks.sort(ORDER);
            g.setBlocks(blocks);
        });
        if (!nextGroups.equals(groups)) groups = List.copyOf(nextGroups);
        float[] b = structure == null ? null : new float[] {0, 0, 0, structure.sizeX(), structure.sizeY(), structure.sizeZ()};
        for (SceneGroup g : groups) {
            int[] gb = g.bounds;
            if (gb == null) continue;
            if (b == null) b = new float[] {gb[0], gb[1], gb[2], gb[3], gb[4], gb[5]};
            for (int a = 0; a < 3; a++) {
                b[a] = Math.min(b[a], gb[a]);
                b[a + 3] = Math.max(b[a + 3], gb[a + 3]);
            }
        }
        bounds = b == null ? new float[] {0, 0, 0, 1, 1, 1} : b;
    }

    // ---- Each frame ----

    /** Where the entities stand; returns whether that changed. */
    private boolean placeEntities() {
        List<SceneEntity> next = new ArrayList<>(entityPoints.size());
        for (Placed p : entityPoints) {
            ComputedStyle s = p.element().style;
            float[] at = p.at();
            if (s == null || s.display == Display.NONE || at == null) continue;
            next.add(new SceneEntity(p.element(), at[0] + s.translateX, at[1] + s.translateY, at[2] + s.translateZ));
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
            matrix = inverse = null;
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
        for (Placed a : anchorPoints) {
            Box box = a.element().box;
            if (box == null || box.parent != element.box) continue;
            float[] at = a.at();
            ComputedStyle s = a.element().computedStyle();
            float x = 0, y = 0;
            if (at != null && matrix != null) {
                Mat.apply(matrix, at[0] + s.translateX, at[1] + s.translateY, at[2] + s.translateZ, p);
                x = camera.x() + p[0] - box.x;
                y = camera.y() + p[1] - box.y;
            }
            if (x != box.hostOffsetX || y != box.hostOffsetY) {
                box.hostOffsetX = x;
                box.hostOffsetY = y;
                changed = true;
            }
        }
        return changed;
    }

    // ---- Pointer ----

    /** Picks the hovered block again when the pointer, the camera or a group moved since the last pick. */
    private void updateHover() {
        Document doc = element.ownerDocument();
        float[] pointer = doc.input().pointer();
        float[] at = doc.input().hoverTarget() == element && pointer != null ? local(pointer[0], pointer[1]) : null;
        SceneBlock now = hovered;
        if (at == null) {
            now = null;
            pickedX = Float.NaN;
        } else if (pickStale || at[0] != pickedX || at[1] != pickedY) {
            now = pick(at[0], at[1]);
            pickedX = at[0];
            pickedY = at[1];
            pickStale = false;
        }
        if (Objects.equals(now, hovered)) return;
        hovered = now;
        element.dispatchEvent(new CustomEvent("blockhover", true, false, now == null ? null : detail(now)));
    }

    /** The block seen at a viewport point, or null. */
    public SceneBlock pickAt(float viewportX, float viewportY) {
        float[] at = local(viewportX, viewportY);
        return at == null ? null : pick(at[0], at[1]);
    }

    /** A viewport point in the painted box, or null when it is outside it. */
    private float[] local(float viewportX, float viewportY) {
        Box box = element.box;
        Affine toLocal = new Affine();
        if (box == null || camera == null || !Coordinates.fromViewport(box, toLocal)) return null;
        float x = toLocal.mapX(viewportX, viewportY) - camera.x(), y = toLocal.mapY(viewportX, viewportY) - camera.y();
        return x < 0 || y < 0 || x > camera.width() || y > camera.height() ? null : new float[] {x, y};
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
        for (SceneGroup g : groups) if (g.element() != null && g.at(b.x(), b.y(), b.z()) == b) group = g.element();
        d.put("group", group);
        return d;
    }

    // ---- Parsing ----

    /** A coordinate: a finite number at most {@link #MAX_COORD} from 0, or NaN when {@code text} isn't one. */
    static float coordinate(String text) {
        if (text == null) return Float.NaN;
        try {
            float v = Float.parseFloat(text.strip());
            return Math.abs(v) <= MAX_COORD ? v : Float.NaN;
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    /** A child's point in the scene, from its {@code x}, {@code y} and {@code z}; null unless all three are coordinates. */
    static float[] point(Element e) {
        float x = coordinate(e.getAttribute("x")), y = coordinate(e.getAttribute("y")), z = coordinate(e.getAttribute("z"));
        return Float.isNaN(x) || Float.isNaN(y) || Float.isNaN(z) ? null : new float[] {x, y, z};
    }

    private void warn(String message) {
        if (warned.size() < 64 && warned.add(message)) {
            element.ownerDocument().host().log(Host.LogLevel.WARN, message);
        }
    }
}
