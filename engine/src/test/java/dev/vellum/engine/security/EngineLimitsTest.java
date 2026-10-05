package dev.vellum.engine.security;

import dev.vellum.engine.Limits;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Node;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A server-sent page tries to freeze or crash the game with markup and styles alone, or with DOM calls. */
class EngineLimitsTest {
    private static final Duration QUICKLY = Duration.ofSeconds(5);

    private static Page load(String html) {
        return assertTimeoutPreemptively(QUICKLY, () -> {
            Page page = new TestHost().recordErrors().load(html);
            for (int f = 1; f <= 3; f++) page.frame(f * 16);
            page.paint();
            return page;
        });
    }

    private static int depth(Node n) {
        int max = 0;
        for (Node c : n.childNodes()) max = Math.max(max, depth(c));
        return max + 1;
    }

    /** 40,000 nested divs (200 KB, the inline page limit) used to overflow the stack after 27 s of parsing. */
    @Test
    void deepMarkupIsFlattened() {
        Page page = load("<div>".repeat(40_000) + "x");
        assertNull(page.doc.error(), page.errors());
        assertTrue(depth(page.doc) <= Limits.DEFAULTS.maxDepth() + 1, "depth " + depth(page.doc));
        assertTrue(page.doc.body().textContent().contains("x"));
    }

    @Test
    void deepTreesFromScriptsAreRefused() {
        Page page = load("<script>var e = document.body; for (var i = 0; i < 100000; i++) e = e.appendChild(document.createElement('div'))</script>");
        assertTrue(page.errors().contains("nested at most 512 deep"), page.errors());
        assertNull(page.doc.error());
        assertTrue(depth(page.doc) <= Limits.DEFAULTS.maxDepth() + 1);
    }

    @Test
    void deepDetachedTreesAreRefusedToo() {
        Page page = load("<script>var e = document.createElement('div'); for (var i = 0; i < 100000; i++) e = e.appendChild(document.createElement('div'))</script>");
        assertTrue(page.errors().contains("nested at most 512 deep"), page.errors());
    }

    @Test
    void nodeCountIsCapped() {
        TestHost host = new TestHost().recordErrors().limits(Limits.DEFAULTS.with("maxNodes", 1000));
        Page page = host.load("<script>for (var i = 0; i < 100000; i++) document.body.appendChild(document.createElement('i'))</script>");
        assertTrue(page.errors().contains("at most 1000 nodes"), page.errors());
        assertTrue(page.doc.nodeCount() <= 1000);
        page.run("while (document.body.firstChild) document.body.firstChild.remove(); for (var i = 0; i < 500; i++) document.body.appendChild(document.createElement('i'))");
        assertEquals(500, page.doc.body().childNodes().size(), "removed nodes free their place");
    }

    @Test
    void templatesCannotMultiplyPastTheNodeCap() {
        Page page = load("<script>vellum.state({n: 10000})</script><div v-for='i in n'><b v-for='j in n'>x</b></div>");
        assertTrue(page.errors().contains("at most 100000 nodes"), page.errors());
        assertTrue(page.doc.nodeCount() <= Limits.DEFAULTS.maxNodes());
    }

    /** Text broken by many comments used to be joined one piece at a time: quadratic in the markup's length. */
    @Test
    void parsingTextIsLinear() {
        Page page = load("");
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> page.run("document.body.innerHTML = 'a<!---->'.repeat(130000)"));
        assertEquals(130_000, page.doc.body().textContent().length());
    }

    @ParameterizedTest
    @ValueSource(strings = {"calc", "is", "has", "not", "block"})
    void deeplyNestedCssIsHarmless(String kind) {
        int n = 20_000;
        String css = switch (kind) {
            case "calc" -> "div { width: " + "calc(1px + ".repeat(n) + "1px" + ")".repeat(n) + " }";
            case "is" -> ":is(".repeat(n) + "div" + ")".repeat(n) + " { color: red }";
            case "has" -> ":has(".repeat(n) + "div" + ")".repeat(n) + " { color: red }";
            case "not" -> ":not(".repeat(n) + "p" + ")".repeat(n) + " { color: red }";
            default -> "div { " + "{".repeat(n) + "}".repeat(n) + " color: red }";
        };
        Page page = load("<style>" + css + "</style><div>x</div>");
        assertNull(page.doc.error(), page.errors());
    }

    @Test
    void hasCannotBeNested() {
        Page page = load("<div><p><a></a></p></div>");
        assertEquals("SyntaxError", page.eval("(function () { try { document.querySelector('div:has(p:has(a))') } catch (e) { return e.name } })()"));
    }

    @Test
    void selectorsHaveAPartLimit() {
        Page page = load("<div class=a></div>");
        String list = String.join(", ", Collections.nCopies(300, ".a"));
        assertEquals("SyntaxError", page.eval("(function () { try { document.querySelector(':not(" + list + ")') } catch (e) { return e.name } })()"));
    }

    /** Descendant combinators used to backtrack through every combination of ancestors. */
    @Test
    void descendantSelectorsDoNotBacktrackExponentially() {
        String css = "<style>" + "div ".repeat(40) + "x { color: red }</style>";
        Page page = load(css + "<div>".repeat(300) + "<x>a</x>");
        assertEquals("rgb(255, 0, 0)", page.computed("x", "color"));
        Page miss = load(css.replace("x {", "y {") + "<div>".repeat(300) + "<x>a</x>");
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> miss.eval("document.querySelectorAll('" + "div ".repeat(40) + "y').length"));
    }

    @Test
    void varSubstitutionCannotExplode() {
        StringBuilder vars = new StringBuilder("--a0: x;");
        for (int i = 1; i <= 40; i++) vars.append("--a").append(i).append(": var(--a").append(i - 1).append(") var(--a").append(i - 1).append(");");
        Page page = load("<style>:root {" + vars + "} div { width: 10px; width: var(--a40) }</style><div>x</div>");
        assertNull(page.doc.error(), page.errors());
    }

    @Test
    void hugeGridRepeatsAreInvalid() {
        Page page = load("<div style='display: grid; grid-template-columns: repeat(100000000, 1px); grid-template-rows: repeat(100000000, 1px)'><i></i></div>");
        assertNull(page.doc.error(), page.errors());
        assertEquals("none", page.computed("div", "grid-template-columns"));
    }

    @Test
    void longShadowListsAreInvalid() {
        String shadows = String.join(", ", Collections.nCopies(5000, "0 0 100px 100px red"));
        Page page = load("<style>div { box-shadow: " + shadows + " }</style>" + "<div>a</div>".repeat(100));
        assertNull(page.doc.error(), page.errors());
        assertEquals("none", page.computed("div", "box-shadow"));
    }

    @Test
    void hugeLengthsAreHarmless() {
        Page page = load("<div style='font-size: 1e9px; width: 1e30px; height: 1e30px; border: 1e30px solid red;"
                + " border-radius: 1e30px; box-shadow: 0 0 1e30px 1e30px red; background: linear-gradient(red, blue)'>Hello</div>");
        assertNull(page.doc.error(), page.errors());
        Element div = page.query("div");
        assertTrue(div.getBoundingClientRect()[2] > 0);
    }
}
