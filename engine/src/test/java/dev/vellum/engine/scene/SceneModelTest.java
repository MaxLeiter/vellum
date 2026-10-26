package dev.vellum.engine.scene;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.input.Turntable;
import dev.vellum.engine.paint.Canvas;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static dev.vellum.engine.css.StyleEngine.computedValue;
import static dev.vellum.engine.testing.Page.styleOf;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code <scene>}: the blocks, groups, entities and anchors a page describes, its camera and picking. */
class SceneModelTest {
    /** A host's scene content, drawing nothing. */
    private static final class TestScene extends SceneContent {
        TestScene(Element element) {
            super(element);
        }

        @Override public void paint(Canvas canvas, float x, float y, float width, float height) {}
    }

    private static TestHost host() {
        TestHost host = new TestHost();
        host.replaced.put("scene", TestScene::new);
        return host;
    }

    private static Page page(String body) {
        return host().load("<body style='margin: 0'>" + body + "</body>");
    }

    private static SceneModel model(Page page) {
        return ((SceneContent) page.byId("s").replaced).scene();
    }

    /** The states of a group's blocks as "x,y,z=state". */
    private static List<String> blocks(SceneGroup g) {
        return g.blocks().stream().map(b -> b.x() + "," + b.y() + "," + b.z() + "=" + b.state()).toList();
    }

