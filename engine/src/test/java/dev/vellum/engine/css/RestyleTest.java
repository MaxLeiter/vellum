package dev.vellum.engine.css;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Text;
import dev.vellum.engine.style.Length;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Restyles match selectors again only after a change selectors or stylesheet collection can see
 * ({@link dev.vellum.engine.dom.Document#selectorVersion}); everything else keeps the previous matches.
 */
class RestyleTest {
    private static final int RED = 0xFFFF0000, BLUE = 0xFF0000FF, GREEN = 0xFF008000, WHITE = 0xFFFFFFFF;

    private static int rematched(Page page) {
        return page.doc.styleEngine().rematched;
    }

    private static int elements(Page page) {
        return page.doc.querySelectorAll("*").size();
    }

    @Test
    void attributesSelectorsReadRestyle() {
        Page page = new TestHost().load("""
                <style>[data-state=open] { color: red }</style><div id=a data-state=closed>a</div><p>b</p>""");
        Element a = page.query("#a");
        assertEquals(WHITE, page.style("#a").color);
        a.setAttribute("data-state", "open");
        page.frame();
        assertEquals(RED, page.style("#a").color);
        assertEquals(elements(page), rematched(page), "an attribute a selector reads matches everything again");
    }

    @Test
    void attributesNoSelectorReadsDoNotRematch() {
        Page page = new TestHost().load("""
                <style>.a { color: red } .h:hover { color: blue }</style>
                <div id=x class=a>x</div><div class=h>h</div><p>p</p><p>q</p>""");
        Element x = page.query("#x"), h = page.query(".h");
        x.setAttribute("data-frame", "1");
        x.setAttribute("title", "t");
        page.doc.setHovered(h, true);
        page.frame();
        assertEquals(BLUE, page.style(".h").color);
        assertEquals(1, rematched(page), "only the hovered element, whose matching read hover");

        x.setAttribute("style", "padding: 3px");
        page.frame();
        assertEquals(Length.px(3), page.style("#x").paddingTop);
        assertEquals(RED, page.style("#x").color, "the matched rules still apply");
        assertEquals(2, rematched(page), "the element whose inline style changed, and the hovered one");
    }

    @Test
    void textTogglingEmptinessRestyles() {
        Page page = new TestHost().load("""
                <style>p:empty { color: red } div:has(> p:empty) { color: blue }
                textarea:placeholder-shown { color: green }</style>
                <div><p id=p></p></div><textarea placeholder=type></textarea>""");
        Text text = page.doc.createTextNode("");
        page.query("#p").appendChild(text);
        Text value = page.doc.createTextNode("");
        page.query("textarea").appendChild(value);
        page.frame();
        assertEquals(RED, page.style("#p").color, "an empty text node leaves the element empty");
        assertEquals(BLUE, page.style("div").color);
        assertEquals(GREEN, page.style("textarea").color);

        text.setData("x");
        value.setData("x");
        page.frame();
        assertEquals(WHITE, page.style("#p").color);
        assertEquals(WHITE, page.style("div").color);
        assertNotEquals(GREEN, page.style("textarea").color, "the placeholder no longer shows");

        text.setData("");
        page.frame();
        assertEquals(RED, page.style("#p").color);

        text.setData("y");
        page.frame();
        int before = rematched(page);
        text.setData("z");
        page.doc.setHovered(page.query("div"), true);
        page.frame();
        assertTrue(rematched(page) < before, "text that stays non-empty does not match everything again");
    }

    @Test
    void styleTextEditsRestyle() {
        Page page = new TestHost().load("<style id=s>#a { color: red }</style><div id=a></div><b>b</b>");
        Element s = page.query("#s");
        ((Text) s.childAt(0)).setData("#a { color: blue }");
        page.frame();
        assertEquals(BLUE, page.style("#a").color);

        // Text below an element in a <style> is the sheet's too.
        Element span = s.appendChild(page.doc.createElement("span"));
        Text nested = span.appendChild(page.doc.createTextNode("/* */"));
        page.frame();
        nested.setData("#a { color: green }");
        page.frame();
        assertEquals(GREEN, page.style("#a").color);
    }

    @Test
    void linkChangesRestyle() {
        TestHost host = new TestHost()
                .resource("test:a.css", "#a { color: red }")
                .resource("test:b.css", "#a { color: blue }");
        Page page = host.load("<link id=l rel=stylesheet href=a.css><div id=a></div>");
        Element link = page.query("#l");
        assertEquals(RED, page.style("#a").color);
        link.setAttribute("href", "b.css");
        page.frame();
        assertEquals(BLUE, page.style("#a").color);

        link.setAttribute("media", "(min-width: 10000px)");
        page.frame();
        assertEquals(WHITE, page.style("#a").color);

        link.removeAttribute("media");
        link.setAttribute("rel", "preload");
        page.frame();
        assertEquals(WHITE, page.style("#a").color);

        link.setAttribute("rel", "stylesheet");
        page.frame();
        assertEquals(BLUE, page.style("#a").color);
    }

    @Test
    void classAndIdChangesRestyle() {
        Page page = new TestHost().load("""
                <style>.on { color: red } #on { color: blue }</style><div id=a>a</div><div id=b>b</div>""");
        page.query("#a").setAttribute("class", "on");
        page.frame();
        assertEquals(RED, page.style("#a").color);
        page.query("#b").setAttribute("id", "on");
        page.frame();
        assertEquals(BLUE, page.style("#on").color);
        page.query("#a").removeAttribute("class");
        page.frame();
        assertEquals(WHITE, page.style("#a").color);
    }

    @Test
    void formStateAndTreeChangesRestyle() {
        Page page = new TestHost().load("""
                <style>input:checked { color: red } fieldset input:disabled { color: blue }
                li:first-child { color: green } li:last-child { color: red }</style>
                <input id=c type=checkbox><fieldset><input id=d></fieldset><ul><li id=one>1</li><li id=two>2</li></ul>""");
        page.query("#c").setChecked(true);
        page.frame();
        assertEquals(RED, page.style("#c").color);
        page.query("fieldset").setAttribute("disabled", "");
        page.frame();
        assertEquals(BLUE, page.style("#d").color);
        Element ul = page.query("ul");
        ul.insertBefore(page.doc.createElement("li"), ul.firstChild());
        ul.appendChild(page.doc.createElement("li"));
        page.frame();
        assertEquals(WHITE, page.style("#one").color);
        assertEquals(WHITE, page.style("#two").color);
    }

    @Test
    void hoverWithATextChangeKeepsTheFastPath() {
        Page page = new TestHost().load("""
                <style>.h:hover { color: red }</style><div class=h>h</div><p id=t>count 0</p><p>a</p><p>b</p>""");
        Element h = page.query(".h");
        Text count = (Text) page.query("#t").childAt(0);
        page.doc.setHovered(h, true);
        count.setData("count 1");
        page.frame();
        assertEquals(RED, page.style(".h").color);
        assertEquals(1, rematched(page), "only the hovered element matches again");
        page.doc.setHovered(h, false);
        count.setData("count 2");
        page.frame();
        assertEquals(WHITE, page.style(".h").color);
        assertEquals(1, rematched(page));
    }
}
