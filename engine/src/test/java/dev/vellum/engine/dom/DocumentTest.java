package dev.vellum.engine.dom;

import dev.vellum.engine.event.Event;
import dev.vellum.engine.event.Modifiers;
import dev.vellum.engine.host.ReplacedContent;
import dev.vellum.engine.paint.Canvas;
import dev.vellum.engine.testing.RecordingCanvas;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The document's own behaviour: invalidation, template inertness, moves, form state, errors and timers. */
class DocumentTest {
    /** A host whose {@code <thing>} elements are replaced content that records its disposal (and can fail). */
    private static final class ThingHost extends TestHost {
        final List<String> disposed = new ArrayList<>();
        boolean fail;

        @Override
        public Map<String, Function<Element, ReplacedContent>> replacedElements() {
            return Map.of("thing", element -> new ReplacedContent() {
                public float intrinsicWidth() { return 16; }
                public float intrinsicHeight() { return 16; }
                public void paint(Canvas canvas, float x, float y, float width, float height) {}
                public boolean update() {
                    if (fail) throw new IllegalStateException("broken content");
                    return false;
                }
                public void dispose() { disposed.add(element.id()); }
            });
        }
    }

    private static void clean(Document doc) {
        doc.frame(0);
        assertFalse(doc.styleDirty || doc.layoutDirty);
    }

    @Test
    void formStateAndAttributesRestyleWithoutRelayout() {
        Document doc = new TestHost().load("<input id=t placeholder=p><input id=c type=checkbox><img id=i><div id=d></div>").doc;
        Element field = doc.getElementById("t");
        clean(doc);
        field.setValue("a");
        assertTrue(doc.styleDirty, ":placeholder-shown flipped");
        assertFalse(doc.layoutDirty);
        clean(doc);
        field.setValue("ab");
        assertFalse(doc.styleDirty || doc.layoutDirty, "typing more changes no selector");
        doc.getElementById("c").setChecked(true);
        doc.getElementById("d").setAttribute("class", "x");
        assertTrue(doc.styleDirty);
        assertFalse(doc.layoutDirty, "the restyle decides whether layout must run");
        doc.getElementById("i").setAttribute("width", "20");
        assertTrue(doc.layoutDirty, "layout reads a replaced element's size attributes itself");
        clean(doc);
        Element detached = doc.createElement("div");
        detached.setAttribute("class", "y");
        detached.appendChild(doc.createTextNode("text"));
        assertFalse(doc.styleDirty || doc.layoutDirty, "detached nodes do not render");
    }

    @Test
    void templateContentsAreInert() {
        TestHost host = new TestHost();
        Document doc = Document.parse(host, "test:x.html",
                "<template id=t><p id=inner class=k></p><script>console.log('ran')</script></template>");
        assertNull(doc.getElementById("inner"));
        assertNull(doc.querySelector(".k"));
        assertTrue(doc.getElementsByTagName("p").isEmpty());
        Element template = doc.getElementById("t");
        template.appendChild(doc.createElement("script")).setTextContent("console.log('inserted')");
        assertEquals(List.of(), host.logs, "scripts in templates never run");
        Node copy = template.firstChild().cloneNode(true);
        doc.body().appendChild(copy);
        assertSame(copy, doc.getElementById("inner"), "a clone of the content is live");
    }

    @Test
    void movingKeepsStateLeavingDisposes() {
        ThingHost host = new ThingHost();
        Document doc = host.load("<div id=a><thing id=x></thing><input id=f></div><div id=b></div>").doc;
        Element thing = doc.getElementById("x"), field = doc.getElementById("f");
        ReplacedContent content = thing.replaced;
        assertNotNull(content);
        field.focus();
        doc.getElementById("b").appendChild(thing);
        doc.getElementById("b").insertBefore(field, thing);
        assertSame(content, thing.replaced, "a move keeps replaced content");
        assertSame(field, doc.focusedElement(), "and focus");
        assertEquals(List.of(), host.disposed);
        assertSame(thing, field.nextSibling());
        assertSame(field, thing.previousSibling());
        thing.remove();
        assertEquals(List.of("x"), host.disposed);
        assertNull(thing.replaced);
    }

    @Test
    void mcTextExpandsWheneverItArrivesOrChanges() {
        TestHost host = new TestHost() {
            @Override
            public String translate(String key, String... args) {
                return key + "(" + String.join("|", args) + ")";
            }

            @Override
            public List<TextRun> formatText(String json) {
                return json.equals("bad") ? null : List.of(new TextRun("Gold", "color: gold"), new TextRun("!", ""));
            }
        };
        Document doc = host.load("<mc-text id=a key=k args='x, y'>fallback</mc-text><mc-text id=b json=bad>kept</mc-text>").doc;
        assertEquals("k(x|y)", doc.getElementById("a").textContent());
        assertEquals("kept", doc.getElementById("b").textContent(), "content stays when the host cannot format it");
        doc.getElementById("a").setAttribute("key", "other");
        assertEquals("other(x|y)", doc.getElementById("a").textContent());
        doc.body().setInnerHTML("<p><mc-text id=c json='{}'></mc-text></p>");
        Element c = doc.getElementById("c");
        assertEquals("<span style=\"color: gold\">Gold</span>!", c.innerHTML());
    }

