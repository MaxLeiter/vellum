package dev.vellum.engine.script;

import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxTest {
    @Test
    void evaluateToJsonGivesTheCompletionValue() {
        Page page = new TestHost().recordErrors().load("");
        ScriptRuntime scripts = page.doc.scripts();
        assertEquals("[1,\"a\",{\"b\":true}]", scripts.evaluateToJson("let n = 1; [n, 'a', {b: true}]", "test"));
        assertNull(scripts.evaluateToJson("undefined", "test"));
        assertNull(scripts.evaluateToJson("(function () {})", "test"), "no JSON form");
        assertTrue(page.host.errors.isEmpty());
        assertNull(scripts.evaluateToJson("nope(", "test"));
        assertNull(scripts.evaluateToJson("nope()", "test"));
        assertEquals(2, page.host.errors.size(), "errors are reported");
    }

    @Test
    void noJavaAccess() {
        Page page = new TestHost().load("");
        assertEquals("undefined undefined undefined undefined undefined",
                page.eval("[typeof java, typeof Packages, typeof JavaAdapter, typeof importClass, typeof getClass].join(' ')"));
        assertEquals("ReferenceError", page.eval("(function () { try { java.lang.System.exit(0) } catch (e) { return e.name } })()"));
    }

    @Test
    void errorObjectsDoNotLeakJavaExceptions() {
        Page page = new TestHost().load("");
        // A script error and a host error (a DOM call with a bad argument) both arrive without Java objects attached.
        assertEquals("TypeError undefined undefined", page.eval(
                "(function () { try { null.x } catch (e) { return [e.name, typeof e.javaException, typeof e.rhinoException].join(' ') } })()"));
        assertEquals("TypeError undefined", page.eval(
                "(function () { try { document.body.appendChild('nope') } catch (e) { return e.name + ' ' + typeof e.javaException } })()"));
    }

    @Test
    void runawayScriptIsStoppedAndReportedAndThePageKeepsWorking() {
        Page page = new TestHost().load("");
        page.host.failOnError = false;
        page.run("var cleanedUp = false; try { while (true) {} } catch (e) {} finally { cleanedUp = true }");
        assertEquals(1, page.host.errors.size());
        assertTrue(page.host.errors.getFirst().contains("budget"), page.host.errors.getFirst());
        assertEquals("false", page.eval("cleanedUp"), "neither catch nor finally may run");
        assertEquals("2", page.eval("1 + 1"));
    }

    @Test
    void budgetUnwindsThroughNestedListeners() {
        Page page = new TestHost().load("<button id=b></button>");
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

    /** A simulated clock, in ns; tests move it. */
    private final long[] now = {0};

    @AfterEach
    void realClock() {
        Sandbox.clock = System::nanoTime;
    }

    private void simulatedClock() {
        Sandbox.clock = () -> now[0];
    }

    @Test
    void compilingIsOffTheClock() {
        simulatedClock();
        String loop = "for (var i = 0; i < 100000; i++) {}"; // several instruction checks, which also check the clock
        Sandbox.run(cx -> {
            Sandbox.compile("source", () -> now[0] += 5_000_000_000L); // a five-second compile on a cold JVM
            return cx.evaluateString(cx.initSafeStandardObjects(), loop, "test", 1, null);
        });
        assertThrows(Sandbox.BudgetExceeded.class, () -> Sandbox.run(cx -> {
            now[0] += 5_000_000_000L; // the same time spent running
            return cx.evaluateString(cx.initSafeStandardObjects(), loop, "test", 1, null);
        }));
    }

    @Test
    void compilingCostsInstructionsSoGeneratedCodeCannotRunForever() {
        simulatedClock(); // frozen: only instructions can stop the loop
        Page page = new TestHost().load("<button id=b></button>");
        page.host.failOnError = false;
        page.run("""
                var b = document.getElementById('b'), big = '/*' + 'x'.repeat(100000) + '*/', n = 0;
                for (;;) { b.setAttribute('onclick', big + 'n++'); b.click(); big += ' ' }""");
        assertEquals(1, page.host.errors.size(), page.host.errors.toString());
        assertTrue(page.host.errors.getFirst().contains("budget"), page.host.errors.getFirst());
        assertTrue(Integer.parseInt(page.eval("n")) < Sandbox.INSTRUCTION_BUDGET / 100000, page.eval("n"));
    }

    /**
     * A page whose first render is slow (here every binding makes a slow host call: on a cold JVM everything is) loads
     * within the load's allowance; the same work later is over the usual budget.
     */
    @Test
    void loadingTakesTheLoadBudget() {
        simulatedClock();
        TestHost host = new TestHost() {
            @Override
            public String translate(String key, String... args) {
                now[0] += 5_000_000; // 5 ms
                return key;
            }
        };
        // Each binding runs enough instructions for the budget to be checked as the render goes.
        StringBuilder html = new StringBuilder("""
                <script>
                  var s = vellum.state({n: 0});
                  function slow(key) { for (var i = 0; i < 50; i++) {} return vellum.t(key) }
                </script>""");
        for (int i = 0; i < 400; i++) html.append("<p :title=\"slow('k') + s.n\">{{ slow('t') }}</p>");
        Page page = host.load(html.toString()); // 800 slow calls: 4 s
        assertEquals("k0", page.query("p").getAttribute("title"));
        assertEquals("t", page.query("p").textContent());
        host.failOnError = false;
        page.run("s.n = 1");
        page.frame(16);
        assertEquals(1, host.errors.size(), host.errors.toString());
        assertTrue(host.errors.getFirst().contains("Error in templates: Script stopped"), host.errors.getFirst());
    }

    @Test
    void deepRecursionIsCatchable() {
        Page page = new TestHost().load("");
        assertEquals("InternalError", page.eval("(function () { try { (function f() { f() })() } catch (e) { return e.name } })()"));
        page.host.failOnError = false;
        page.run("(function f() { f() })()");
        assertTrue(page.host.errors.getFirst().contains("stack depth"), page.host.errors.getFirst());
    }

    @Test
    void regularExpressionsWork() {
        Page page = new TestHost().load("");
        assertEquals("bbb a+b+c true", page.eval("[/a(b+)c/.exec('xabbbc')[1], 'a-b-c'.replace(/-/g, '+'), /^\\d+$/.test('42')].join(' ')"));
    }

    @Test
    void promisesAndMicrotasksRunWhenTheScriptEnds() {
        Page page = new TestHost().load("""
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
        Page page = new TestHost().load("");
        page.host.failOnError = false;
        page.run("Promise.reject(new Error('nope')); Promise.reject(1).catch(() => {})");
        assertEquals(1, page.host.errors.size(), page.host.errors.toString());
        assertTrue(page.host.errors.getFirst().contains("Uncaught (in promise): Error: nope"), page.host.errors.getFirst());
    }

    @Test
    void syntaxErrorsReportTheLine() {
        Page page = new TestHost().recordErrors().load("<script>\nvar a = 1;\nvar b = ;\n</script>");
        assertTrue(page.errors().contains("SyntaxError") && page.errors().contains("test:page.html#script:3"), page.errors());
    }

    @Test
    void runtimeErrorsReportTheLine() {
        Page page = new TestHost().recordErrors().load("<script>\n\nnull.foo;\n</script>");
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
        assertEquals("true", new TestHost().load("").eval(expression));
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
        Page page = new TestHost().load("");
        page.host.failOnError = false;
        page.run(code);
        assertTrue(!page.host.errors.isEmpty() && page.host.errors.getFirst().contains("SyntaxError"), page.host.errors.toString());
    }

    @Test
    void loopLetSharesOneBindingAcrossIterations() {
        Page page = new TestHost().load("");
        assertEquals("3,3,3", page.eval(
                "(function () { var fs = []; for (let i = 0; i < 3; i++) fs.push(() => i); return fs.map(f => f()).join() })()"));
        assertEquals("2,2", page.eval(
                "(function () { var fs = []; for (let x of [1, 2]) fs.push(() => x); return fs.map(f => f()).join() })()"));
        assertEquals("1,2", page.eval(
                "(function () { var fs = []; [1, 2].forEach(x => fs.push(() => x)); return fs.map(f => f()).join() })()"));
        assertEquals("undefined", page.eval("typeof Intl"));
    }
}
