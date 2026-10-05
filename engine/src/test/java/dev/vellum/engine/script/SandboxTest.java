package dev.vellum.engine.script;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxTest {
    @Test
    void noJavaAccess() {
        Page page = new Page("");
        assertEquals("undefined undefined undefined undefined undefined",
                page.eval("[typeof java, typeof Packages, typeof JavaAdapter, typeof importClass, typeof getClass].join(' ')"));
        assertEquals("ReferenceError", page.eval("(function () { try { java.lang.System.exit(0) } catch (e) { return e.name } })()"));
    }

    @Test
    void errorObjectsDoNotLeakJavaExceptions() {
        Page page = new Page("");
        // A script error and a host error (a DOM call with a bad argument) both arrive without Java objects attached.
        assertEquals("TypeError undefined undefined", page.eval(
                "(function () { try { null.x } catch (e) { return [e.name, typeof e.javaException, typeof e.rhinoException].join(' ') } })()"));
        assertEquals("TypeError undefined", page.eval(
                "(function () { try { document.body.appendChild('nope') } catch (e) { return e.name + ' ' + typeof e.javaException } })()"));
    }

    @Test
    void runawayScriptIsStoppedAndReportedAndThePageKeepsWorking() {
        Page page = new Page("");
        page.host.failOnError = false;
        page.run("var cleanedUp = false; try { while (true) {} } catch (e) {} finally { cleanedUp = true }");
        assertEquals(1, page.host.errors.size());
        assertTrue(page.host.errors.getFirst().contains("budget"), page.host.errors.getFirst());
        assertEquals("false", page.eval("cleanedUp"), "neither catch nor finally may run");
        assertEquals("2", page.eval("1 + 1"));
    }

    @Test
    void budgetUnwindsThroughNestedListeners() {
        Page page = Page.withScript("<button id=b></button>", "");
        page.host.failOnError = false;
        page.run("""
                var after = false;
                document.getElementById('b').addEventListener('click', () => { while (true) {} });
                document.getElementById('b').click();
                after = true;
                """);
        assertEquals(1, page.host.errors.size(), page.host.errors.toString());
        assertEquals("false", page.eval("after"));
    }

    @Test
    void deepRecursionIsCatchable() {
        Page page = new Page("");
        assertEquals("InternalError", page.eval("(function () { try { (function f() { f() })() } catch (e) { return e.name } })()"));
        page.host.failOnError = false;
        page.run("(function f() { f() })()");
        assertTrue(page.host.errors.getFirst().contains("stack depth"), page.host.errors.getFirst());
    }

    @Test
    void regularExpressionsWork() {
        Page page = new Page("");
        assertEquals("bbb a+b+c true", page.eval("[/a(b+)c/.exec('xabbbc')[1], 'a-b-c'.replace(/-/g, '+'), /^\\d+$/.test('42')].join(' ')"));
    }

    @Test
    void promisesAndMicrotasksRunWhenTheScriptEnds() {
        Page page = new Page("""
                <script>
                var log = [];
                log.push('a');
                Promise.resolve().then(() => log.push('c'));
                queueMicrotask(() => log.push('d'));
                log.push('b');
                </script>""");
        assertEquals("a,b,c,d", page.eval("log.join()"));
    }

    @Test
    void unhandledRejectionsAreReported() {
        Page page = new Page("");
        page.host.failOnError = false;
        page.run("Promise.reject(new Error('nope')); Promise.reject(1).catch(() => {})");
        assertEquals(1, page.host.errors.size(), page.host.errors.toString());
        assertTrue(page.host.errors.getFirst().contains("Uncaught (in promise): Error: nope"), page.host.errors.getFirst());
    }

    @Test
    void syntaxErrorsReportTheLine() {
        Page page = new Page("<script>\nvar a = 1;\nvar b = ;\n</script>", false);
        assertTrue(page.errors().contains("SyntaxError") && page.errors().contains("test:page.html#script:3"), page.errors());
    }

    @Test
    void runtimeErrorsReportTheLine() {
        Page page = new Page("<script>\n\nnull.foo;\n</script>", false);
        assertTrue(page.errors().contains("TypeError") && page.errors().contains("test:page.html#script:3"), page.errors());
    }

    /** Language features the docs promise. */
    @ParameterizedTest
    @ValueSource(strings = {
            "(function () { let a = 1; const b = 2; return a + b === 3 })()",
            "[1, 2].map(x => x * 2).join() === '2,4'",
            "`${1 + 1}` === '2'",
            "(function () { const {a, b: [c]} = {a: 1, b: [2]}; return a + c === 3 })()",
            "[...[1, 2], 3].length === 3",
            "({...{a: 1}, b: 2}).a === 1",
            "(function () { let s = 0; for (let x of [1, 2, 3]) s += x; return s === 6 })()",
            "({a: null})?.a?.b === undefined",
            "(null ?? 5) === 5",
            "(function* () { yield 1 })().next().value === 1",
            "new Map([[1, 2]]).get(1) === 2 && new Set([1, 1]).size === 1",
            "(function (a, b = 2, ...rest) { return a + b + rest.length })(1) === 3",
            "[1, [2, [3]]].flat(2).includes(3) && Object.entries({a: 1}).length === 1",
            "2 ** 10 === 1024",
    })
    void supportedFeatures(String expression) {
        assertEquals("true", new Page("").eval(expression));
    }

    /** The dialect limits SCRIPTING.md documents: each is a syntax error. */
    @ParameterizedTest
    @ValueSource(strings = {
            "class A {}",
            "async function f() { await 1 }",
            "Math.max(...[1, 2])",
            "for (const x of [1, 2]) {}",
            "for (const k in {a: 1}) {}",
            "const {a, ...rest} = {a: 1, b: 2}",
            "const f = async () => 1",
            "import x from 'y'",
            "export var a = 1",
    })
    void unsupportedSyntax(String code) {
        Page page = new Page("");
        page.host.failOnError = false;
        page.run(code);
        assertTrue(!page.host.errors.isEmpty() && page.host.errors.getFirst().contains("SyntaxError"), page.host.errors.toString());
    }

    @Test
    void loopLetSharesOneBindingAcrossIterations() {
        Page page = new Page("");
        assertEquals("3,3,3", page.eval(
                "(function () { var fs = []; for (let i = 0; i < 3; i++) fs.push(() => i); return fs.map(f => f()).join() })()"));
        assertEquals("2,2", page.eval(
                "(function () { var fs = []; for (let x of [1, 2]) fs.push(() => x); return fs.map(f => f()).join() })()"));
        assertEquals("1,2", page.eval(
                "(function () { var fs = []; [1, 2].forEach(x => fs.push(() => x)); return fs.map(f => f()).join() })()"));
        assertEquals("undefined", page.eval("typeof Intl"));
    }
}