    /** A compound in this key order. */
    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) out.put((String) keysAndValues[i], keysAndValues[i + 1]);
        return out;
    }

    private static byte[] structure() {
        return NbtWriter.gzip(map(
                "DataVersion", 4000,
                "size", List.of(3, 2, 3),
                "palette", List.of(map("Name", "minecraft:stone"),
                        map("Name", "minecraft:furnace", "Properties", map("facing", "north", "lit", "false")),
                        map("Name", "minecraft:air"), map("Name", "minecraft:chest")),
                "blocks", List.of(
                        map("pos", List.of(0, 0, 0), "state", 0),
                        map("pos", List.of(1, 0, 0), "state", 0),
                        map("pos", List.of(2, 0, 0), "state", 1),
                        map("pos", List.of(0, 1, 0), "state", 2),
                        map("pos", List.of(2, 1, 2), "state", 3, "nbt",
                                map("id", "minecraft:chest", "CustomName", "Loot \"box\"", "Lock", (byte) 1)))));
    }

    // ---- Parsing ----

    @Test
    void coordinatesAreFiniteNumbersNearTheOrigin() {
        assertEquals(-2.5f, SceneModel.coordinate(" -2.5\t"));
        assertEquals(65536, SceneModel.coordinate("65536"));
        assertTrue(Float.isNaN(SceneModel.coordinate(null)));
        assertTrue(Float.isNaN(SceneModel.coordinate("1 2")), "one number");
        assertTrue(Float.isNaN(SceneModel.coordinate("x")));
        assertTrue(Float.isNaN(SceneModel.coordinate("NaN")));
        assertTrue(Float.isNaN(SceneModel.coordinate("Infinity")));
        assertTrue(Float.isNaN(SceneModel.coordinate("1e9")), "too far out");
    }

    @Test
    void structureFilesGiveBlocksWithStatesAndBlockEntityData() throws Exception {
        Structure s = Structure.read(structure());
        assertEquals(List.of(3, 2, 3), List.of(s.sizeX(), s.sizeY(), s.sizeZ()));
        assertEquals(4, s.blocks().size(), "air is left out");
        assertEquals("minecraft:furnace[facing=north,lit=false]", s.blocks().get(2).state());
        SceneBlock chest = s.blocks().get(3);
        assertEquals("minecraft:chest", chest.id());
        assertEquals("{\"id\":\"minecraft:chest\",\"CustomName\":\"Loot \\\"box\\\"\",\"Lock\":1b}", chest.nbt());
    }

    @Test
    void brokenStructureFilesAreRefused() {
        assertThrowsIo(new byte[] {1, 2, 3});
        assertThrowsIo(NbtWriter.gzip(Map.of("size", List.of(1, 1, 1))));
        // A list that claims more items than the file holds.
        byte[] lying = {10, 0, 0, 9, 0, 1, 'x', 10, 0x7F, -1, -1, -1};
        assertThrowsIo(lying);
    }

    private static void assertThrowsIo(byte[] bytes) {
        try {
            Structure.read(bytes);
        } catch (java.io.IOException expected) {
            return;
        }
        throw new AssertionError("read");
    }

    // ---- Blocks and groups ----

    @Test
    void childrenApplyInDocumentOrderOnTopOfTheStructure() {
        TestHost host = host();
        host.binaries.put("test:hut.nbt", structure());
        Page page = host.load("""
                <scene id=s structure="hut.nbt">
                  <block x="1" y="1" z="1" state="minecraft:furnace[lit=true]"></block>
                  <block x="1" y="0" z="0" state="minecraft:air"></block>
                  <block x="0" y="0" z="0" state="minecraft:oak_planks"></block>
                  <block x="1" y="1" z="1" state="minecraft:glass"></block>
                </scene>""");
        SceneModel m = model(page);
        assertEquals(List.of("0,0,0=minecraft:oak_planks", "2,0,0=minecraft:furnace[facing=north,lit=false]",
                "1,1,1=minecraft:glass", "2,1,2=minecraft:chest"), blocks(m.groups().get(0)));
        assertArrayEquals(new float[] {0, 0, 0, 3, 2, 3}, m.bounds(), "the structure's size counts");
    }

    @Test
    void eachBlockBelongsToTheFirstGroupThatClaimsIt() {
        Page page = page("""
                <scene id=s>
                  <block x="0" y="0" z="0" state="stone"></block>
                  <block x="0" y="1" z="0" state="dirt"></block>
                  <block x="0" y="2" z="0" state="sand"></block>
                  <block-group id=a x="0" y="1..2" z="0"></block-group>
                  <block-group id=b x="0" y="0..9" z="0">
                    <block x="5" y="5" z="5" state="gold_block"></block>
                  </block-group>
                  <block-group id=c x="0" y="2" z="0"></block-group>
                </scene>""");
        List<SceneGroup> groups = model(page).groups();
        assertEquals(4, groups.size());
        assertNull(groups.get(0).element());
        assertEquals(List.of(), blocks(groups.get(0)), "every block is claimed");
        assertEquals(List.of("0,1,0=dirt", "0,2,0=sand"), blocks(groups.get(1)));
        assertEquals(List.of("0,0,0=stone", "5,5,5=gold_block"), blocks(groups.get(2)), "its own blocks too");
        assertEquals(List.of(), blocks(groups.get(3)), "a was first");
    }

    @Test
    void pointsNeedAllThreeOfXYAndZ() {
        Page page = page("""
                <scene id=s>
                  <block x="1" y="2" z="3" state="stone"></block>
                  <block x="4" y="2" state="dirt"></block>
                  <block x="1.5" y="2" z="3" state="sand"></block>
                </scene>""");
        assertEquals(List.of("1,2,3=stone"), blocks(model(page).groups().get(0)), "z missing, or not whole: left out");
    }

    @Test
    void groupRangesAreNumbersOrRangesAndALeftOutAxisIsAllOfIt() {
        assertArrayEquals(new int[] {3, 3}, SceneModel.range(" 3 "));
        assertArrayEquals(new int[] {2, 5}, SceneModel.range("2..5"));
        assertArrayEquals(new int[] {2, 5}, SceneModel.range("5..2"), "either order");
        assertArrayEquals(new int[] {-3, 1}, SceneModel.range("-3 .. 1"));
        assertNull(SceneModel.range("2-5"));
        assertNull(SceneModel.range("a..b"));
        Page page = page("""
                <scene id=s>
                  <block x="0" y="0" z="0" state="stone"></block>
                  <block x="9" y="1" z="-4" state="dirt"></block>
                  <block x="3" y="1" z="2" state="sand"></block>
                  <block-group id=layer y="1"></block-group>
                  <block-group id=bad y="up"></block-group>
                </scene>""");
        List<SceneGroup> groups = model(page).groups();
        assertEquals(List.of("0,0,0=stone"), blocks(groups.get(0)));
        assertEquals(List.of("9,1,-4=dirt", "3,1,2=sand"), blocks(groups.get(1)), "a whole layer, wherever it reaches");
        assertEquals(List.of(), blocks(groups.get(2)), "a range that doesn't parse claims nothing");
    }

    @Test
    void scriptsChangeTheBlocksAndOnlyTheGroupsTheyTouchChange() {
        Page page = page("""
                <scene id=s>
                  <block x="0" y="0" z="0" state="stone"></block>
                  <block-group id=roof x="0..4" y="3..4" z="0..4"><block x="1" y="3" z="1" state="oak_slab"></block></block-group>
                </scene>""");
        SceneModel m = model(page);
        SceneGroup base = m.groups().get(0), roof = m.groups().get(1);
        int baseVersion = base.version(), roofVersion = roof.version();
        page.run("let b = document.createElement('block'); b.setAttribute('x', 2); b.setAttribute('y', 4); b.setAttribute('z', 2); b.setAttribute('state', 'glass');"
                + "document.getElementById('s').appendChild(b)");
        page.frame();
        assertEquals(baseVersion, base.version(), "the base kept its blocks");
        assertNotEquals(roofVersion, roof.version());
        assertEquals(List.of("1,3,1=oak_slab", "2,4,2=glass"), blocks(roof));
        roofVersion = roof.version();
        page.run("document.querySelector('block').setAttribute('state', 'cobblestone')");
        page.frame();
        assertEquals(List.of("0,0,0=cobblestone"), blocks(base));
        assertEquals(roofVersion, roof.version());
        page.run("document.title = 'unrelated'; document.body.appendChild(document.createElement('p'))");
        page.frame();
        assertEquals(roofVersion, roof.version(), "other changes rebuild nothing");
        page.run("document.getElementById('roof').remove()");
        page.frame();
        assertEquals(List.of("0,0,0=cobblestone", "2,4,2=glass"), blocks(m.groups().get(0)), "unclaimed again");
        assertEquals(1, m.groups().size());
    }

    @Test
    void scriptsChangeTheBlocksInGroupsToo() {
        Page page = page("""
                <scene id=s><block-group id=g><block id=b x="0" y="0" z="0" state="stone"></block></block-group></scene>
                <div id=elsewhere></div>""");
        SceneGroup g = model(page).groups().get(1);
        page.run("let b = document.createElement('block'); b.setAttribute('x', 1); b.setAttribute('y', 0); b.setAttribute('z', 0);"
                + "b.setAttribute('state', 'dirt'); document.getElementById('g').appendChild(b)");
        page.frame();
        assertEquals(List.of("0,0,0=stone", "1,0,0=dirt"), blocks(g));
        page.run("document.getElementById('b').setAttribute('z', '2')");
        page.frame();
        assertEquals(List.of("1,0,0=dirt", "0,0,2=stone"), blocks(g));
        page.run("document.getElementById('b').setAttribute('style', 'color: red'); document.getElementById('g').className = 'x'");
        page.frame();
        int version = g.version();
        page.run("document.getElementById('elsewhere').appendChild(document.getElementById('b'))");
        page.frame();
        assertEquals(List.of("1,0,0=dirt"), blocks(g), "moved out of the scene");
        assertNotEquals(version, g.version());
    }

    @Test
    void sceneChildrenAreNotLaidOutButAnchorsAre() {
        Page page = page("""
                <scene id=s><block id=b x="0" y="0" z="0" state="stone"></block><block-group id=g></block-group>
                <entity id=e x="0" y="1" z="0"></entity><scene-anchor id=a x="0" y="1" z="0">Hi</scene-anchor></scene>""");
        assertNull(page.byId("b").box);
        assertNull(page.byId("g").box);
        assertNull(page.byId("e").box);
        assertNotNull(page.byId("a").box);
        assertTrue(page.byId("a").box.hosted());
        assertEquals(160, page.byId("s").box.width);
        assertEquals(120, page.byId("s").box.height);
    }

    // ---- Styles ----

    @Test
    void translateRollAndCentreParseAndSerialise() {
        ComputedStyle t = styleOf("-mc-translate: 1 -0.5");
        assertArrayEquals(new float[] {1, -0.5f, 0}, new float[] {t.translateX, t.translateY, t.translateZ});
        assertEquals("1 -0.5", computedValue(t, "-mc-translate"));
        assertEquals("0", computedValue(styleOf("color: red"), "-mc-translate"));
        assertEquals("0 0 2", computedValue(styleOf("-mc-translate: 0 0 2"), "-mc-translate"));
        assertEquals(0, styleOf("-mc-translate: 1 2 3 4").translateX, "four numbers are invalid");
        assertEquals(90, styleOf("-mc-roll: 0.25turn").modelRoll);
        assertEquals("90deg", computedValue(styleOf("-mc-roll: 90deg"), "-mc-roll"));
        ComputedStyle c = styleOf("-mc-scene-center: 1 2.5 3");
        assertArrayEquals(new float[] {1, 2.5f, 3}, new float[] {c.sceneCenterX, c.sceneCenterY, c.sceneCenterZ});
        assertEquals("1 2.5 3", computedValue(c, "-mc-scene-center"));
        assertTrue(Float.isNaN(styleOf("-mc-scene-center: 1 2 3; -mc-scene-center: auto").sceneCenterY));
        assertEquals("auto", computedValue(styleOf("color: red"), "-mc-scene-center"));
        assertEquals(0, styleOf("-mc-translate: 1px").translateX, "blocks are numbers");
    }

    @Test
    void groupsFollowTheirStyleEveryFrame() {
        Page page = page("""
                <style>#g { transition: opacity 100ms linear, -mc-translate 100ms linear }
                  #g.away { opacity: 0.2; -mc-translate: 0 -1 0 } .hidden { display: none }</style>
                <scene id=s><block-group id=g x="0..1" y="0..1" z="0..1"><block x="0" y="0" z="0" state="stone"></block></block-group></scene>""");
        SceneGroup g = model(page).groups().get(1);
        assertTrue(g.visible());
        assertFalse(g.moved());
        page.byId("g").addClass("away");
        page.frame(1000);
        page.frame(1050);
        assertEquals(0.6f, g.opacity(), 1e-3);
        float[] m = g.matrix();
        assertEquals(-0.5f, m[7], 1e-3, "halfway down");
        page.frame(1200);
        assertEquals(0.2f, g.opacity(), 1e-4);
        page.byId("g").addClass("hidden");
        page.frame(1300);
        assertFalse(g.visible());
    }

    @Test
    void yawPitchAndRollTurnAGroupAboutItsMiddle() {
        Page page = page("""
                <scene id=s><block-group id=g style="-mc-yaw: 90deg">
                  <block x="0" y="0" z="0" state="stone"></block><block x="1" y="0" z="0" state="stone"></block></block-group></scene>""");
        SceneGroup g = model(page).groups().get(1);
        // The middle is (1, 0.5, 0.5). A quarter yaw is clockwise seen from above, as entities turn: the west end
        // (-1, -0.5, -0.5 from the middle) goes north, to (0.5, -0.5, -1).
        assertArrayEquals(new float[] {1.5f, 0, -0.5f}, Mat.apply(g.matrix(), 0, 0, 0, new float[3]), 1e-5f);
        // Pitch tips the top north and roll tips it east: toward the default view, from the north-east.
        page.byId("g").setAttribute("style", "-mc-pitch: 90deg");
        page.frame();
        assertArrayEquals(new float[] {0, 0.5f, -0.5f}, Mat.apply(g.matrix(), 0, 1.5f, 0.5f, new float[3]), 1e-5f);
        page.byId("g").setAttribute("style", "-mc-roll: 90deg");
        page.frame();
        assertArrayEquals(new float[] {2, 0.5f, 0.5f}, Mat.apply(g.matrix(), 1, 1.5f, 0.5f, new float[3]), 1e-5f);
    }

    // ---- Camera, anchors and picking ----

    @Test
    void theCameraFitsTheBlocksAndCentresThem() {
        Page page = page("<scene id=s><block x='0' y='0' z='0' state='stone'></block><block x='3' y='2' z='3' state='stone'></block></scene>");
        SceneCamera c = model(page).camera();
        assertNotNull(c);
        assertEquals(30, c.tilt());
        float[] middle = c.project(2, 1.5f, 2);
        assertArrayEquals(new float[] {80, 60}, new float[] {middle[0], middle[1]}, 1e-3f);
        // Every corner of the bounds fits in the box, whatever the turn.
        for (float yaw = 0; yaw < 360; yaw += 15) {
            SceneCamera turned = SceneCamera.fit(model(page).bounds(), new float[] {Float.NaN, Float.NaN, Float.NaN},
                    yaw, 0, 1, 0, 0, 160, 120);
            for (int corner = 0; corner < 8; corner++) {
                float[] p = turned.project((corner & 1) * 4, (corner >> 1 & 1) * 3, (corner >> 2 & 1) * 4);
                assertTrue(p[0] >= 0 && p[0] <= 160 && p[1] >= 0 && p[1] <= 120, "corner " + corner + " at " + yaw);
            }
        }
        float[] top = c.project(2, 3, 2), bottom = c.project(2, 0, 2);
        assertTrue(top[1] < bottom[1], "y is up");
        float[] near = c.project(2, 3, 2), far = c.project(2, 0, 2);
        assertTrue(near[2] < far[2], "seen from above, the top is nearer");
    }

    @Test
    void cssAndDraggingTurnTheCamera() {
        Page page = page("<scene id=s rotatable style='-mc-yaw: 20deg; -mc-pitch: 10deg; -mc-scene-center: 0 0 0'>"
                + "<block x='0' y='0' z='0' state='stone'></block></scene>");
        SceneCamera c = model(page).camera();
        assertEquals(20, c.yaw());
        assertEquals(40, c.tilt());
        assertEquals(0, c.centerX());
        page.down(80, 60);
        page.move(90, 60);
        page.frame(1000); // held still before letting go: no spin
        page.up(90, 60);
        page.frame(1016);
        assertEquals(20 + 10 * Turntable.DEGREES_PER_PX, model(page).camera().yaw(), 1e-3, "dragging adds to the CSS");
    }

    @Test
    void anchorsSitAtTheirProjectedPoint() {
        Page page = page("""
                <div style='padding: 30px 0 0 40px'><scene id=s style='padding: 5px; border: 2px solid'>
                  <block x='0' y='0' z='0' state='stone'></block><block x='2' y='1' z='2' state='stone'></block>
                  <scene-anchor id=a x='1' y='2' z='1'>Here</scene-anchor>
                  <scene-anchor id=b x='0' y='0' z='0' style='transform: translate(-50%, -100%)'>Below</scene-anchor>
                </scene></div>""");
        SceneCamera c = model(page).camera();
        float[] p = c.project(1, 2, 1);
        float[] rect = page.byId("a").getBoundingClientRect();
        // The content box starts at the border and padding inside (40, 30).
        assertEquals(40 + 7 + p[0], rect[0], 1e-3);
        assertEquals(30 + 7 + p[1], rect[1], 1e-3);
        float[] q = c.project(0, 0, 0);
        float[] below = page.byId("b").getBoundingClientRect();
        assertEquals(47 + q[0], below[0] + below[2] / 2, 1e-3);
        assertEquals(37 + q[1], below[1] + below[3], 1e-3);
        page.byId("s").setAttribute("style", "padding: 5px; border: 2px solid; -mc-yaw: 90deg");
        page.frame();
        float[] turned = model(page).camera().project(1, 2, 1);
        float[] moved = page.byId("a").getBoundingClientRect();
        assertEquals(40 + 7 + turned[0], moved[0], 1e-3, "follows the camera");
        assertEquals(30 + 7 + turned[1], moved[1], 1e-3);
        assertEquals(page.byId("a"), page.doc.hitTest(moved[0] + 1, moved[1] + 1).element(),
                "hit testing finds it where it is drawn");
    }

    @Test
    void anchorsFollowTheirTranslate() {
        Page page = page("<scene id=s><block x='0' y='0' z='0' state='stone'></block>"
                + "<scene-anchor id=a x='0' y='0' z='0' style='-mc-translate: 0 1 0'>x</scene-anchor></scene>");
        float[] p = model(page).camera().project(0, 1, 0);
        assertEquals(p[1], page.byId("a").getBoundingClientRect()[1], 1e-3);
        page.byId("a").setAttribute("y", "2");
        page.frame();
        assertEquals(model(page).camera().project(0, 3, 0)[1], page.byId("a").getBoundingClientRect()[1], 1e-3,
                "and their point");
    }

    @Test
    void entitiesStandAtTheirPointPlusTranslate() {
        Page page = page("<scene id=s><entity id=e x='1.5' y='1' z='2.5' style='-mc-translate: 1 0 0'></entity>"
                + "<entity x='0' y='0' z='0' style='display: none'></entity></scene>");
        List<SceneEntity> entities = model(page).entities();
        assertEquals(1, entities.size());
        assertEquals(new SceneEntity(page.byId("e"), 2.5f, 1, 2.5f), entities.get(0));
    }

    @Test
    void theNearestShownBlockIsPicked() {
        Page page = page("""
                <scene id=s style='-mc-pitch: 60deg'>
                  <block x='0' y='0' z='0' state='stone'></block>
                  <block-group id=top><block x='0' y='1' z='0' state='glass'></block></block-group>
                </scene>""");
        SceneModel m = model(page);
        float[] middle = m.camera().project(0.5f, 2, 0.5f);
        assertEquals("glass", m.pick(middle[0], middle[1]).state(), "looking down: the top block");
        page.byId("top").setAttribute("style", "display: none");
        page.frame();
        assertEquals("stone", m.pick(middle[0], middle[1]).state(), "hidden groups are not picked");
        assertNull(m.pick(1, 1));
    }

    @Test
    void hoveringAndClickingBlocksFireEvents() {
        Page page = page("""
                <scene id=s><block-group id=g><block x='0' y='0' z='0' state='stone'></block></block-group></scene>
                <script>
                  window.seen = [];
                  const s = document.getElementById('s');
                  s.addEventListener('blockhover', e => seen.push('hover ' + (e.detail
                      ? e.detail.x + ',' + e.detail.y + ',' + e.detail.z + ' ' + e.detail.state + ' ' + e.detail.group.id
                      : 'none')));
                  s.addEventListener('blockclick', e => seen.push('click ' + e.detail.state));
                </script>""");
        SceneModel m = model(page);
        page.move(80, 60);
        page.frame();
        assertEquals("stone", m.hovered().state());
        assertEquals("stone", m.tooltipBlock().state());
        page.click(80, 60);
        page.frame();
        page.down(80, 60);
        page.move(120, 60);
        page.up(120, 60);
        page.move(2, 2);
        page.frame();
        assertNull(m.hovered());
        assertEquals("hover 0,0,0 stone g,click stone,hover none", page.eval("seen.join()"),
                "a drag is not a click");
        page.byId("s").setAttribute("notooltips", "");
        page.move(80, 60);
        page.frame();
        assertNull(m.tooltipBlock());
    }

    @Test
    void scenesWithoutABoxHaveNoCamera() {
        Page page = page("<scene id=s style='display: none'><block x='0' y='0' z='0' state='stone'></block></scene>");
        assertNull(model(page).camera());
        assertEquals(1, model(page).groups().get(0).blocks().size());
    }

    @Test
    void missingStructuresAreLoggedOnce() {
        TestHost host = host();
        Page page = host.load("<scene id=s structure='nope.nbt'></scene>");
        page.run("document.body.appendChild(document.createElement('p'))");
        page.frame();
        assertEquals(1, host.logs.stream().filter(l -> l.contains("Structure not found: test:nope.nbt")).count());
        assertTrue(model(page).groups().get(0).blocks().isEmpty());
    }
}
