package dev.vellum.engine.script;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.event.Event;
import dev.vellum.engine.event.InputEvent;
import dev.vellum.engine.event.KeyboardEvent;
import dev.vellum.engine.event.Modifiers;
import dev.vellum.engine.html.HtmlSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplatesTest {
    private static String body(Page page) {
        return HtmlSerializer.innerHTML(page.doc.body()).replaceAll("<script>.*?</script>", "");
    }

    private static void type(Element input, String text) {
        input.setValue(text);
        input.dispatchEvent(new InputEvent("input", text, "insertText"));
    }

    @Test
    void bindsWithoutAnyScript() {
        Page page = new Page("<p title='{{ 1 + 1 }}'>{{ 'a' + 'b' }} and {{ [1, 2] }}{{ null }}</p>");
        assertEquals("<p title=\"2\">ab and [1,2]</p>", body(page));
    }

    @Test
    void interpolationFollowsData() {
        Page page = new Page("<p id=p>Hello {{ name }}, {{ stats.hp }} HP</p>", false); // undefined until data arrives
        page.doc.receive("data", "{\"name\": \"Steve\", \"stats\": {\"hp\": 20}}");
        assertEquals("Hello Steve, 20 HP", page.byId("p").textContent());
        page.doc.receive("data", "{\"name\": \"Alex\", \"stats\": {\"hp\": 7}}");
        assertEquals("Hello Alex, 7 HP", page.byId("p").textContent());
    }

    @Test
    void stateAndHandlers() {
        Page page = new Page("""
                <button id=b @click="count++">{{ count }} / {{ doubled() }}</button>
                <script>
                const state = vellum.state({count: 1});
                function doubled() { return state.count * 2 }
                </script>""");
        assertEquals("1 / 2", page.byId("b").textContent());
        page.byId("b").click();
        page.byId("b").click();
        assertEquals("3 / 6", page.byId("b").textContent());
        page.run("state.count = 10");
        assertEquals("10 / 20", page.byId("b").textContent(), "any entry re-renders");
    }

    @Test
    void ifElseChains() {
        Page page = new Page("""
                <div id=d>
                  <p v-if="n === 0">zero</p>
                  <p v-else-if="n === 1">one</p>
                  <p v-else>many</p>
                </div>
                <script>const s = vellum.state({n: 0})</script>""");
        Element zero = page.byId("d").children().getFirst();
        assertEquals(List.of("zero"), texts(page.byId("d")));
        page.run("s.n = 1");
        assertEquals(List.of("one"), texts(page.byId("d")));
        page.run("s.n = 5");
        assertEquals(List.of("many"), texts(page.byId("d")));
        page.run("s.n = 0");
        assertSame(zero, page.byId("d").children().getFirst(), "branches are kept and reused");
    }

    @Test
    void keyedListsMoveElementsInsteadOfRecreating() {
        Page page = new Page("""
                <ul id=list><li v-for="(item, i) in items" :key="item.id" :data-index="i">{{ item.name }}</li></ul>
                <script>const s = vellum.state({items: [{id: 1, name: 'a'}, {id: 2, name: 'b'}, {id: 3, name: 'c'}]})</script>""");
        Element list = page.byId("list");
        List<Element> before = list.children();
        assertEquals(List.of("a", "b", "c"), texts(list));
        page.run("s.items.reverse(); s.items[0].name = 'C'");
        List<Element> after = list.children();
        assertEquals(List.of("C", "b", "a"), texts(list));
        assertSame(before.get(2), after.get(0));
        assertSame(before.get(0), after.get(2));
        assertEquals("0", after.getFirst().getAttribute("data-index"));
        page.run("s.items.splice(1, 1); s.items.push({id: 9, name: 'z'})");
        assertEquals(List.of("C", "a", "z"), texts(list));
        assertSame(before.get(0), list.children().get(1));
    }

    @Test
    void forOverObjectsRangesAndNesting() {
        Page page = new Page("""
                <p id=o><b v-for="(value, key, index) in {x: 1, y: 2}">{{ index }}{{ key }}={{ value }}</b></p>
                <p id=r><i v-for="n in 3">{{ n }}</i></p>
                <p id=n><span v-for="row in [[1, 2], [3]]"><u v-for="cell in row" v-if="cell !== 2">{{ cell }}</u></span></p>""");
        assertEquals(List.of("0x=1", "1y=2"), texts(page.byId("o")));
        assertEquals(List.of("1", "2", "3"), texts(page.byId("r")));
        assertEquals("<span><u>1</u></span><span><u>3</u></span>", page.byId("n").innerHTML());
    }

    @Test
    void showClassAndStyle() {
        Page page = new Page("""
                <div id=d class="base" style="color: red" v-show="visible" :class="{active: on, off: !on}" :style="{marginTop: gap + 'px'}"
                     :title="on ? 'yes' : null" :disabled="!on"></div>
                <p id=p v-class="['x', {y: on}]" v-style="'color: blue'"></p>
                <script>const s = vellum.state({visible: true, on: true, gap: 4})</script>""");
        Element d = page.byId("d");
        assertEquals("x y", page.byId("p").getAttribute("class"));
        assertEquals("color: blue", page.byId("p").getAttribute("style"));
        assertEquals("base active", d.getAttribute("class"));
        assertEquals("color: red; margin-top: 4px", d.getAttribute("style"));
        assertFalse(d.hasAttribute("v-hidden"));
        assertEquals("yes", d.getAttribute("title"));
        assertFalse(d.hasAttribute("disabled"));
        page.run("s.visible = false; s.on = false");
        assertEquals("base off", d.getAttribute("class"));
        assertTrue(d.hasAttribute("v-hidden"));
        assertNull(d.getAttribute("title"));
        assertEquals("", d.getAttribute("disabled"));
    }

    @Test
    void eventModifiers() {
        Page page = new Page("""
                <div id=outer @click="log.push('outer')">
                  <a id=a href=x @click.prevent.stop="log.push('a')">a</a>
                  <b id=b @click.once="log.push('once')">b</b>
                  <i id=i @click.self="log.push('self')"><u id=u>u</u></i>
                  <input id=k @keydown.enter="log.push('enter ' + $event.key)" @keydown.ctrl.space="log.push('ctrl-space')">
                  <p id=m @click="record">m</p>
                  <p id=f @click="e => log.push('arrow ' + e.type)">f</p>
                </div>
                <script>
                const log = [];
                const state = vellum.state({clicks: 0, record(e) { this.clicks++; log.push('method ' + e.type) }});
                </script>""");
        Event click = new Event("click", true, true);
        page.byId("a").dispatchEvent(click);
        assertTrue(click.defaultPrevented());
        page.byId("b").click();
        page.byId("b").click();
        page.byId("u").click();
        page.byId("i").click();
        page.byId("k").dispatchEvent(new KeyboardEvent("keydown", "a", "KeyA", false, Modifiers.NONE));
        page.byId("k").dispatchEvent(new KeyboardEvent("keydown", "Enter", "Enter", false, Modifiers.NONE));
        page.byId("k").dispatchEvent(new KeyboardEvent("keydown", " ", "Space", false, Modifiers.NONE));
        page.byId("k").dispatchEvent(new KeyboardEvent("keydown", " ", "Space", false, new Modifiers(false, true, false, false)));
        page.byId("m").click();
        page.byId("f").click();
        assertEquals("a,once,outer,outer,outer,self,outer,enter Enter,ctrl-space,method click,outer,arrow click,outer 1",
                page.eval("log.join() + ' ' + state.clicks"));
    }

    @Test
    void textModelRoundTrip() {
        Page page = new Page("""
                <input id=name v-model="name"><input id=age type=number v-model="age"><input id=t v-model.trim.lazy="note">
                <textarea id=area v-model="name"></textarea>
                <script>const s = vellum.state({name: 'Steve', age: 3, note: ''})</script>""");
        Element name = page.byId("name");
        assertEquals("Steve", name.value());
        assertEquals("Steve", page.byId("area").value());
        type(name, "Alex");
        assertEquals("Alex", page.eval("s.name"));
        assertEquals("Alex", page.byId("area").value(), "other bindings re-render after the input event");
        type(page.byId("age"), "12");
        assertEquals("number 12", page.eval("typeof s.age + ' ' + s.age"));
        type(page.byId("t"), "  spaced ");
        assertEquals("", page.eval("s.note"), ".lazy waits for change");
        page.byId("t").dispatchEvent(new Event("change", true, false));
        assertEquals("spaced", page.eval("s.note"));
        page.run("s.name = 'Zed'");
        assertEquals("Zed", name.value());
    }

    @Test
    void checkboxRadioAndSelectModels() {
        Page page = new Page("""
                <input id=c type=checkbox v-model="agreed">
                <input id=x type=checkbox value=x v-model="picked"><input id=y type=checkbox value=y v-model="picked">
                <input id=r1 type=radio name=r value=1 v-model.number="size"><input id=r2 type=radio name=r value=2 v-model.number="size">
                <select id=s v-model="mode"><option>easy</option><option>hard</option></select>
                <script>const s = vellum.state({agreed: false, picked: ['y'], size: 2, mode: 'hard'})</script>""");
        assertFalse(page.byId("c").checked());
        assertFalse(page.byId("x").checked());
        assertTrue(page.byId("y").checked());
        assertTrue(page.byId("r2").checked());
        assertEquals(1, page.byId("s").selectedIndex());

        toggle(page.byId("c"), true);
        toggle(page.byId("x"), true);
        toggle(page.byId("y"), false);
        page.byId("r2").setChecked(false);
        toggle(page.byId("r1"), true);
        page.byId("s").setValue("easy");
        page.byId("s").dispatchEvent(new Event("change", true, false));
        assertEquals("true x 1 number easy", page.eval("[s.agreed, s.picked.join(), s.size, typeof s.size, s.mode].join(' ')"));
    }

    private static void toggle(Element input, boolean checked) {
        input.setChecked(checked);
        input.dispatchEvent(new Event("input", true, false));
        input.dispatchEvent(new Event("change", true, false));
    }

    @Test
    void textHtmlCloakAndPre() {
        Page page = new Page("""
                <p id=t v-text="'<b>' + n">ignored {{ n }}</p>
                <p id=h v-html="'<b>' + n + '</b>'"></p>
                <p id=c v-cloak>{{ n }}</p>
                <p id=p v-pre>{{ n }}</p>
                <script>vellum.state({n: 1})</script>""");
        assertEquals("&lt;b&gt;1", page.byId("t").innerHTML());
        assertEquals("<b>1</b>", page.byId("h").innerHTML());
        assertFalse(page.byId("c").hasAttribute("v-cloak"));
        assertEquals("{{ n }}", page.byId("p").textContent());
    }

    @Test
    void brokenExpressionsAreReportedOnceAndOthersStillRender() {
        Page page = new Page("<p id=a>{{ missing.x }}</p><p id=b>{{ n }}</p><script>const s = vellum.state({n: 1})</script>", false);
        page.run("s.n = 2");
        page.run("s.n = 3");
        assertEquals("3", page.byId("b").textContent());
        assertEquals(1, page.host.errors.size(), page.errors());
        assertTrue(page.errors().contains("Error in template text \"{{ missing.x }}\": ReferenceError"), page.errors());
    }

    @Test
    void listItemsShareCompiledExpressions() {
        Page page = new Page("<ul><li v-for=\"x in [1, 2, 3]\">{{ x.a.b }}</li></ul><ul><li v-for=\"x in [1, 2]\">{{ x.c.d }}</li></ul>", false);
        assertEquals(2, page.host.errors.size(), page.errors()); // one per site, not one per item
    }

    @Test
    void syntaxErrorsInTemplatesAreReported() {
        Page page = new Page("<p :title=\"a +\">x</p>", false);
        assertTrue(page.errors().contains("SyntaxError"), page.errors());
    }

    @Test
    void unstableTemplatesStopAfterTenPasses() {
        Page page = new Page("<p>{{ s.n++ }}</p><script>const s = vellum.state({n: 0})</script>");
        assertTrue(page.host.logs.stream().anyMatch(l -> l.startsWith("WARN: Templates still changing")), page.host.logs.toString());
        assertEquals("10", page.eval("s.n"));
    }

    private static List<String> texts(Element parent) {
        return parent.children().stream().map(Element::textContent).toList();
    }
}
