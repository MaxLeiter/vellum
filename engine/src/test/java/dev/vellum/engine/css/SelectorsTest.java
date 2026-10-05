package dev.vellum.engine.css;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectorsTest {
    private static final String PAGE = """
            <div id=root class="box main" data-kind="big panel" lang="en-US">
              <p class=first>one</p>
              <p>two <b>bold</b></p>
              <span title="Hello World">three</span>
              <ul><li>a</li><li class=x>b</li><li>c</li><li class=x>d</li><li>e</li></ul>
              <section><article><em id=deep>deep</em></article></section>
              <input id=cb type=checkbox checked><input id=txt placeholder="name"><button id=btn disabled>b</button>
              <div id=empty></div><div id=ws> </div>
              <details id=det open><summary>s</summary></details>
            </div>
            """;

    private static Document doc() {
        return Document.parse(new TestHost(), "test:page.html", PAGE);
    }

    private static List<String> ids(List<Element> elements) {
        return elements.stream().map(e -> e.id().isEmpty() ? e.tagName() + "." + e.textContent() : e.id()).toList();
    }

    private static int specificity(String selector) {
        return SelectorParser.parse(selector).get(0).specificity;
    }

    private static int spec(int a, int b, int c) {
        return a * Selector.ID + b * Selector.CLASS + c * Selector.TYPE;
    }

    @Test
    void typeIdClassAttribute() {
        Document d = doc();
        assertEquals(List.of("root"), ids(d.querySelectorAll("#root.box.main")));
        assertEquals(3, d.querySelectorAll("p, span").size());
        assertEquals(List.of("root"), ids(d.querySelectorAll("[data-kind~=panel]")));
        assertEquals(List.of("root"), ids(d.querySelectorAll("[lang|=en]")));
        assertEquals(1, d.querySelectorAll("[title^=Hello][title$=World][title*='o W']").size());
        assertEquals(1, d.querySelectorAll("[title='hello world' i]").size());
        assertEquals(0, d.querySelectorAll("[title='hello world']").size());
        assertEquals(0, d.querySelectorAll("[title^='']").size());
        assertEquals(List.of("root"), ids(d.querySelectorAll("[data-kind~=PANEL i][lang|=EN i]")));
        assertEquals(0, d.querySelectorAll("[data-kind~=pan], [data-kind~='big panel'], [lang|=en-u]").size());
        assertEquals(1, d.querySelectorAll("[title^=hello i][title$=WORLD i][title*='O w' i]").size());
        assertEquals(2, d.querySelectorAll("[id=cb], INPUT[ID=txt]").size());
    }

    @Test
    void combinators() {
        Document d = doc();
        assertEquals(List.of("deep"), ids(d.querySelectorAll("section em")));
        assertEquals(List.of(), ids(d.querySelectorAll("section > em")));
        assertEquals(List.of("deep"), ids(d.querySelectorAll("div > section > article > em")));
        assertEquals(List.of("p.two bold"), ids(d.querySelectorAll(".first + p")));
        assertEquals(List.of("li.c", "li.e"), ids(d.querySelectorAll("li.x + li:not(.x)")));
        assertEquals(List.of("li.b", "li.c", "li.d", "li.e"), ids(d.querySelectorAll("li:first-child ~ li")));
    }

    @Test
    void structuralPseudoClasses() {
        Document d = doc();
        assertEquals(List.of("li.a", "li.c", "li.e"), ids(d.querySelectorAll("li:nth-child(odd)")));
        assertEquals(List.of("li.b", "li.d"), ids(d.querySelectorAll("li:nth-child(2n)")));
        assertEquals(List.of("li.c", "li.d", "li.e"), ids(d.querySelectorAll("li:nth-child(n+3)")));
        assertEquals(List.of("li.a", "li.b"), ids(d.querySelectorAll("li:nth-child(-n + 2)")));
        assertEquals(List.of("li.d"), ids(d.querySelectorAll("li:nth-last-child(2)")));
        assertEquals(List.of("li.d"), ids(d.querySelectorAll("li:nth-child(2 of .x)")));
        assertEquals(List.of("li.b"), ids(d.querySelectorAll("li:nth-last-child(2 of .x)")));
        assertEquals(List.of("p.one"), ids(d.querySelectorAll("p:first-of-type")));
        assertEquals(List.of("p.two bold"), ids(d.querySelectorAll("p:nth-of-type(2)")));
        assertEquals(List.of("span.three"), ids(d.querySelectorAll("#root > :only-of-type:not(ul, section, details, button)")));
        assertEquals(List.of("li.a"), ids(d.querySelectorAll("li:first-child")));
        assertEquals(List.of("li.e"), ids(d.querySelectorAll("li:last-child")));
        assertEquals(List.of("html", "div", "b", "article", "em", "summary"),
                d.querySelectorAll(":only-child").stream().map(Element::tagName).toList());
        assertEquals(List.of("empty"), ids(d.querySelectorAll("div:empty")));
        assertEquals("html", d.querySelector(":root").tagName());
    }

    @Test
    void logicalPseudoClasses() {
        Document d = doc();
        assertEquals(2, d.querySelectorAll(":is(p, span).first, :where(span)").size());
        assertEquals(List.of("li.a", "li.c", "li.e"), ids(d.querySelectorAll("li:not(.x)")));
        assertEquals(List.of("root"), ids(d.querySelectorAll("div:has(> p.first)")));
        assertEquals(List.of("root"), ids(d.querySelectorAll("div:has(em)")));
        assertEquals(List.of(), ids(d.querySelectorAll("div:has(> em)")));
        assertEquals(List.of("p.one"), ids(d.querySelectorAll("p:has(+ p)")));
        assertEquals(List.of("li.a", "li.b", "li.c"), ids(d.querySelectorAll("li:has(~ .x)")));
        // :is() is forgiving: an invalid argument is dropped, the rest still matches.
        assertEquals(1, d.querySelectorAll(":is(::nope, #deep)").size());
    }

    @Test
    void statePseudoClasses() {
        Document d = doc();
        Element cb = d.getElementById("cb"), txt = d.getElementById("txt"), btn = d.getElementById("btn");
        assertTrue(cb.matches(":checked:enabled"));
        cb.setChecked(false);
        assertFalse(cb.matches(":checked"));
        assertTrue(btn.matches(":disabled"));
        assertFalse(d.getElementById("root").matches(":disabled, :enabled"));
        assertTrue(txt.matches(":placeholder-shown"));
        txt.setValue("x");
        assertFalse(txt.matches(":placeholder-shown"));
        assertTrue(d.getElementById("det").matches(":open"));
        d.setHovered(cb, true);
        assertTrue(cb.matches(":hover"));
        d.setActive(cb, true);
        assertTrue(cb.matches(":active"));
        txt.focus();
        assertTrue(txt.matches(":focus"));
        assertTrue(txt.matches(":focus-visible"), "text fields show focus for pointer focus too");
        assertTrue(d.getElementById("root").matches(":focus-within"));
        cb.focus();
        assertFalse(cb.matches(":focus-visible"), "checkboxes need keyboard focus");
        assertTrue(d.querySelector("a, p").matches(":not(:focus-within)"));
        assertTrue(d.getElementById("root").matches(":scope"));
    }

    @Test
    void specificity() {
        assertEquals(spec(0, 0, 1), specificity("div"));
        assertEquals(spec(1, 1, 1), specificity("div#a.b"));
        assertEquals(spec(0, 2, 1), specificity("a[href]:hover"));
        assertEquals(spec(0, 0, 2), specificity("p::before"));
        assertEquals(spec(1, 0, 0), specificity(":is(#a, .b, c)"));
        assertEquals(spec(0, 1, 0), specificity(":not(.a, b)"));
        assertEquals(spec(0, 0, 0), specificity(":where(#a, .b)"));
        assertEquals(spec(0, 1, 0), specificity(":has(> .a, b)"));
        assertEquals(spec(1, 1, 0), specificity(":nth-child(2n of #a, .b)"));
        assertEquals(spec(0, 0, 0), specificity("*"));
    }

    @Test
    void pseudoElements() {
        List<Selector> s = SelectorParser.parse("p::before, a:after, input::placeholder");
        assertEquals(Selector.PseudoElement.BEFORE, s.get(0).pseudoElement);
        assertEquals(Selector.PseudoElement.AFTER, s.get(1).pseudoElement);
        assertEquals(Selector.PseudoElement.PLACEHOLDER, s.get(2).pseudoElement);
        // Pseudo-element selectors never match elements themselves.
        assertNull(doc().querySelector("p::before"));
    }

    @Test
    void invalidSelectorsThrow() {
        Document d = doc();
        for (String bad : List.of("", "a,", "> a", "a >", "#1", ".", "[a=]", "[a b]", ":nope", "::nope", "a::before b",
                ":nth-child(x)", "ns|a", "a::before:hover", ":not()", "a !b")) {
            assertThrows(IllegalArgumentException.class, () -> d.querySelector(bad), bad);
        }
    }

    @Test
    void domApiScopesAndOrder() {
        Document d = doc();
        Element root = d.getElementById("root");
        assertEquals("p", root.querySelector("p").tagName());
        assertNull(root.querySelector("#root"), "scope itself is excluded");
        assertEquals(List.of("deep"), ids(root.querySelectorAll(":scope > section em")));
        assertEquals(root, d.getElementById("deep").closest(".box"));
        assertTrue(d.getElementById("deep").matches("div em"));
        assertEquals("ul", root.querySelectorAll("ul, li").get(0).tagName(), "document order");
    }
}
