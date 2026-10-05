package dev.vellum.engine.security;

import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a page from an untrusted server can do through the engine's {@link dev.vellum.engine.host.Host}: the engine
 * passes these requests on, and the Minecraft host decides (DocumentDriver, McHost, ServerPages). These tests pin the
 * engine side, so a change that widens it is noticed.
 */
class HostBoundaryTest {
    /** The page can keep Escape: the host must have a close key of its own (DocumentDriver.forceClose). */
    @Test
    void pageCanCancelEscape() {
        Page page = new TestHost().load("<script>addEventListener('keydown', e => { if (e.key === 'Escape') e.preventDefault() })</script>");
        assertTrue(page.key("Escape"), "a cancelled Escape is reported as consumed, so the screen would stay open");
    }

    /** Scripts navigate without a click, so the host must not open web links unless the player just acted. */
    @Test
    void scriptsNavigateWithoutUserInput() {
        Page page = new TestHost().load("<script>setInterval(() => location.href = 'https://example.com/', 0)</script>");
        page.frame().frame().frame();
        assertTrue(page.host.navigations.size() >= 3, page.host.navigations.toString());
        assertEquals("https://example.com/", page.host.navigations.getFirst());
    }

    /** Sound ids, volume and pitch reach the host as the page wrote them; McHost checks and caps them. */
    @Test
    void soundRequestsReachTheHostUnchecked() {
        Page page = new TestHost().load("<script>for (let i = 0; i < 500; i++) vellum.playSound('nope:missing', 1e9, 1e9)</script>");
        assertEquals(500, page.host.sounds.size());
    }

    /** Only canvases can be drawn into a canvas, so getImageData never reads pixels of a texture or skin. */
    @Test
    void canvasesDrawOnlyCanvases() {
        Page page = new TestHost().recordErrors().load("""
                <canvas id="c" width="4" height="4"></canvas><img id="i" src="minecraft:textures/block/stone.png">
                <script>
                const ctx = document.getElementById('c').getContext('2d');
                try { ctx.drawImage(document.getElementById('i'), 0, 0); var result = 'drawn'; }
                catch (e) { var result = e.name; }
                </script>""");
        assertEquals("TypeError", page.eval("result"));
    }

    /** URLs with a scheme are passed to the host as written; the host's loadText must never fetch them. */
    @Test
    void schemeUrlsReachLoadTextVerbatim() {
        List<String> asked = new java.util.ArrayList<>();
        TestHost recording = new TestHost() {
            @Override
            public String loadText(String url) {
                asked.add(url);
                return null;
            }
        };
        recording.load("""
                <link rel="stylesheet" href="https://example.com/a.css">
                <link rel="stylesheet" href="file:///etc/passwd">
                <script src="../../secret.js"></script>""");
        // ".." cannot climb above the namespace: the script resolves inside the test page's own namespace.
        assertEquals(java.util.Set.of("https://example.com/a.css", "file:///etc/passwd", "test:secret.js"), java.util.Set.copyOf(asked));
    }

    /** JSON nested deeper than the stack can parse fails the page, not the game. The network layer caps depth first. */
    @Test
    void deeplyNestedDataStaysInsideTheDocument() {
        Page page = new TestHost().recordErrors().load("<script>vellum.on('data', d => {})</script>");
        String deep = "[".repeat(200_000) + "]".repeat(200_000);
        assertDoesNotThrow(() -> page.doc.receive("data", deep));
        assertDoesNotThrow(() -> page.frame());
    }

    /**
     * Markup from other players (a name or chat line put into innerHTML or v-html) can't run a script tag, but its
     * inline event handlers run when clicked, and then send as the viewing player. Mod authors must pass such text as
     * data and show it as text.
     */
    @Test
    void injectedMarkupRunsInlineHandlers() {
        Page page = new TestHost().load("""
                <div id="log"></div>
                <script>
                const name = '<b id="evil" onclick="vellum.send(\\'buy\\', 64)" style="display:block;width:50px;height:20px">x</b><script>vellum.send(\\'ran\\', 1)</' + 'script>';
                document.getElementById('log').innerHTML = name;
                </script>""");
        assertTrue(page.host.sent.isEmpty(), "a script tag from innerHTML does not run");
        page.click(page.byId("evil"));
        assertEquals("buy", page.host.sent.getFirst()[0], "an inline handler from innerHTML runs on click");
    }
}
