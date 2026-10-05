package dev.vellum.engine.script;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimersAndConsoleTest {
    @Test
    void timeoutsRunInOrderWithArguments() {
        Page page = new Page("""
                <script>
                const log = [];
                setTimeout((a, b) => log.push('late ' + a + b), 50, 'x', 'y');
                setTimeout(() => log.push('early'), 10);
                const cancelled = setTimeout(() => log.push('never'), 20);
                clearTimeout(cancelled);
                setTimeout("log.push('code')", 30);
                </script>""");
        page.advance(5);
        assertEquals("", page.eval("log.join()"));
        page.advance(100);
        assertEquals("early,code,late xy", page.eval("log.join()"));
    }

    @Test
    void intervalsRepeatUntilCleared() {
        Page page = new Page("<script>let n = 0; const id = setInterval(() => { if (++n === 3) clearInterval(id) }, 10);</script>");
        for (int t = 10; t <= 100; t += 10) page.advance(t);
        assertEquals("3", page.eval("n"));
    }

    @Test
    void animationFramesGetTheFrameTimeAndRunAfterTimers() {
        Page page = new Page("""
                <script>
                const log = [];
                requestAnimationFrame(t => log.push('frame ' + t + ' ' + performance.now()));
                setTimeout(() => log.push('timer'), 0);
                cancelAnimationFrame(requestAnimationFrame(() => log.push('cancelled')));
                </script>""");
        page.advance(16);
        assertEquals("timer,frame 16 16", page.eval("log.join()"));
        page.advance(32);
        assertEquals("timer,frame 16 16", page.eval("log.join()"), "frame callbacks run once");
    }

    @Test
    void microtasksRunAfterEachTimerCallback() {
        Page page = new Page("""
                <script>
                const log = [];
                setTimeout(() => { Promise.resolve().then(() => log.push('micro')); log.push('a') }, 0);
                setTimeout(() => log.push('b'), 0);
                </script>""");
        page.advance(1);
        assertEquals("a,micro,b", page.eval("log.join()"));
    }

    @Test
    void consoleFormatsLikeBrowsers() {
        Page page = new Page("");
        page.run("""
                console.log('plain', 1, true, null, undefined);
                console.log({a: [1, 'x']}, [1, 2]);
                console.log('%s has %d items (%i%%) %o', 'bag', 3.7, -2.5, {k: 1}, 'extra');
                console.warn('careful');
                console.error(new TypeError('bad'));
                console.debug(document.body);
                console.info('%c styled', 'color: red');
                var cyclic = {}; cyclic.self = cyclic; console.log(cyclic);
                """);
        assertEquals(List.of(
                "INFO: plain 1 true null undefined",
                "INFO: {\"a\":[1,\"x\"]} [1,2]",
                "INFO: bag has 3 items (-2%) {\"k\":1} extra",
                "WARN: careful",
                "ERROR: TypeError: bad",
                "DEBUG: <body>",
                "INFO:  styled",
                "INFO: [object Object]"), page.host.logs);
    }

    @Test
    void storage() {
        Page page = new Page("""
                <script>
                localStorage.setItem('a', 1);
                localStorage.setItem('b', 'two');
                localStorage.removeItem('a');
                sessionStorage.setItem('s', 'x');
                </script>""");
        assertEquals("1 b two null s", page.eval("[localStorage.length, localStorage.key(0), localStorage.getItem('b'), "
                + "String(localStorage.getItem('a')), sessionStorage.key(0)].join(' ')"));
        assertTrue(page.eval("(function () { try { localStorage.setItem('big', 'x'.repeat(300000)) } catch (e) { return e.message } })()")
                .contains("QuotaExceededError"));
        page.run("localStorage.clear()");
        assertEquals("0", page.eval("localStorage.length"));
    }

    @Test
    void structuredCloneCopiesData() {
        Page page = new Page("<script>const original = {a: [1, {b: 2}]}; const copy = structuredClone(original);</script>");
        assertEquals("false 2", page.eval("[copy.a === original.a, copy.a[1].b].join(' ')"));
    }
}
