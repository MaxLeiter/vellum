package dev.vellum.engine.script;

import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VellumApiTest {
    @Test
    void sendSerialisesJson() {
        Page page = new TestHost().load("<script>vellum.send('buy', {item: 'minecraft:apple', count: 2})</script>");
        assertArrayEquals(new String[] {"buy", "{\"item\":\"minecraft:apple\",\"count\":2}"}, page.host.sent.getFirst());
    }

    @Test
    void sendIsRateLimited() {
        Page page = new TestHost().load("<script>var results = []; for (let i = 0; i < 25; i++) results.push(vellum.send('spam', i));</script>");
        assertEquals(VellumApi.SEND_RATE, page.host.sent.size());
        assertEquals("false", page.eval("results[24]"));
        assertEquals(1, page.host.logs.stream().filter(l -> l.startsWith("WARN: vellum.send")).count());
        page.frame(500); // half a second refills half the bucket
        page.run("for (let i = 0; i < 25; i++) vellum.send('spam', i)");
        assertEquals(VellumApi.SEND_RATE + VellumApi.SEND_RATE / 2, page.host.sent.size());
    }

    @Test
    void receivedDataReplacesVellumDataAndNotifiesListeners() {
        Page page = new TestHost().load("""
                <script>
                const log = [];
                vellum.on('data', d => log.push('data ' + d.hp));
                const stop = vellum.on('toast', msg => log.push('toast ' + msg.text));
                </script>""");
        assertEquals("{}", page.eval("JSON.stringify(vellum.data)"));
        page.doc.receive("data", "{\"hp\": 20}");
        page.doc.receive("toast", "{\"text\": \"hi\"}");
        page.run("stop()");
        page.doc.receive("toast", "{\"text\": \"ignored\"}");
        assertEquals("data 20,toast hi 20", page.eval("log.join() + ' ' + vellum.data.hp"));
    }

    @Test
    void badJsonIsReported() {
        Page page = new TestHost().recordErrors().load("");
        page.doc.receive("data", "{nope");
        assertTrue(page.errors().contains("Error in message 'data': SyntaxError"), page.errors());
    }

    @Test
    void hostCalls() {
        Page page = new TestHost().load("""
                <script>
                vellum.playSound('minecraft:ui.button.click', 0.5, 2);
                var label = vellum.t('gui.done', 'x');
                vellum.close();
                </script>""");
        assertEquals(List.of("minecraft:ui.button.click"), page.host.sounds);
        assertEquals("gui.done", page.eval("label"));
        assertTrue(page.host.closed);
    }

    @Test
    void closingFiresPagehideThenUnloadWhileScriptsStillRun() {
        Page page = new TestHost().load("""
                <script>
                addEventListener('pagehide', () => vellum.send('bye', 'pagehide'));
                window.addEventListener('unload', () => {
                  vellum.send('bye', 'unload');
                  setTimeout(() => vellum.send('bye', 'too late'), 0);
                });
                </script>""");
        page.doc.close();
        assertEquals(List.of("pagehide", "unload"), page.host.sent.stream().map(m -> m[1].replace("\"", "")).toList());
        page.doc.frame(100);
        assertEquals(2, page.host.sent.size(), "timers die with the document");
    }
}
