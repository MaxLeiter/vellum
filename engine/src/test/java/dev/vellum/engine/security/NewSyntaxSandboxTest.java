package dev.vellum.engine.security;

import dev.vellum.engine.Limits;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The syntax Vellum's Rhino patches add (classes, super, spread calls, async functions) must not reach Java or slip past
 * the script budgets.
 */
class NewSyntaxSandboxTest {
    private static final Duration QUICKLY = Duration.ofSeconds(5);

    private static String tryEval(Page page, String body) {
        return page.eval("(function () { try { " + body + " } catch (e) { return e.name + ': ' + e.message } })()");
    }

    @Test
    void classesCanExtendHostClassesWithoutReachingJava() {
        Page page = new TestHost().load("<div id=d></div>");
        assertEquals("true true ping 1", page.eval("""
                (function () {
                  class Ping extends Event { constructor() { super('ping'); this.n = 1; } get kind() { return super.type; } }
                  var seen = [];
                  var d = document.getElementById('d');
                  d.addEventListener('ping', e => seen.push(e));
                  var p = new Ping();
                  d.dispatchEvent(p);
                  return [p instanceof Ping, p instanceof Event, p.kind, seen.length].join(' ');
                })()"""));
        assertEquals("undefined undefined", page.eval("""
                (function () {
                  class Ping extends Event { constructor() { super('ping'); } }
                  var p = new Ping();
                  return [typeof p.getClass, typeof Object.getPrototypeOf(Ping).getClass].join(' ');
                })()"""));
    }

    @Test
    void hostClassesWithoutConstructorsStayIllegal() {
        Page page = new TestHost().load("");
        assertEquals("TypeError: Illegal constructor",
                tryEval(page, "class Mine extends MouseEvent {} new Mine('click'); return 'made'"));
    }

    @Test
    void superAccessOnHostPrototypesChecksTheReceiver() {
        Page page = new TestHost().load("");
        String result = tryEval(page, """
                var o = { __proto__: Event.prototype, t() { return super.type; } };
                return 'got ' + o.t();""");
        assertTrue(result.startsWith("TypeError"), result);
        assertTrue(!result.contains("java") && !result.contains("Exception"), result);
    }

    @Test
    void spreadCallsOfHugeArraysAreCapped() {
        Page page = new TestHost().load("");
        String result = assertTimeoutPreemptively(QUICKLY,
                () -> tryEval(page, "Math.max(...new Array(2 ** 32 - 1)); return 'no error'"));
        assertTrue(result.startsWith("RangeError"), result);
    }

    @Test
    void awaitLoopRunsOutOfBudget() {
        TestHost host = new TestHost().recordErrors().limits(Limits.DEFAULTS.with("instructionBudget", 1_000_000));
        Page page = host.load("");
        assertTimeoutPreemptively(QUICKLY, () -> page.run("(async () => { for (;;) await null; })()"));
        assertTrue(page.errors().contains("Script stopped"), page.errors());
        assertEquals("2", page.eval("1 + 1"), "the page keeps working");
    }

    @Test
    void busyAsyncFunctionRunsOutOfTime() {
        TestHost host = new TestHost().recordErrors().limits(Limits.DEFAULTS.with("timeBudgetMs", 50));
        Page page = host.load("");
        assertTimeoutPreemptively(QUICKLY, () -> page.run(
                "(async () => { for (;;) { await null; var t = Date.now(); while (Date.now() - t < 5); } })()"));
        assertTrue(page.errors().contains("Script stopped"), page.errors());
    }

    @Test
    void awaitsOnTimersStopWithThePage() {
        TestHost host = new TestHost().recordErrors().limits(Limits.DEFAULTS.with("instructionBudget", 1_000_000));
        Page page = host.load("""
                <script>
                (async () => { for (;;) { await new Promise(r => setTimeout(r, 0)); while (true); } })();
                </script>""");
        assertTimeoutPreemptively(QUICKLY, () -> {
            for (int f = 1; f <= 10; f++) page.frame(f * 16);
        });
        assertTrue(page.errors().contains("Script stopped"), page.errors());
        assertEquals("2", page.eval("1 + 1"));
    }

    @Test
    void microtaskStormFromAsyncFunctionsIsStopped() {
        TestHost host = new TestHost().recordErrors().limits(Limits.DEFAULTS.with("instructionBudget", 1_000_000));
        Page page = host.load("");
        assertTimeoutPreemptively(QUICKLY, () -> page.run(
                "async function storm() { await null; storm(); storm(); } storm();"));
        assertTrue(page.errors().contains("Script stopped"), page.errors());
        assertEquals("2", page.eval("1 + 1"));
    }

    @Test
    void classConstructionDoesNotLeakItsThis() {
        Page page = new TestHost().load("");
        assertEquals("true", page.eval("""
                (function () {
                  var leaked;
                  class A { constructor() { leaked = this; } }
                  class B extends A { constructor() { var f = () => this; super(); leaked = f(); } }
                  return new B() === leaked;
                })()"""));
    }
}
