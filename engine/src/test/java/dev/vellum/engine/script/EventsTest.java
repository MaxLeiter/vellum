package dev.vellum.engine.script;

import dev.vellum.engine.event.Event;
import dev.vellum.engine.event.KeyboardEvent;
import dev.vellum.engine.event.Modifiers;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventsTest {
    private static final String TREE = "<div id=outer><button id=b>go</button></div>";

    private static Page page(String script) {
        return new TestHost().load(TREE + """
                <script>
                const outer = document.getElementById('outer'), b = document.getElementById('b');
                const log = [];
                """ + script + "</script>");
    }

    @Test
    void captureTargetAndBubbleOrder() {
        Page page = page("""
                outer.addEventListener('click', e => log.push('capture ' + e.eventPhase + ' ' + e.currentTarget.id), true);
                outer.addEventListener('click', e => log.push('bubble ' + e.eventPhase + ' ' + e.target.id));
                b.addEventListener('click', function (e) { log.push('target ' + e.eventPhase + ' ' + (this === b)) });
                b.click();
                """);
        assertEquals("capture 1 outer,target 2 true,bubble 3 b", page.eval("log.join()"));
    }

    @Test
    void onceAndRemovalByIdentity() {
        Page page = page("""
                const count = e => log.push('count');
                b.addEventListener('click', () => log.push('once'), {once: true});
                b.addEventListener('click', count);
                b.addEventListener('click', count); // duplicates are ignored
                b.addEventListener('click', count, true);
                b.removeEventListener('click', count, {capture: true});
                b.click();
                b.removeEventListener('click', count);
                b.click();
                """);
        assertEquals("once,count", page.eval("log.join()"));
    }

    @Test
    void stopPropagationAndPreventDefault() {
        Page page = page("""
                outer.addEventListener('click', () => log.push('outer'));
                b.addEventListener('click', e => { e.stopPropagation(); e.preventDefault(); log.push('first') });
                b.addEventListener('click', e => { e.stopImmediatePropagation(); log.push('second') });
                b.addEventListener('click', () => log.push('third'));
                var allowed = b.dispatchEvent(new Event('click', {bubbles: true, cancelable: true}));
                """);
        assertEquals("first,second false", page.eval("log.join() + ' ' + allowed"));
    }

    @Test
    void customEventsCarryDetail() {
        Page page = page("""
                outer.addEventListener('pick', e => log.push(e.detail.slot + ' ' + e.bubbles + ' ' + (e instanceof CustomEvent)));
                b.dispatchEvent(new CustomEvent('pick', {detail: {slot: 4}, bubbles: true}));
                b.dispatchEvent(new CustomEvent('pick')); // does not bubble
                """);
        assertEquals("4 true true", page.eval("log.join()"));
        assertEquals("null false", page.eval("[String(new CustomEvent('x').detail), new Event('x').bubbles].join(' ')"));
    }

    @Test
    void listenersShareOneEventObject() {
        Page page = page("""
                b.addEventListener('click', e => { e.note = 'hi'; window.first = e });
                b.addEventListener('click', e => log.push(e === first, e.note));
                """);
        page.byId("b").click();
        assertEquals("true,hi", page.eval("log.join()"));
    }

    @Test
    void inlineHandlersSeeThisAndEventAndCanCancel() {
        Page page = new TestHost().load("""
                <body><a id=a href=x onclick="window.seen = this.id + ' ' + event.type; return false">x</a></body>""");
        Event click = new Event("click", true, true);
        assertFalse(page.byId("a").dispatchEvent(click), "return false cancels");
        assertEquals("a click", page.eval("seen"));
    }

    @Test
    void handlerProperties() {
        Page page = page("""
                b.onclick = e => log.push('one ' + e.type);
                b.click();
                b.onclick = () => log.push('two');
                b.click();
                b.onclick = null;
                b.click();
                """);
        assertEquals("one click,two null", page.eval("log.join() + ' ' + b.onclick"));
    }

    @Test
    void listenerErrorsAreReportedAndDispatchContinues() {
        Page page = page("""
                b.addEventListener('click', () => { throw new Error('boom') });
                b.addEventListener('click', () => log.push('still runs'));
                """);
        page.host.failOnError = false;
        page.byId("b").click();
        assertEquals("still runs", page.eval("log.join()"));
        assertTrue(page.errors().contains("Error in 'click' listener: Error: boom"), page.errors());
    }

    @Test
    void keyboardEventFields() {
        Page page = page("document.addEventListener('keydown', e => log.push([e.key, e.code, e.repeat, e.shiftKey, e.ctrlKey, e instanceof KeyboardEvent].join(' ')))");
        page.byId("b").dispatchEvent(new KeyboardEvent("keydown", "Enter", "Enter", false, new Modifiers(true, false, false, false)));
        assertEquals("Enter Enter false true false true", page.eval("log.join()"));
    }

    @Test
    void windowListenersAreDocumentListeners() {
        Page page = page("window.addEventListener('ping', e => log.push('window ' + e.type)); document.dispatchEvent(new Event('ping'))");
        assertEquals("window ping", page.eval("log.join()"));
    }
}
