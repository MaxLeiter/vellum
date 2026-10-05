package dev.vellum.engine.security;

import dev.vellum.engine.Limits;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A server-sent page tries to exhaust the game's memory, which would crash it. */
class ScriptMemoryTest {
    private static final Duration QUICKLY = Duration.ofSeconds(10);

    /** An entry that keeps allocating is stopped by the memory budget long before the instruction budget. */
    @Test
    void allocationBudgetStopsAnEntry() {
        TestHost host = new TestHost().recordErrors().limits(Limits.DEFAULTS.with("entryAllocation", 16 << 20));
        Page page = host.load("");
        assertTimeoutPreemptively(QUICKLY, () -> page.run("var keep = []; for (;;) keep.push(new Array(1000).fill(0))"));
        assertTrue(page.errors().contains("allocated more than 16 MiB"), page.errors());
        assertNull(page.doc.error(), "one overrun leaves the page running");
    }

    /** Memory overruns, once they stop the page, let go of everything it built. */
    @Test
    void memoryOverrunsStopThePageAndLetGo() {
        TestHost host = new TestHost().recordErrors()
                .limits(Limits.DEFAULTS.with("entryAllocation", 16 << 20).with("maxBudgetOverruns", 1));
        Page page = assertTimeoutPreemptively(QUICKLY, () -> host.load(
                "<p>a</p><script>var keep = []; for (;;) keep.push(new Array(1000).fill(0))</script>"));
        assertInstanceOf(OutOfMemoryError.class, page.doc.error());
        assertNull(page.doc.scripts(), "the script runtime, and the global holding `keep`, are dropped");
        assertEquals(0, page.doc.nodeCount());
    }

    /**
     * A page whose script finds the heap nearly full after a collection is stopped at once and its memory released,
     * so a page that keeps what it allocates, a little per entry, cannot fill the heap. (A 1% limit makes any page
     * trip it.)
     */
    @Test
    void nearlyFullHeapStopsThePage() {
        System.gc(); // so the heap's use after a collection is known
        TestHost host = new TestHost().recordErrors().limits(Limits.DEFAULTS.with("heapLimitPercent", 1));
        Page page = host.load("<script>var a = 1</script>");
        assertInstanceOf(OutOfMemoryError.class, page.doc.error(), page.errors());
        assertTrue(page.errors().contains("out of memory"), page.errors());
        assertNull(page.doc.scripts());
    }

    /**
     * Strings built by concatenation cannot be capped (Rhino builds them lazily, in Java). Flattening a gigabyte one
     * runs out of memory in a single allocation that never happens, and the error stops the page, not the game.
     */
    @Test
    void outOfMemoryInAScriptStopsOnlyThePage() {
        TestHost host = new TestHost().recordErrors();
        Page page = assertTimeoutPreemptively(QUICKLY, () -> host.load(
                "<script>var s = 'xy'; for (var i = 0; i < 29; i++) s += s; s.indexOf('z')</script>"));
        assertInstanceOf(OutOfMemoryError.class, page.doc.error());
        assertTrue(page.errors().contains("ran out of memory"), page.errors());
        assertNull(page.doc.scripts());
        page.frame(16); // stopped documents ignore the host
        page.paint();
    }

    @Test
    void canvasPixelsAreCappedPerPage() {
        Page page = new TestHost().recordErrors().load("");
        page.run("""
                var made = 0;
                for (var i = 0; i < 6; i++) {
                  var c = document.createElement('canvas');
                  c.width = 2048; c.height = 2048;
                  c.getContext('2d').fillRect(0, 0, 1, 1);
                  made++;
                }""");
        assertEquals("4", page.eval("made"), "four 2048 px squares fit, the fifth does not");
        assertTrue(page.errors().contains("canvases may have at most"), page.errors());
    }

    @Test
    void resizingACanvasGivesItsPixelsBack() {
        Page page = new TestHost().load("<canvas id=c width=2048 height=2048></canvas>");
        for (int i = 0; i < 10; i++) page.run("document.getElementById('c').height = " + (2047 + i % 2));
        page.run("document.getElementById('c').remove()");
        page.run("for (var i = 0; i < 4; i++) { var c = document.createElement('canvas'); c.width = c.height = 2048; c.getContext('2d') }");
    }

    @Test
    void imageDataIsCapped() {
        Page page = new TestHost().load("<canvas id=c></canvas>");
        assertEquals("RangeError", page.eval(
                "(function () { try { document.getElementById('c').getContext('2d').createImageData(4096, 4096) } catch (e) { return e.name } })()"));
    }

    @Test
    void consoleFloodIsRateLimitedAndCut() {
        Page page = new TestHost().load("");
        page.run("var s = 'x'.repeat(1e6); for (var i = 0; i < 10000; i++) console.log(s)");
        int rate = Limits.DEFAULTS.logRate();
        assertTrue(page.host.logs.size() <= rate + 1, page.host.logs.size() + " lines");
        for (String line : page.host.logs) assertTrue(line.length() < Limits.DEFAULTS.maxLogLength() + 100, line.length() + " chars");
    }

    @Test
    void errorFloodIsRateLimited() {
        TestHost host = new TestHost().recordErrors();
        Page page = host.load("<script>for (var i = 0; i < 1000; i++) setTimeout(function () { throw new Error('x') }, 0)</script>");
        page.frame(16);
        assertTrue(host.errors.size() <= Limits.DEFAULTS.logRate(), host.errors.size() + " errors logged");
    }
}
