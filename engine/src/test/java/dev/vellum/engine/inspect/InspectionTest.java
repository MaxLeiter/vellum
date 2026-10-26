package dev.vellum.engine.inspect;

import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InspectionTest {
    private static final String TARGET = """
            <style>.card { padding: 3px; color: #f00; }</style>
            <div id="main" class="card wide"><b>Hello</b><i>there</i></div>""";

    /** A target page, and an inspector page whose script inspects it with {@code script}. */
    private static Page inspector(Page target, String script) {
        TestHost host = new TestHost();
        host.inspection = new Inspection();
        host.inspection.retarget(target.doc);
        return host.load("<script>" + script + "</script>");
    }

    @Test
    void theTreeNamesElementsAndShowsTheTextOfElementsHoldingOnlyText() {
        Page target = new TestHost().load(TARGET);
        Page panel = inspector(target, "");
        panel.run("var t = inspector.tree(), div = t.kids[1].kids[0]");
        assertEquals("<html> <head>,<body>", panel.eval("t.label + ' ' + t.kids.map(k => k.label).join()"));
        assertEquals("<div#main.card.wide> <b> Hello", panel.eval("div.label + ' ' + div.kids[0].label + ' ' + div.kids[0].text"));
        assertEquals("undefined", panel.eval("typeof div.text"), "it holds elements");
    }

    @Test
    void detailsGiveAttributesBoxAndComputedValuesThatDifferFromTheInitialOnes() {
        Page target = new TestHost().load(TARGET);
        Page panel = inspector(target, "const t = inspector.tree(); var id = t.kids[1].kids[0].id, d = inspector.details(id);");
        assertEquals("id=main,class=card wide", panel.eval("d.attributes.map(a => a.join('=')).join()"));
        assertEquals("3 3 3 3", panel.eval("d.box.padding.join(' ')"));
        assertEquals("rgb(255, 0, 0)", panel.eval("d.computed.find(c => c[0] === 'color')[1]"));
        assertEquals("false", panel.eval("String(d.computed.some(c => c[0] === 'opacity'))"));
        assertEquals("<html>,<body>", panel.eval("d.path.map(p => p[1]).join()"));
        assertEquals("undefined", panel.eval("typeof d.text"), "it holds elements, not only text");
    }

    @Test
    void editsChangeTheInspectedPage() {
        Page target = new TestHost().load(TARGET);
        Page panel = inspector(target, """
                const div = inspector.tree().kids[1].kids[0], b = div.kids[0];
                inspector.setAttribute(div.id, 'style', 'margin: 5px');
                inspector.setAttribute(div.id, 'class', null);
                inspector.setText(b.id, 'Bye');
                var badName = inspector.setAttribute(div.id, 'a b', 'x'), textOfDiv = inspector.setText(div.id, 'no');
                inspector.remove(div.kids[1].id);""");
        assertEquals("margin: 5px", target.byId("main").getAttribute("style"));
        assertFalse(target.byId("main").hasAttribute("class"));
        assertEquals("Bye", target.byId("main").textContent());
        assertEquals("false false", panel.eval("badName + ' ' + textOfDiv"));
        target.frame();
        assertEquals(5f, target.byId("main").box.marginLeft);
    }

    @Test
    void theStampFollowsTheInspectedPageAndASelectionEndsPicking() {
        Page target = new TestHost().load(TARGET);
        Inspection inspection = new Inspection();
        inspection.retarget(target.doc);
        Object stamp = inspection.state().get("stamp");
        target.byId("main").setAttribute("title", "x");
        assertTrue(!stamp.equals(inspection.state().get("stamp")));
        inspection.pick(true);
        inspection.hover(target.byId("main"));
        assertSame(target.byId("main"), inspection.highlighted());
        inspection.select(target.query("b"));
        assertFalse(inspection.picking());
        assertSame(target.query("b"), inspection.highlighted());
        assertEquals(inspection.state().get("selected"), inspection.state().get("selected"));
        inspection.retarget(new TestHost().load("<p>other</p>").doc);
        assertNull(inspection.highlighted(), "a reloaded page starts with nothing selected");
    }

    @Test
    void otherPagesHaveNoInspectorGlobal() {
        assertEquals("undefined", new TestHost().load("").eval("typeof inspector"));
    }

    /** The real panel, loaded against a page: its script runs, lists the tree, and selects what it is told to. */
    @Test
    void theInspectorPanelShowsTheTreeAndTheSelectedElement() throws IOException {
        Path file = Path.of("../common/src/main/resources/assets/vellum/vellum/dev/inspector.html");
        Page target = new TestHost().load(TARGET);
        TestHost host = new TestHost();
        host.inspection = new Inspection();
        host.inspection.retarget(target.doc);
        Page panel = host.load(Files.readString(file), 240, 240);
        assertTrue(panel.doc.querySelectorAll("#tree .row").size() >= 4, "html, head, body and the div at least");
        host.inspection.select(target.byId("main"));
        panel.frame(300);
        assertEquals("div#main.card.wide", panel.query("#tree .row.selected").textContent().strip().substring(1), "after its ▸");
        assertTrue(panel.query("#details").textContent().contains("Attributes"));
        panel.click(panel.query("#pick")).frame(600);
        assertTrue(host.inspection.picking());

        float[] bar = panel.centre(panel.query("#split"));
        float before = panel.query("#tree").box.height;
        panel.down(bar[0], bar[1]);
        panel.move(bar[0], bar[1] - 40);
        panel.up(bar[0], bar[1] - 40);
        panel.frame(700);
        assertEquals(before - 40, panel.query("#tree").box.height, 0.5f, "dragging the bar up shrinks the tree");
    }
}
