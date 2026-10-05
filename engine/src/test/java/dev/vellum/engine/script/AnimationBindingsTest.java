package dev.vellum.engine.script;

import dev.vellum.engine.anim.Timing;
import dev.vellum.engine.style.AnimationSpec;
import dev.vellum.engine.style.TimingFunction;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import dev.vellum.shadow.rhino.EcmaError;
import dev.vellum.shadow.rhino.Scriptable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@code element.animate()}: keyframe and option parsing, and animations running on the document's animation engine. */
class AnimationBindingsTest {
    /** Evaluates {@code js} in a bare sandboxed scope and applies {@code parse} to the result. */
    private static <T> T parse(String js, Function<Object, T> parse) {
        return Sandbox.run(cx -> {
            Scriptable scope = cx.initSafeStandardObjects();
            return parse.apply(cx.evaluateString(scope, "(" + js + ")", "test", 1, null));
        });
    }

    @Test
    void arrayFormDistributesMissingOffsets() {
        List<AnimationBindings.Keyframe> frames = parse(
                "[{opacity: 0}, {opacity: 0.2}, {opacity: 0.5, offset: 0.8, easing: 'ease-in'}, {opacity: 0.7}, {backgroundColor: 'red'}]",
                AnimationBindings::keyframes);
        assertEquals(List.of(0.0, 0.4, 0.8, 0.9, 1.0), frames.stream().map(AnimationBindings.Keyframe::offset).toList());
        assertEquals("opacity: 0.5", frames.get(2).declarations());
        assertEquals(TimingFunction.EASE_IN, frames.get(2).easing());
        assertEquals("background-color: red", frames.get(4).declarations());
    }

    @Test
    void offsetsMustBeOrdered() {
        assertEquals("TypeError", parse("[{opacity: 0, offset: 0.6}, {opacity: 1, offset: 0.2}]", spec -> {
            try {
                AnimationBindings.keyframes(spec);
                return "accepted";
            } catch (EcmaError e) {
                return e.getName();
            }
        }));
    }

    @Test
    void singleKeyframeIsTheEnd() {
        List<AnimationBindings.Keyframe> frames = parse("[{transform: 'scale(2)'}]", AnimationBindings::keyframes);
        assertEquals(1.0, frames.getFirst().offset());
    }

    @Test
    void propertyIndexedFormSpacesEachPropertyEvenly() {
        List<AnimationBindings.Keyframe> frames = parse("{opacity: [0, 1], marginLeft: ['0px', '4px', '8px'], easing: 'ease-out'}",
                AnimationBindings::keyframes);
        assertEquals(List.of(0.0, 0.5, 1.0), frames.stream().map(AnimationBindings.Keyframe::offset).toList());
        assertEquals(Map.of("opacity", "0", "margin-left", "0px"), frames.get(0).properties());
        assertEquals(Map.of("margin-left", "4px"), frames.get(1).properties());
        assertEquals(TimingFunction.EASE_OUT, frames.get(1).easing());
    }

    @Test
    void options() {
        assertEquals(Timing.of(300), parse("300", AnimationBindings::options));
        assertEquals(new Timing(50, 200, Double.POSITIVE_INFINITY, AnimationSpec.Direction.ALTERNATE_REVERSE,
                        AnimationSpec.FillMode.FORWARDS, new TimingFunction.Steps(4, TimingFunction.Steps.Jump.START)),
                parse("{duration: 200, delay: 50, easing: 'steps(4, jump-start)', iterations: Infinity, "
                        + "direction: 'alternate-reverse', fill: 'forwards'}", AnimationBindings::options));
        assertEquals(new TimingFunction.CubicBezier(0.1f, 0.7f, 1f, 0.1f), AnimationBindings.easing(" cubic-bezier(0.1, 0.7, 1.0, 0.1) "));
    }

    @Test
    void easingsParseAsInCss() {
        assertEquals(TimingFunction.EASE_IN, AnimationBindings.easing("Ease-In"));
        assertEquals(new TimingFunction.Steps(2, TimingFunction.Steps.Jump.NONE), AnimationBindings.easing("steps(2, jump-none)"));
        assertEquals(new TimingFunction.CubicBezier(0.5f, 0, 0.5f, 1), AnimationBindings.easing("cubic-bezier(calc(1 / 2), 0, .5, 1)"));
        for (String invalid : List.of("cubic-bezier(2, 0, 0, 1)", "steps(1, jump-none)", "steps(0)", "ease-in 1", "bounce")) {
            assertThrows(EcmaError.class, () -> AnimationBindings.easing(invalid), invalid);
        }
    }

    @Test
    void animateRunsOnTheAnimationEngine() {
        Page page = new TestHost().load("""
                <div id=d style="opacity: 0.5"></div>
                <script>
                const log = [];
                const d = document.getElementById('d');
                const a = d.animate([{opacity: 0}, {opacity: 1}], {duration: 100, fill: 'forwards'});
                a.onfinish = e => log.push('onfinish ' + e.type);
                a.finished.then(x => log.push('finished ' + (x === a)));
                </script>""");
        // Started by the load's first frame, at t = 0.
        assertEquals("true running", page.eval("(a instanceof Animation) + ' ' + a.playState"));
        assertEquals(0, page.byId("d").style.opacity, 1e-4);
        page.frame(50);
        assertEquals(0.5, page.byId("d").style.opacity, 1e-4);
        assertEquals("0.5 50", page.eval("getComputedStyle(d).opacity + ' ' + a.currentTime"));
        page.frame(100);
        assertEquals(1, page.byId("d").style.opacity, 1e-4, "fills forwards");
        assertEquals("finished onfinish finish,finished true", page.eval("a.playState + ' ' + log.join()"));
    }

    @Test
    void cancellingRejectsFinishedAndRemovesTheEffect() {
        Page page = new TestHost().load("""
                <div id=d style="opacity: 0.5"></div>
                <script>
                const log = [];
                const a = document.getElementById('d').animate({opacity: [0, 1]}, 100);
                a.oncancel = () => log.push('oncancel');
                a.finished.catch(e => log.push(e.name));
                </script>""");
        page.frame(0);
        page.frame(50);
        page.run("a.cancel()");
        page.frame(60);
        assertEquals(0.5, page.byId("d").style.opacity, 1e-4);
        assertEquals("idle oncancel,AbortError", page.eval("a.playState + ' ' + log.join()"));
    }
}
