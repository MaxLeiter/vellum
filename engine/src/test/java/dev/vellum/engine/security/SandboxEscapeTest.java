package dev.vellum.engine.security;

import dev.vellum.engine.Limits;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A server-sent page tries to reach Java. Pages run with no LiveConnect: no Java globals, no Java objects anywhere
 * in the object graph, error objects without Java exceptions, and host methods that refuse a {@code this} that is not
 * theirs.
 */
class SandboxEscapeTest {
    private final Page page = new TestHost().load("<div id=d data-x=1><canvas id=c></canvas></div>");

    @ParameterizedTest
    @ValueSource(strings = {"java", "javax", "com", "org", "net", "edu", "Packages", "JavaAdapter", "JavaImporter",
            "importPackage", "importClass", "getClass", "Continuation", "Script", "XML", "XMLList", "load", "loadClass",
            "defineClass", "print", "readFile", "readUrl", "runCommand", "spawn", "sync", "quit", "Intl", "With", "Call",
            "JavaException", "isXMLName"})
    void noJavaOrShellGlobals(String name) {
        assertEquals("undefined", page.eval("typeof " + name));
        assertEquals("undefined", page.eval("typeof (function () { return this })()['" + name + "']"));
    }

    /**
     * Walks everything a script can reach from the global object and from live host objects (nodes, events, styles,
     * storage, canvas contexts, animations, errors), reading every property through its getter, and checks that none
     * of it is a Java wrapper.
     */
    @Test
    void nothingReachableIsAJavaObject() {
        // The walk takes about a second, near the default time budget, and this test is about escapes, not budgets.
        Page page = new TestHost().limits(Limits.DEFAULTS.with("timeBudgetMs", 30_000).with("instructionBudget", 1L << 40))
                .load("<div id=d data-x=1><canvas id=c></canvas></div>");
        String result = page.eval("""
                (function () {
                  var global = (function () { return this })(), d = document.getElementById('d');
                  var roots = [global, document, d, d.style, d.classList, d.dataset, getComputedStyle(d), new Event('x'),
                      new CustomEvent('y', {detail: {a: 1}}), localStorage, location, performance, console, vellum,
                      document.getElementById('c').getContext('2d'), d.animate([{opacity: 0}, {opacity: 1}], 100),
                      d.getBoundingClientRect(), d.attributes, vellum.on('z', function () {})];
                  try { document.body.appendChild('x') } catch (e) { roots.push(e) }
                  try { null.x } catch (e) { roots.push(e) }
                  var seen = new Set(), queue = [], bad = [];
                  function visit(v, path) {
                    if (v === null || (typeof v !== 'object' && typeof v !== 'function') || seen.has(v)) return;
                    seen.add(v);
                    queue.push([v, path]);
                  }
                  roots.forEach(function (r, i) { visit(r, 'root' + i) });
                  for (var q = 0; q < queue.length && q < 20000; q++) {
                    var o = queue[q][0], path = queue[q][1];
                    try { Object.getOwnPropertyNames(o) } catch (e) { bad.push(path + ' cannot be listed: ' + e); continue; }
                    var tag = Object.prototype.toString.call(o);
                    if (/Java/.test(tag)) bad.push(path + ' ' + tag);
                    if (typeof o === 'function' && /java[.]|javax[.]/.test(Function.prototype.toString.call(o))) bad.push(path + ' is a Java method');
                    var names = [];
                    for (var p = o; p !== null; p = Object.getPrototypeOf(p)) names = names.concat(Object.getOwnPropertyNames(p));
                    names.forEach(function (k) {
                      var desc = Object.getOwnPropertyDescriptor(o, k);
                      if (desc && desc.get) visit(desc.get, path + '.get ' + k);
                      if (desc && desc.set) visit(desc.set, path + '.set ' + k);
                      try { visit(o[k], path + '.' + k) } catch (e) { visit(e, path + '.' + k + '!') }
                    });
                    Object.getOwnPropertySymbols(o).forEach(function (k) {
                      try { visit(o[k], path + '[' + String(k) + ']') } catch (e) { visit(e, path + '[' + String(k) + ']!') }
                    });
                    visit(Object.getPrototypeOf(o), path + '.__proto__');
                  }
                  return seen.size + ' ' + bad.join(', ');
                })()""");
        String[] parts = result.split(" ", 2);
        assertTrue(Integer.parseInt(parts[0]) > 500, "the walk reached " + parts[0] + " objects");
        assertEquals("", parts.length > 1 ? parts[1] : "", "Java objects reachable from scripts");
    }

