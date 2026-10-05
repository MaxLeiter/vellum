package dev.vellum.engine.script;

import dev.vellum.engine.anim.AnimationOptions;
import dev.vellum.engine.style.AnimationSpec;
import dev.vellum.engine.style.TimingFunction;
import dev.vellum.shadow.rhino.EcmaError;
import dev.vellum.shadow.rhino.Scriptable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The keyframe and option parsing behind {@code element.animate()} (the animation engine itself is another module). */
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
        assertEquals(AnimationOptions.of(300), parse("300", AnimationBindings::options));
        assertEquals(new AnimationOptions(200, 50, new TimingFunction.Steps(4, TimingFunction.Steps.Jump.START),
                        Float.POSITIVE_INFINITY, AnimationSpec.Direction.ALTERNATE_REVERSE, AnimationSpec.FillMode.FORWARDS),
                parse("{duration: 200, delay: 50, easing: 'steps(4, jump-start)', iterations: Infinity, "
                        + "direction: 'alternate-reverse', fill: 'forwards'}", AnimationBindings::options));
        assertEquals(new TimingFunction.CubicBezier(0.1f, 0.7f, 1f, 0.1f), AnimationBindings.easing(" cubic-bezier(0.1, 0.7, 1.0, 0.1) "));
    }

    @Test
    void animateReachesTheAnimationEngine() {
        Page page = Page.withScript("<div id=d></div>", "");
        // Until the animation engine lands its stub throws, which must still arrive as a catchable script error.
        assertEquals("true", page.eval("(function () { try { return document.getElementById('d').animate([{opacity: 0}, "
                + "{opacity: 1}], 100) instanceof Animation } catch (e) { return e.name === 'Error' } })()"));
    }
}
