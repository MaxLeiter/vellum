package dev.vellum.engine.security;

import dev.vellum.engine.Limits;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A server-sent page tries to freeze the game with script work the instruction budget does not see. */
class ScriptCpuTest {
    private static final Duration QUICKLY = Duration.ofSeconds(5);

    /**
     * Built-ins loop and allocate in Java, out of the instruction budget's sight. Before the caps each of these spun
     * for minutes or ran out of memory; now each is a catchable RangeError, at once.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "Array.prototype.indexOf.call({length: 2 ** 53 - 1}, 1)",
            "Array.prototype.map.call({length: 2 ** 40}, function (x) { return x })",
            "new Array(2 ** 32 - 1).indexOf(1)",
            "new Array(2 ** 32 - 1).reverse()",
            "new Array(2 ** 32 - 1).concat([1])",
            "[].concat(new Array(2 ** 32 - 1))",
            "new Array(2 ** 32 - 1).fill(0)",
            "new Array(2 ** 32 - 1).join('ab')",
            "new Array(2 ** 32 - 1).sort()",
            "new Array(2 ** 32 - 1).keys().next()",
            "String(new Array(2 ** 32 - 1))",
            "new Array(2 ** 32 - 1) + ''",
            "[...new Array(2 ** 32 - 1)]",
            "Array.from({length: 2 ** 32 - 1})",
            "Object.keys(new Array(1e7).fill(0))",
            "JSON.stringify(new Array(2 ** 32 - 1))",
            "JSON.stringify({a: [new Array(2 ** 32 - 1)]})",
            "JSON.stringify({get a() { return new Array(2 ** 32 - 1) }})",
            "JSON.stringify({toJSON: function () { return new Array(2 ** 32 - 1) }})",
            "JSON.stringify(new Array(2 ** 32 - 1), ['a'])",
            "JSON.stringify(1, function (k, v) { return k === '' ? [new Array(2 ** 32 - 1)] : v })",
            "Math.max.apply(null, {length: 2 ** 31})",
            "Reflect.apply(Math.max, null, {length: 2 ** 31})",
            "String.fromCharCode.apply(null, new Array(2 ** 25))",
            "'x'.repeat(2 ** 30)",
            "'x'.padStart(2 ** 30)",
            "'x'.padEnd(2 ** 30)",
            "new ArrayBuffer(2 ** 31 - 2)",
            "new Float64Array(2 ** 28)",
            "new Uint8Array({length: 2 ** 30})",
            "Int32Array(2 ** 28)",
            "String.raw({raw: {length: 2 ** 31 - 1}})",
            "'a'.repeat(2e6).split('')",
            "'a'.repeat(2e6).split(/(?:)/)",
            "/(?:)/[Symbol.split]('a'.repeat(2e6))",
            "[...'a'.repeat(2e6)]",
            "Array.from('a'.repeat(2e6))",
            "'a'.repeat(2e6).match(/a/g)",
            "[...'a'.repeat(2e6).matchAll(/a/g)]",
            "/a/g[Symbol.match]('a'.repeat(2e6))",
            "'a'.repeat(1 << 20).replace(/a/g, '$&'.repeat(20))",
            "'a'.repeat(8192).replace(/a/g, '$`')",
            "'a'.repeat(1 << 24).replaceAll('a', 'bb')",
            "new Array(2000).fill('x'.repeat(1e4)).join('')",
            "1n << 100000000n",
            "2n ** 1000000n",
            "BigInt('9'.repeat(1 << 20))",
            "(function () { var x = 3n; for (var i = 0; i < 40; i++) x = x * x; return x })()",
            "BigInt.asUintN(2 ** 40, 1n)",
    })
    void builtInsThatLoopInJavaAreCapped(String expression) {
        Page page = new TestHost().load("");
        String result = assertTimeoutPreemptively(QUICKLY, () -> page.eval(
                "(function () { try { " + expression + "; return 'no error' } catch (e) { return e.name } })()"));
        assertEquals("RangeError", result, expression);
    }

    /** The same built-ins on ordinary sizes behave as before. */
    @ParameterizedTest
    @ValueSource(strings = {
            "[1, 2, 3].map(function (x) { return x * 2 }).join() === '2,4,6'",
            "new Array(1000).fill(1).reduce(function (a, b) { return a + b }) === 1000",
            "[...'abc'].length === 3 && Array.from(new Set([1, 2])).length === 2",
            "JSON.stringify({b: 1, a: 2, c: 3}, ['c', 'a']) === '{\"c\":3,\"a\":2}'",
            "JSON.stringify({a: [1, {b: 2, c: 3}]}, ['a', 'b']) === '{\"a\":[1,{\"b\":2}]}'",
            "JSON.stringify({a: 1, b: 'x'}, function (k, v) { return typeof v === 'number' ? v + 1 : v }) === '{\"a\":2,\"b\":\"x\"}'",
            "JSON.stringify([new String('s'), new Number(1)], ['x']) === '[\"s\",1]'",
            "JSON.stringify({a: 1}, null, 2) === '{\\n  \"a\": 1\\n}'",
            "JSON.parse('[[[1]]]')[0][0][0] === 1",
            "new Uint8Array([1, 2, 3]).length === 3 && new Uint8Array(4) instanceof Uint8Array",
            "new Uint8Array(4).constructor === Uint8Array && Uint8Array.BYTES_PER_ELEMENT === 1",
            "new Float32Array(new ArrayBuffer(16), 4, 2).length === 2 && new ArrayBuffer(8).byteLength === 8",
            "Uint8Array.name === 'Uint8Array' && Uint8Array.length === 3 && typeof Uint8Array.prototype.subarray === 'function'",
            "'ab'.repeat(3) === 'ababab' && '5'.padStart(3, '0') === '005' && '5'.padEnd(2) === '5 '",
            "Math.max.apply(null, [1, 5, 3]) === 5 && Reflect.apply(Math.min, null, [4, 2]) === 2",
            "Array.prototype.slice.call({length: 2, 0: 'a', 1: 'b'}).join() === 'a,b'",
            "'a,b'.split(',').length === 2 && 'a1b2'.split(/\\d/).join() === 'a,b,' && 'x'.repeat(2e6).split(',').length === 1",
            "'abc'.match(/b/g).length === 1 && [...'ab'.matchAll(/./g)].length === 2 && [...'ab'].join() === 'a,b'",
            "'aXbXc'.replace(/X/g, '-') === 'a-b-c' && 'x'.replace('x', '$&$&') === 'xx' && 'abc'.search(/c/) === 2",
            "'abc'.replace(/b/, function (m) { return m + m }) === 'abbc' && 'aa'.replaceAll('a', 'b') === 'bb'",
            "String.raw`a${1}b` === 'a1b' && ['a', 'b'].join('-') === 'a-b' && String([1, [2, 3]]) === '1,2,3'",
            "(2n ** 64n).toString() === '18446744073709551616' && BigInt(10) * 3n === 30n && (1n << 100n) > 0n",
            "new BigInt64Array(2)[0] === 0n && BigInt('123456789012345678901234567890') % 7n === 0n",
    })
    void ordinaryUseIsUnchanged(String expression) {
        assertEquals("true", new TestHost().load("").eval(expression), expression);
    }

