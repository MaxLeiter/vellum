package dev.vellum.engine.html;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Node;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class HtmlParserTest {
    private static Document parse(String html) {
        Document doc = Document.create(new TestHost(), "test:x.html");
        doc.removeAllChildren();
        HtmlParser.parseInto(doc, html);
        return doc;
    }

    @Test
    void impliesStructure() {
        Document doc = parse("<p>Hello <b>world</b>");
        assertEquals("<html><head></head><body><p>Hello <b>world</b></p></body></html>",
                HtmlSerializer.outerHTML(doc.documentElement()));
    }

    @Test
    void headElementsGoToHead() {
        Document doc = parse("<!doctype html><title>T</title><style>a { color: red }</style><div id=x>hi</div>");
        assertEquals("<title>T</title><style>a { color: red }</style>", HtmlSerializer.innerHTML(doc.head()));
        assertEquals("<div id=\"x\">hi</div>", HtmlSerializer.innerHTML(doc.body()));
    }

    @Test
    void attributesAndEntities() {
        Document doc = parse("<input type=checkbox checked value='a &amp; b' data-x=\"1&lt;2\"><span>&copy; &#65;&#x42; &nope;</span>");
        Element input = doc.body().children().get(0);
        assertEquals("checkbox", input.getAttribute("type"));
        assertEquals("", input.getAttribute("checked"));
        assertEquals("a & b", input.getAttribute("value"));
        assertEquals("1<2", input.getAttribute("data-x"));
        assertEquals("© AB &nope;", doc.body().children().get(1).textContent());
    }

    @Test
    void impliedEndTags() {
        Document doc = parse("<ul><li>one<li>two</ul><p>a<p>b<div>c</div>");
        assertEquals("<ul><li>one</li><li>two</li></ul><p>a</p><p>b</p><div>c</div>", HtmlSerializer.innerHTML(doc.body()));
    }

    @Test
    void selfClosingAndVoid() {
        Document doc = parse("<div><slot index=\"0\"/><slot index=\"1\"/><br>x<img src=a.png>y</div>");
        assertEquals("<div><slot index=\"0\"></slot><slot index=\"1\"></slot><br>x<img src=\"a.png\">y</div>",
                HtmlSerializer.innerHTML(doc.body()));
    }

    @Test
    void rawText() {
        Document doc = parse("<script>if (a < b && c) { x = '</div>'; }</script><textarea>\n&lt;hi&gt;</textarea>");
        Element script = doc.head().children().get(0);
        assertEquals("if (a < b && c) { x = '</div>'; }", script.textContent());
        assertEquals("<hi>", doc.body().children().get(0).textContent());
    }

    @Test
    void fragments() {
        Document doc = parse("<div id=a></div>");
        Element a = doc.getElementById("a");
        assertNotNull(a);
        a.setInnerHTML("<b>x</b> y <!-- gone --><i>z");
        assertEquals("<b>x</b> y <i>z</i>", a.innerHTML());
        List<Node> nodes = HtmlParser.parseFragment(doc, "text");
        assertEquals(1, nodes.size());
    }

    @Test
    void strayEndTagsAndComments() {
        Document doc = parse("<div>a</span>b<!-- c -->d</div></div>e");
        assertEquals("<div>abd</div>e", HtmlSerializer.innerHTML(doc.body()));
    }
}
