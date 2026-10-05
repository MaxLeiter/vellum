package dev.vellum.engine;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.style.Colors;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.RecordingCanvas;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whole pages through the whole pipeline, driven only as a host drives them: HTML and CSS in, pointer and keyboard
 * input at viewport points, frames at chosen times, and what gets painted out.
 */
class EndToEndTest {
    @Test
    void templatedCardsHoverWithATransitionAndPickOnClick() {
        Page page = new TestHost().load("""
                <style>
                  .row { display: flex; gap: 4px; padding: 4px }
                  .card { width: 60px; height: 30px; background: #333; transition: background-color 100ms linear }
                  .card:hover { background: #f00 }
                  .card.picked { border: 2px solid #ff0 }
                </style>
                <div class=row>
                  <div v-for="item in s.items" :key="item" :id="item" class=card :class="{picked: s.picked === item}"
                       @click="s.picked = item">{{ item }}</div>
                </div>
                <p id=status>picked: {{ s.picked || 'none' }}</p>
                <script>const s = vellum.state({items: ['a', 'b', 'c'], picked: null})</script>""");
        Element b = page.byId("b");
        assertArrayEquals(new float[] {68, 4, 60, 30}, b.getBoundingClientRect(), 1e-3f, "the second of three cards");
        assertArrayEquals(new float[] {0, 38, 320, 9}, page.byId("status").getBoundingClientRect(), 1e-3f);
        assertEquals("picked: none", page.byId("status").textContent());

        page.hover(b);
        assertTrue(b.isHovered());
        page.frame(100); // restyles: the transition starts now
        page.frame(150);
        int halfway = b.style.backgroundColor;
        assertEquals(0xFF, Colors.alpha(halfway));
        assertEquals((0x33 + 0xFF) / 2, Colors.red(halfway), 1);
        assertEquals(0x33 / 2, Colors.green(halfway), 1);
        assertEquals("rgb(" + Colors.red(halfway) + ", " + Colors.green(halfway) + ", " + Colors.blue(halfway) + ")",
                page.computed("#b", "background-color"), "what getComputedStyle reports");
        RecordingCanvas.Call fill = page.paint().ops("fillRect").stream()
                .filter(c -> c.x() == 68 && c.w() == 60).findFirst().orElseThrow();
        assertEquals(halfway, fill.color(), "painted mid-transition");

        page.click(b);
        page.frame(200);
        assertEquals("picked: b", page.byId("status").textContent());
        assertTrue(b.hasClass("picked"));
        assertEquals(0xFFFF0000, b.style.backgroundColor, "the transition ended at 200 ms");
        assertArrayEquals(new float[] {68, 4, 60, 30}, b.getBoundingClientRect(), 1e-3f,
                "border-box: the border fits inside");
        List<String> trace = page.paint().trace();
        assertTrue(trace.contains("rect 68,4 60x30 #ffff0000"), trace::toString);
        assertTrue(trace.contains("border 68,4 60x30 #ffffff00"), trace::toString);
        assertTrue(trace.contains("text 'b' 70,6 #ffffffff"), "the text moved in by the border: " + trace);
    }

    @Test
    void clicksMapThroughScrollingAndTransforms() {
        Page page = new TestHost().load("""
                <div id=scroller style="position: absolute; left: 20px; top: 10px; width: 100px; height: 60px;
                                        overflow: auto">
                  <div style="height: 100px"></div>
                  <button id=target style="display: block; width: 40px; height: 20px; padding: 0;
                                           transform: translate(30px, 5px) rotate(90deg)"
                          onclick="clicks.push(event.offsetX + ',' + event.offsetY)">go</button>
                  <div style="height: 100px"></div>
                </div>
                <script>const clicks = []</script>""");
        Element target = page.byId("target");
        page.byId("scroller").scrollTo(0, 80);
        // Laid out at (20, 30) after the scroll; turned a quarter about its centre (40, 40) and moved by (30, 5),
        // its 40x20 box covers x 60..80, y 25..65.
        assertArrayEquals(new float[] {60, 25, 20, 40}, target.getBoundingClientRect(), 1e-3f);
        page.click(target);
        page.click(70, 30); // 15px above the centre on screen: 15px left of it in the button
        assertEquals("20,10 5,10", page.eval("clicks.join(' ')"));
        assertTrue(target.isFocused());
        RecordingCanvas.Call label = page.paint().ops("drawText").getFirst();
        assertEquals("go", label.text());
        assertArrayEquals(new float[] {20, 10, 100, 60}, label.clip(), 1e-3f, "clipped by the scroller's padding box");
    }

    @Test
    void formsBindThroughRealInput() {
        Page page = new TestHost().load("""
                <form>
                  <input id=name v-model="s.name" style="display: block">
                  <select id=mode v-model="s.mode" style="display: block; width: 80px"><option>easy<option>hard</select>
                  <label id=agree style="display: block"><input id=ok type=checkbox v-model="s.ok"> I agree</label>
                </form>
                <p id=out>{{ s.name }} / {{ s.mode }} / {{ s.ok }}</p>
                <script>const s = vellum.state({name: '', mode: 'easy', ok: false})</script>""");
        Element name = page.byId("name"), mode = page.byId("mode");

        page.click(name).type("Alex");
        page.frame();
        assertEquals("Alex", name.value());
        assertTrue(page.paint().trace().stream().anyMatch(l -> l.startsWith("text 'Alex'")));

        float[] select = mode.getBoundingClientRect();
        page.click(mode); // opens the list under the select, one 12px row per option
        page.click(select[0] + 10, select[1] + select[3] + 1 + 12 + 6);
        assertEquals("hard", mode.value());

        float[] label = page.byId("agree").getBoundingClientRect();
        page.click(label[0] + 40, label[1] + label[3] / 2); // on the label's text, not the box
        assertTrue(page.byId("ok").checked());

        page.frame();
        assertEquals("Alex / hard / true", page.byId("out").textContent());
    }

    @Test
    void canvasDrawingReadsBackAndPaints() {
        Page page = new TestHost().load("""
                <canvas id=c width=8 height=4 style="display: block; width: 16px; height: 8px"></canvas>
                <script>
                const ctx = document.getElementById('c').getContext('2d');
                ctx.fillStyle = '#ff0000';
                ctx.fillRect(0, 0, 4, 4);
                ctx.fillStyle = 'rgba(0, 0, 255, 0.5)';
                ctx.fillRect(2, 0, 4, 2);
                const pixel = (x, y) => Array.from(ctx.getImageData(x, y, 1, 1).data).join(',');
                </script>""");
        assertEquals("255,0,0,255", page.eval("pixel(0, 0)"));
        assertEquals("127,0,128,255", page.eval("pixel(3, 1)"), "half blue over red");
        assertEquals("0,0,255,128", page.eval("pixel(5, 1)"));
        assertEquals("0,0,0,0", page.eval("pixel(7, 3)"));
        assertEquals(List.of("image surface:8x4 0,0 16x8"), page.paint().trace(), "the surface, scaled to the box");
    }
}