    @Test
    void framesAreNeededOnlyWhileSomethingChanges() {
        Document doc = new TestHost().load("<div id=s style='overflow: auto; height: 10px'><p style='height: 50px'></p></div>").doc;
        doc.paint(new RecordingCanvas());
        assertFalse(doc.needsFrame(16), "idle");
        doc.getElementById("s").scrollTo(0, 5);
        assertTrue(doc.needsFrame(16), "a scroll repaints");
        doc.frame(16);
        doc.paint(new RecordingCanvas());
        doc.scheduler().setTimeout(() -> {}, 100);
        assertFalse(doc.needsFrame(50));
        assertTrue(doc.needsFrame(116), "a due timer");
    }

    @Test
    void selectionIsPerOption() {
        Document doc = new TestHost().load("<select id=s><option>a<option value=x>first x<option value=x>second x</select>").doc;
        Element select = doc.getElementById("s");
        List<Element> options = select.options();
        assertSame(options.get(0), select.selectedOption(), "the first enabled option by default");
        options.get(2).setSelected(true);
        assertEquals(2, select.selectedIndex(), "options with the same value stay distinct");
        assertEquals("x", select.value());
        assertTrue(select.matches("select:has(option:checked:last-child)"));
        select.setValue("x");
        assertEquals(1, select.selectedIndex(), "a value selects the first option that has it");
        select.setValue("nothing");
        assertEquals(-1, select.selectedIndex());
        assertEquals("", select.value());
        select.setSelectedIndex(0);
        assertEquals("a", select.value());
    }

    @Test
    void checkingARadioUnchecksItsGroup() {
        Document doc = new TestHost().load("<form><input type=radio name=r id=a checked><input type=radio name=r id=b></form>"
                + "<input type=radio name=r id=other checked>").doc;
        doc.getElementById("b").setChecked(true);
        assertFalse(doc.getElementById("a").checked());
        assertTrue(doc.getElementById("other").checked(), "a radio outside the form is another group");
    }

    @Test
    void inputTypesFollowHtml() {
        Document doc = new TestHost().load("<input id=a type=' Number '><input id=b type=date><input id=c type=checkbox>"
                + "<textarea id=d></textarea><input id=e type=submit>").doc;
        assertEquals("number", doc.getElementById("a").inputType());
        assertEquals("text", doc.getElementById("b").inputType(), "unknown types are text");
        assertTrue(doc.getElementById("b").isTextControl());
        assertTrue(doc.getElementById("c").isCheckable());
        assertEquals("on", doc.getElementById("c").value());
        assertTrue(doc.getElementById("d").isTextControl());
        assertFalse(doc.getElementById("e").hasLiveValue());
        assertEquals("", doc.body().inputType());
    }

    @Test
    void engineFailuresStopTheDocumentOnce() {
        ThingHost host = new ThingHost();
        Document doc = host.recordErrors().load("<thing id=x></thing>").doc;
        host.fail = true;
        doc.frame(16);
        assertNotNull(doc.error());
        doc.frame(32);
        assertFalse(doc.input().mouseMove(1, 1, Modifiers.NONE), "a stopped document takes no input");
        assertEquals(1, host.errors.size(), "reported once");
        doc.close();
        assertEquals(List.of("x"), host.disposed, "closing still disposes");
    }

    @Test
    void flushesFireNoEvents() {
        Document doc = Document.parse(new TestHost(), "test:x.html", "<input id=f autofocus>");
        doc.flushLayout();
        assertTrue(doc.getElementById("f").box != null);
        assertNull(doc.focusedElement(), "autofocus waits for the frame");
        doc.frame(0);
        assertSame(doc.getElementById("f"), doc.focusedElement());
    }

    @Test
    void dispatchSkipsTypesNobodyHandles() {
        Document doc = new TestHost().load("<div id=d></div>").doc;
        Element d = doc.getElementById("d");
        assertFalse(doc.handles("ping"));
        List<String> log = new ArrayList<>();
        d.addEventListener("ping", e -> log.add("once"), false, true);
        assertTrue(doc.handles("ping"));
        d.dispatchEvent(new Event("ping"));
        d.dispatchEvent(new Event("ping"));
        assertEquals(List.of("once"), log);
        assertFalse(doc.handles("ping"), "the once listener is gone");
        d.setAttribute("onPing", "x()");
        assertTrue(doc.handles("ping"), "inline handlers count too");
        d.removeAttribute("onping");
        assertFalse(doc.handles("ping"));
        Event e = new Event("ping");
        d.dispatchEvent(e);
        assertSame(d, e.target(), "a skipped dispatch still targets");
    }

    @Test
    void animationFramesCanBeCancelledDuringTheirFrame() {
        Scheduler s = new TestHost().load("").doc.scheduler();
        List<String> log = new ArrayList<>();
        int[] second = new int[1];
        s.requestAnimationFrame(t -> {
            log.add("first");
            s.cancelAnimationFrame(second[0]);
            s.requestAnimationFrame(u -> log.add("next frame"));
        });
        second[0] = s.requestAnimationFrame(t -> log.add("second"));
        s.run(16);
        s.run(32);
        assertEquals(List.of("first", "next frame"), log);
    }
}
