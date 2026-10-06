package dev.vellum.engine.script;

import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The language features pages can rely on, run through the real sandbox. Vellum builds a patched Rhino (rhino/patches)
 * for the ones upstream lacks: per-iteration let and const bindings, spread arguments, classes, async functions.
 */
class LanguageConformanceTest {
    /** Each body runs as a function and must return true. */
    static final Map<String, String> FEATURES = new LinkedHashMap<>();

    static {
        // The probe that found the gaps.
        FEATURES.put("let per iteration", "var fs=[]; for (let i=0;i<3;i++) fs.push(()=>i); return fs[0]()===0 && fs[2]()===2");
        FEATURES.put("const in loop body", "var out=[]; for (let i=0;i<3;i++){ const x=i; out.push(()=>x);} return out.map(f=>f()).join()===\"0,1,2\"");
        FEATURES.put("for const of", "let s=0; for (const x of [1,2,3]) s+=x; return s===6");
        FEATURES.put("for const in", "let n=0; for (const k in {a:1,b:2}) n++; return n===2");
        FEATURES.put("spread call", "return Math.max(...[1,5,3])===5");
        FEATURES.put("class", "class A{constructor(x){this.x=x} get y(){return this.x+1} static z(){return 3}} return new A(1).y===2 && A.z()===3");
        FEATURES.put("extends/super", "class A{f(){return 1}} class B extends A{constructor(){super(); this.k=2} f(){return super.f()+this.k}} return new B().f()===3");
        FEATURES.put("fields", "class A{x=1; static y=2} return new A().x+A.y===3");
        FEATURES.put("async", "async function f(){ return await Promise.resolve(2) } return typeof f().then===\"function\"");

        // Loop bindings.
        FEATURES.put("const in loop body without closures", "var out=[]; for (let i=0;i<3;i++){ const x=i; out.push(x);} return out.join()==='0,1,2'");
        FEATURES.put("let without initializer is fresh", "var out=[]; for (let i=0;i<3;i++){ let x; if (i==1) x=5; out.push(x);} return out.join()===',5,'");
        FEATURES.put("for let of closures", "var fs=[]; for (let x of 'ab') fs.push(()=>x); return fs.map(f=>f()).join()==='a,b'");
        FEATURES.put("for const destructuring", "var fs=[]; for (const [k,v] of Object.entries({a:1,b:2})) fs.push(()=>k+v); return fs.map(f=>f()).join()==='a1,b2'");
        FEATURES.put("nested let loops", "var fs=[]; for (let i=0;i<2;i++) for (let j=0;j<2;j++) fs.push(()=>''+i+j); return fs.map(f=>f()).join()==='00,01,10,11'");
        FEATURES.put("const is block scoped", "const a=1; { const a=2; } return a===1");
        FEATURES.put("block bindings skip Object.prototype", "var valueOf=5; { let x=1; return valueOf+(()=>x)()===6 }");

        // Spread.
        FEATURES.put("spread mixed", "function f(){return [].slice.call(arguments).join()} return f(...[1,2],3,...new Set([4]))==='1,2,3,4'");
        FEATURES.put("spread method keeps this", "var o={k:1,m(a,b){return this.k+a+b}}; return o.m(...[2,3])===6");
        FEATURES.put("spread new", "function P(x,y){this.s=x+y} return new P(...[1,2]).s===3");

        // Classes.
        FEATURES.put("class expression name", "var A=class B{static me(){return B}}; return A.me()===A && A.name==='B'");
        FEATURES.put("methods not enumerable", "class A{m(){}} return Object.keys(A.prototype).length===0");
        FEATURES.put("extends Error", "class E extends Error{constructor(m){super(m);this.name='E'}} var e=new E('x'); return e instanceof E && e instanceof Error && e.message==='x' && String(e)==='E: x'");
        FEATURES.put("extends Map", "class M extends Map{inc(k){this.set(k,(this.get(k)||0)+1)}} var m=new M(); m.inc('a'); m.inc('a'); return m.get('a')===2");
        FEATURES.put("default derived constructor", "class A{constructor(a,b){this.v=a+b}} class B extends A{} return new B(1,2).v===3");
        FEATURES.put("super in object literal", "var b={f(){return 1}}; var o={__proto__:b, f(){return super.f()+1}}; return o.f()===2");
        FEATURES.put("static super", "class A{static s(){return 1}} class B extends A{static s(){return super.s()+1}} return B.s()===2");
        FEATURES.put("field arrows bind this", "class A{v=7; g=()=>this.v} var g=new A().g; return g()===7");
        FEATURES.put("static block", "class A{static{this.x=1}} return A.x===1");
        FEATURES.put("new.target", "var t; class A{constructor(){t=new.target}} class B extends A{} new B(); return t===B");
        FEATURES.put("class needs new", "class A{} try { A(); return false } catch (e) { return e instanceof TypeError }");
        FEATURES.put("this before super", "class A{} class B extends A{constructor(){this.x=1;super()}} try { new B(); return false } catch (e) { return e instanceof ReferenceError }");
        FEATURES.put("class body is strict", "class A{m(){ undeclaredName = 1 }} try { new A().m(); return false } catch (e) { return e instanceof ReferenceError }");
        FEATURES.put("class used before declaration throws", "try { new A(); return false } catch (e) { return e instanceof TypeError } class A{}");

        // Async functions.
        FEATURES.put("async arrow", "var f=async x=>x*2; return typeof f(1).then==='function'");
        FEATURES.put("async method", "class A{async m(){return 1}} var o={async n(){return 2}}; return typeof new A().m().then==='function' && typeof o.n().then==='function'");
    }

