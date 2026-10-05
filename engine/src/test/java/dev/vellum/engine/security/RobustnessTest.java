package dev.vellum.engine.security;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Viewport;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.RecordingCanvas;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Whatever a page does, failures end at the document's error boundary (or the page's error report), never above. */
class RobustnessTest {
    /**
     * Recursion through host calls grows the Java stack, which the interpreter's depth limit does not see. The
     * overflow is reported as an error of that entry, and the page keeps working.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "var b = document.getElementById('b'); b.onclick = function () { b.click() }; b.click()",
            "var o = {toString: function () { return String(o) }}; String(o)",
            "var o = {}; Object.defineProperty(o, 'x', {get: function () { return JSON.stringify(o) }, enumerable: true}); o.x",
            "document.addEventListener('x', function () { document.dispatchEvent(new Event('x')) }); document.dispatchEvent(new Event('x'))",
            "[1].forEach(function f() { [1].forEach(f) })",
    })
    void stackOverflowThroughHostCallsIsReported(String code) {
        Page page = new TestHost().recordErrors().load("<button id=b></button>");
        page.run(code);
        assertTrue(page.errors().contains("too much recursion") || page.errors().contains("stack depth"), page.errors());
        assertNull(page.doc.error());
        assertEquals("2", page.eval("1 + 1"));
    }

    /** Any throwable from the engine or the host stops the page; none escapes to the caller (the game's frame). */
    @Test
    void errorsOfAnyKindStopOnlyThePage() {
        TestHost host = new TestHost() {
            @Override
            public String loadText(String url) {
                throw new LinkageError("broken host");
            }
        }.recordErrors();
        Document doc = Document.parse(host, "test:page.html", "<script src=a.js></script>", null, new Viewport(320, 240, 2));
        assertInstanceOf(LinkageError.class, doc.error());
        doc.frame(16);
        doc.paint(new RecordingCanvas());
        assertEquals(1, host.errors.size(), host.errors.toString());
    }

    @Test
    void deepJsonFromTheServerIsRefused() {
        TestHost host = new TestHost().recordErrors();
        String json = "[".repeat(50_000) + "]".repeat(50_000);
        Document doc = Document.parse(host, "test:page.html", "<p>a</p>", json, new Viewport(320, 240, 2));
        assertNull(doc.error(), host.errors.toString());
        assertTrue(host.errors.toString().contains("JSON nested deeper"), host.errors.toString());
        doc.receive("data", json);
        assertNull(doc.error());
    }
}
