package dev.vellum.engine.script;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.html.HtmlSerializer;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DomBindingsTest {
    @Test
    void createAppendInsertRemove() {
        Page page = new TestHost().load("""
                <ul id=list><li>b</li></ul>
                <script>
const list = document.getElementById('list');
                const a = document.createElement('li');
                a.textContent = 'a';
                list.insertBefore(a, list.firstChild);
                list.appendChild(document.createElement('li')).append('c', document.createTextNode('!'));
                list.removeChild(list.children[1]);
                </script>""");
        assertEquals("<li>a</li><li>c!</li>", page.byId("list").innerHTML());
        assertEquals("2 LI li 1 true", page.eval("[list.childElementCount, list.lastChild.tagName, list.lastChild.localName, "
                + "list.nodeType, list.firstChild.nextSibling === list.lastElementChild].join(' ')"));
    }

    @Test
    void wrappersAreUniqueAndTyped() {
        Page page = new TestHost().load("<p id=p>hi</p>");
        assertEquals("true true true false true", page.eval("[document.getElementById('p') === document.body.firstElementChild, "
                + "document.body.firstChild instanceof Element, document.body.firstChild instanceof Node, "
                + "document.body.firstChild instanceof Text, document.body.firstChild.firstChild instanceof Text].join(' ')"));
        assertEquals("TypeError: Illegal constructor", page.eval("(function () { try { new Element() } catch (e) { return e } })()"));
        assertEquals("[object Element]", page.eval("Object.prototype.toString.call(document.body)"));
    }

    @Test
    void collectionsAreArraySnapshots() {
        Page page = new TestHost().load("<div id=d><i class='x y'></i><b class=y></b> text</div><script>const d = document.getElementById('d');</script>");
        assertEquals("true 3 2 1 2 1", page.eval("[Array.isArray(d.childNodes), d.childNodes.length, d.children.length, "
                + "d.getElementsByTagName('i').length, document.getElementsByClassName('y').length, "
                + "document.getElementsByClassName('x y').length].join(' ')"));
        assertEquals("3", page.eval("(function () { const nodes = d.childNodes; d.textContent = ''; return nodes.length })()"));
    }

    @Test
    void attributes() {
        Page page = new TestHost().load("""
                <div id=d data-x=1></div>
                <script>
const d = document.getElementById('d');
                d.setAttribute('title', 'hello');
                d.removeAttribute('data-x');
                d.toggleAttribute('hidden');
                </script>""");
        Element d = page.byId("d");
        assertEquals("hello", d.getAttribute("title"));
        assertEquals("id,title,hidden", page.eval("d.getAttributeNames().join()"));
        assertEquals("true false true hello", page.eval("[d.hasAttribute('hidden'), d.toggleAttribute('hidden'), "
                + "d.toggleAttribute('open', true), d.title].join(' ')"));
        assertEquals("id=d", page.eval("d.attributes.map(a => a.name + '=' + a.value)[0]"));
    }

    @Test
    void classList() {
        Page page = new TestHost().load("""
                <div id=d class='a b'></div>
                <script>
const d = document.getElementById('d');
                const seen = [];
                d.classList.add('c', 'd');
                d.classList.remove('a');
                d.classList.toggle('b');
                d.classList.toggle('e', true);
                d.classList.toggle('c', true);
                d.classList.replace('d', 'z');
                d.classList.forEach((c, i) => seen.push(i + c));
                </script>""");
        assertEquals("c z e", page.byId("d").getAttribute("class"));
        assertEquals("3 true false z c z e 0c,1z,2e true", page.eval("[d.classList.length, d.classList.contains('e'), "
                + "d.classList.contains('a'), d.classList.item(1), d.classList.value, seen.join(), d.classList === d.classList].join(' ')"));
        page.run("d.className = 'q'");
        assertEquals("q", page.byId("d").getAttribute("class"));
    }

    @Test
    void dataset() {
        Page page = new TestHost().load("""
                <div id=d data-item-id=7></div>
                <script>
const d = document.getElementById('d');
                d.dataset.slotCount = 3;
                delete d.dataset.itemId;
                </script>""");
        Element d = page.byId("d");
        assertEquals("3", d.getAttribute("data-slot-count"));
        assertEquals(null, d.getAttribute("data-item-id"));
        assertEquals("slotCount undefined true", page.eval("[Object.keys(d.dataset).join(), typeof d.dataset.itemId, 'slotCount' in d.dataset].join(' ')"));
    }

    @Test
    void textAndMarkup() {
        Page page = new TestHost().load("""
                <div id=d></div><p id=p>x</p>
                <script>
const d = document.getElementById('d');
                d.innerHTML = '<b>bold</b> <i>it</i>';
                d.insertAdjacentHTML('beforeend', '<u>u</u>');
                document.getElementById('p').outerHTML = '<span id=s>s</span>';
                </script>""");
        assertEquals("<b>bold</b> <i>it</i><u>u</u>", page.byId("d").innerHTML());
        assertEquals("bold itu", page.eval("d.textContent"));
        assertEquals("<span id=\"s\">s</span>", page.eval("document.getElementById('s').outerHTML"));
        assertEquals("SyntaxError", page.eval("(function () { try { d.insertAdjacentHTML('nowhere', 'x') } catch (e) { return e.name } })()"));
        page.run("d.textContent = 'plain'");
        assertEquals("plain", page.byId("d").innerHTML());
    }

    @Test
    void cloneNode() {
        Page page = new TestHost().load("""
                <div id=d class=k><b>x</b></div>
                <script>
const d = document.getElementById('d');
                const shallow = d.cloneNode();
                const deep = d.cloneNode(true);
                document.body.append(deep);
                </script>""");
        assertEquals("0 1 k false", page.eval("[shallow.childNodes.length, deep.childNodes.length, deep.className, deep === d].join(' ')"));
        assertEquals(2, page.doc.body().getElementsByTagName("b").size());
    }

    @Test
    void childNodeMethodsAcceptStrings() {
        Page page = new TestHost().load("""
                <div id=d><b id=b></b></div>
                <script>
const b = document.getElementById('b');
                b.before('1');
                b.after('2', document.createElement('i'));
                b.prepend('in');
                document.getElementById('d').prepend('0');
                </script>""");
        assertEquals("01<b id=\"b\">in</b>2<i></i>", page.byId("d").innerHTML());
        page.run("b.replaceWith('gone')");
        assertEquals("01gone2<i></i>", page.byId("d").innerHTML());
    }

    @Test
    void formState() {
        Page page = new TestHost().load("""
                <input id=t value=start><input id=c type=checkbox>
                <select id=s><option>a</option><option value=bv selected>b</option><option>c</option></select>
                <script>const t = document.getElementById('t'), c = document.getElementById('c'), s = document.getElementById('s');</script>""");
        assertEquals("start text checkbox false on", page.eval("[t.value, t.type, c.type, c.checked, c.value].join(' ')"));
        page.run("t.value = 'typed'; c.checked = true");
        assertEquals("typed", page.byId("t").value());
        assertEquals("start", page.byId("t").getAttribute("value"));
        assertEquals(true, page.byId("c").checked());

        assertEquals("1 bv 3 true", page.eval("[s.selectedIndex, s.value, s.options.length, s.options[1].selected].join(' ')"));
        page.run("s.value = 'c'");
        assertEquals("2 c", page.eval("[s.selectedIndex, s.value].join(' ')"));
        page.run("s.selectedIndex = 0");
        assertEquals("a", page.eval("s.value"));
        page.run("s.options[1].selected = true");
        assertEquals("bv", page.eval("s.value"));
    }

    @Test
    void documentAndWindow() {
        Page page = new TestHost().load("<title> My  UI </title><body><button id=b></button></body>");
        assertEquals("My UI BODY HTML HEAD true", page.eval("[document.title, document.activeElement.tagName, "
                + "document.documentElement.tagName, document.head.tagName, window === self].join(' ')"));
        page.run("document.title = 'Other'; document.getElementById('b').focus()");
        assertEquals("<title>Other</title>", HtmlSerializer.innerHTML(page.doc.head()));
        assertEquals("BUTTON", page.eval("document.activeElement.tagName"));
        page.doc.setViewport(400, 300, 3);
        assertEquals("400 300 3 test:page.html complete", page.eval("[innerWidth, innerHeight, devicePixelRatio, "
                + "location.href, document.readyState].join(' ')"));
        page.run("location.href = 'other.html'; vellum.open('third.html')");
        assertEquals("[test:other.html, test:third.html]", page.host.navigations.toString());
    }

    @Test
    void scriptsSeeTheViewportFromTheStart() {
        // The host passes the viewport when it creates the document: scripts read it while the page loads.
        Page page = new TestHost().load("<script>console.log([innerWidth, innerHeight, devicePixelRatio].join(' '))</script>",
                200, 100);
        assertEquals(List.of("INFO: 200 100 2"), page.host.logs);
    }

    @Test
    void styleNamesConvertToCss() {
        assertEquals("background-color", StyleBindings.cssName("backgroundColor"));
        assertEquals("-webkit-transform", StyleBindings.cssName("webkitTransform"));
        assertEquals("-webkit-transform", StyleBindings.cssName("WebkitTransform"));
        assertEquals("-mc-tint", StyleBindings.cssName("mcTint"));
        assertEquals("float", StyleBindings.cssName("cssFloat"));
        assertEquals("--accentColor", StyleBindings.cssName("--accentColor"));
        assertEquals("margin-top", StyleBindings.cssName("margin-top"));
        assertEquals("slotCount", StyleBindings.camelCase("slot-count"));
    }

    @Test
    void styleObjectIsStableAndRoutesNamedProperties() {
        Page page = new TestHost().load("<div id=d style='color: blue'></div><script>const d = document.getElementById('d');</script>");
        assertEquals("true true string function", page.eval("[d.style === d.style, d.style instanceof CSSStyleDeclaration, "
                + "typeof d.style.backgroundColor, typeof d.style.setProperty].join(' ')"));
        page.run("d.style.backgroundColor = 'red'; d.style.setProperty('--x', '1'); d.style.removeProperty('color')");
        assertEquals("background-color: red; --x: 1;", page.byId("d").getAttribute("style"), "written back to the attribute");
        assertEquals("red 2", page.eval("d.style.backgroundColor + ' ' + d.style.length"));
        assertEquals("rgb(255, 0, 0) rgb(255, 255, 255) 1", page.eval("[getComputedStyle(d).backgroundColor, "
                + "getComputedStyle(d).color, getComputedStyle(d).getPropertyValue('--x')].join(' ')"),
                "computed at once, without waiting for a frame; color falls back to the inherited white");
        page.frame();
        assertEquals(0xFFFF0000, page.byId("d").style.backgroundColor);
    }

    @Test
    void geometryAndScrollingGoThroughTheElement() {
        Page page = new TestHost().load("""
                <div id=s style="overflow: auto; height: 50px; scroll-behavior: auto">
                  <div style="height: 200px; padding-top: 120px"><div id=low style="height: 20px"></div></div>
                </div>
                <script>const s = document.getElementById('s'), low = document.getElementById('low');</script>""");
        page.run("s.scrollTop = 30");
        assertEquals("30 90", page.eval("[s.scrollTop, low.getBoundingClientRect().top].join(' ')"));
        page.run("low.scrollIntoView()");
        assertEquals("120 0", page.eval("[s.scrollTop, low.getBoundingClientRect().top].join(' ')"));
        page.run("low.scrollIntoView({block: 'nearest'}); s.scrollBy({top: -20})");
        assertEquals("100", page.eval("s.scrollTop"));
        page.run("s.scrollTo({top: 0, behavior: 'smooth'})");
        assertEquals("100", page.eval("s.scrollTop"), "smooth: eased over the next frames");
        for (int t = 16; t < 600; t += 16) page.frame(t);
        assertEquals("0", page.eval("s.scrollTop"));
    }
}