    static Stream<String> features() {
        return FEATURES.keySet().stream();
    }

    @ParameterizedTest
    @MethodSource("features")
    void feature(String name) {
        Page page = new TestHost().load("");
        assertEquals("true", page.eval("(function () {\n" + FEATURES.get(name) + "\n})()"), name);
    }

    @Test
    void awaitResolvesWithinTheEntryThatStartedIt() {
        Page page = new TestHost().load("""
                <script>
                var log = [];
                async function f() { log.push('a'); await null; log.push('c'); return 'd'; }
                f().then(v => log.push(v));
                log.push('b');
                </script>""");
        assertEquals("a,b,c,d", page.eval("log.join()"));
    }

    @Test
    void awaitOrderingFollowsThePromiseJobQueue() {
        Page page = new TestHost().load("""
                <script>
                var log = [];
                var p = Promise.resolve();
                (async () => { await p; log.push(1); await p; log.push(3); })();
                p.then(() => log.push(2));
                </script>""");
        assertEquals("1,2,3", page.eval("log.join()"));
    }

    @Test
    void awaitResumesInTheFrameLoop() {
        Page page = new TestHost().load("""
                <script>
                var log = [];
                const sleep = ms => new Promise(r => setTimeout(r, ms));
                (async () => { log.push('start'); await sleep(10); log.push('slept'); await null; log.push('done'); })();
                </script>""");
        assertEquals("start", page.eval("log.join()"));
        page.frame(5);
        assertEquals("start", page.eval("log.join()"));
        page.frame(20);
        assertEquals("start,slept,done", page.eval("log.join()"));
    }

    @Test
    void rejectionsReachCatchBlocks() {
        Page page = new TestHost().load("""
                <script>
                var log = [];
                async function load() { throw new Error('offline'); }
                (async () => { try { await load(); } catch (e) { log.push(e.message); } })();
                </script>""");
        assertEquals("offline", page.eval("log.join()"));
    }

    @Test
    void uncaughtAsyncErrorsAreReported() {
        Page page = new TestHost().recordErrors().load("""
                <script>
                (async () => { await null; throw new Error('lost'); })();
                </script>""");
        assertTrue(page.errors().contains("Uncaught (in promise): Error: lost"), page.errors());
    }

    @Test
    void topLevelAwaitIsASyntaxError() {
        Page page = new TestHost().recordErrors().load("<script>await null;</script>");
        assertTrue(page.errors().contains("SyntaxError") && page.errors().contains("await is only valid in async functions"),
                page.errors());
    }

    @Test
    void classesWorkAcrossScripts() {
        Page page = new TestHost().load("""
                <script>class Shape { area() { return 0; } }</script>
                <script>class Square extends Shape { constructor(s) { super(); this.s = s; } area() { return this.s ** 2; } }</script>""");
        assertEquals("9", page.eval("new Square(3).area()"));
    }
}