    @Test
    void generatedCodeIsSandboxedToo() {
        assertEquals("undefined undefined undefined", page.eval(
                "[Function('return typeof Packages')(), eval('typeof java'), document.body.constructor.constructor('return typeof getClass')()].join(' ')"));
        assertEquals("true undefined", page.eval(
                "(function () { var g = document.constructor.constructor('return this')(); return [g === window, typeof g.Packages].join(' ') })()"));
        assertEquals("undefined", page.eval("(function () { with (document.body) { return typeof getClass } })()"));
        page.run("setTimeout('window.fromTimer = typeof Packages + typeof java', 0)");
        page.frame();
        assertEquals("undefinedundefined", page.eval("fromTimer"));
    }

    @Test
    void templateExpressionsCannotReachJava() {
        Page p = new TestHost().load("<script>vellum.state({})</script>"
                + "<p id=p>{{ constructor.constructor('return typeof Packages + typeof java')() }}</p>");
        assertEquals("undefinedundefined", p.byId("p").textContent());
    }

    @Test
    void errorObjectsCarryNoJavaExceptions() {
        String result = page.eval("""
                (function () {
                  var out = [];
                  function probe(f) {
                    try { f(); out.push('no error') } catch (e) {
                      out.push(e.name + ':' + typeof e.javaException + ':' + typeof e.rhinoException + ':'
                          + Object.getOwnPropertyNames(e).filter(function (k) { return /java|rhino/i.test(k) }).join())
                    }
                  }
                  probe(function () { null.x });
                  probe(function () { document.body.appendChild('x') });
                  probe(function () { document.querySelector('###') });
                  probe(function () { eval('('.repeat(100000)) });
                  probe(function () { new Array(2 ** 32 - 1).indexOf(0) });
                  probe(function () { (function f() { f() })() });
                  probe(function () { document.body.innerHTML = 'x'.repeat(2000000) });
                  probe(function () { new ArrayBuffer(2 ** 31 - 2) });
                  probe(function () { JSON.parse('['.repeat(1000)) });
                  return out.join('\\n');
                })()""");
        for (String line : result.split("\n")) {
            assertTrue(line.endsWith(":undefined:undefined:"), line);
        }
    }

    @Test
    void hostMethodsRefuseForeignReceivers() {
        String result = page.eval("""
                (function () {
                  var out = [];
                  function probe(f) { try { f(); out.push('ok') } catch (e) { out.push(e.name) } }
                  probe(function () { Element.prototype.getAttribute.call({}, 'id') });
                  probe(function () { Object.create(Element.prototype).getAttribute('id') });
                  probe(function () { var b = document.body; b.__proto__ = Text.prototype; return b.data });
                  probe(function () { CSSStyleDeclaration.prototype.setProperty.call(getComputedStyle(document.getElementById('d')), 'color', 'red') });
                  probe(function () { Node.prototype.appendChild.call(localStorage, document.createElement('p')) });
                  probe(function () { new Element() });
                  return out.join(' ');
                })()""");
        assertEquals("TypeError TypeError TypeError TypeError TypeError TypeError", result);
    }

    @Test
    void hostObjectsLookLikeTheDom() {
        assertEquals("[object Element] [object Document] [object CSSStyleDeclaration] [object Storage]", page.eval(
                "[document.body, document, document.body.style, localStorage].map(function (o) { return Object.prototype.toString.call(o) }).join(' ')"));
        assertFalse(page.eval("Object.getOwnPropertyNames(document.body).join()").contains("target"));
    }
}