    /** Rhino's regular expressions count their backtracking as instructions, so catastrophic patterns stop. */
    @Test
    void catastrophicRegexRunsOutOfBudget() {
        Page page = new TestHost().recordErrors().load("");
        assertTimeoutPreemptively(QUICKLY, () -> page.run("/(a+)+$/.test('a'.repeat(40) + 'b')"));
        assertTrue(page.errors().contains("Script stopped"), page.errors());
    }

    @Test
    void slowSortComparatorRunsOutOfBudget() {
        Page page = new TestHost().recordErrors().load("");
        assertTimeoutPreemptively(QUICKLY, () -> page.run(
                "[3, 2, 1, 5, 4].sort(function (a, b) { for (var i = 0; i < 1e9; i++); return a - b })"));
        assertTrue(page.errors().contains("Script stopped"), page.errors());
    }

    /** A thousand busy intervals: each frame runs them only until its timer allowance is spent. */
    @Test
    void intervalStormCannotHangAFrame() {
        Page page = new TestHost().recordErrors().load("""
                <script>
                for (var i = 0; i < 1000; i++) setInterval(function () { for (var j = 0; j < 1e6; j++); }, 0);
                </script>""");
        for (int f = 1; f <= 5; f++) {
            int frame = f;
            assertTimeoutPreemptively(Duration.ofMillis(1500), () -> page.frame(frame * 16));
        }
        assertNull(page.doc.error());
    }

    @Test
    void animationFrameStormCannotHangAFrame() {
        Page page = new TestHost().recordErrors().load("""
                <script>
                function burst() {
                  for (var i = 0; i < 1000; i++) requestAnimationFrame(function () { for (var j = 0; j < 1e6; j++); });
                  requestAnimationFrame(burst);
                }
                burst();
                </script>""");
        for (int f = 1; f <= 5; f++) {
            int frame = f;
            assertTimeoutPreemptively(Duration.ofMillis(1500), () -> page.frame(frame * 16));
        }
    }

    /** Timers that keep running out of budget stop the page after a few tries, instead of every frame for ever. */
    @Test
    void runawayTimersStopThePage() {
        TestHost host = new TestHost().recordErrors().limits(Limits.DEFAULTS.with("instructionBudget", 1_000_000));
        Page page = host.load("<script>setInterval(function () { while (true); }, 0)</script>");
        assertTimeoutPreemptively(QUICKLY, () -> {
            for (int f = 1; f <= 10; f++) page.frame(f * 16);
        });
        assertNotNull(page.doc.error());
        assertTrue(page.errors().contains("ran out of budget 3 times"), page.errors());
    }

    @Test
    void endlessTemplateExpressionIsStopped() {
        TestHost host = new TestHost().recordErrors().limits(Limits.DEFAULTS.with("instructionBudget", 1_000_000));
        Page page = assertTimeoutPreemptively(QUICKLY, () -> host.load(
                "<script>vellum.state({n: 1})</script><p>{{ (function () { while (true); })() }}</p>"));
        assertTrue(page.errors().contains("Script stopped"), page.errors());
    }

    /** A page that keeps every frame slow (here by script; styles or layout count the same) is stopped. */
    @Test
    void persistentlySlowFramesStopThePage() {
        TestHost host = new TestHost().recordErrors()
                .limits(Limits.DEFAULTS.with("slowFrameMs", 5).with("maxSlowFrames", 3));
        Page page = host.load("""
                <script>
                requestAnimationFrame(function spin() {
                  var t = Date.now(); while (Date.now() - t < 20);
                  requestAnimationFrame(spin);
                });
                </script>""");
        for (int f = 1; f <= 6; f++) page.frame(f * 16);
        assertNotNull(page.doc.error());
        assertTrue(page.errors().contains("frames in a row took longer than 5 ms"), page.errors());
    }

    @Test
    void timersAreCapped() {
        TestHost host = new TestHost().recordErrors().limits(Limits.DEFAULTS.with("maxTimers", 100));
        Page page = host.load("<script>for (var i = 0; i < 1000; i++) setTimeout(function () {}, 1e9)</script>");
        assertTrue(page.errors().contains("at most 100 pending timers"), page.errors());
        assertNull(page.doc.error());
    }
}
