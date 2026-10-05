package dev.vellum.engine.anim;

import dev.vellum.engine.css.ResolvedKeyframe;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.PseudoElement;
import dev.vellum.engine.style.AnimationSpec;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.TimingFunction;

import java.util.List;
import java.util.Objects;

/**
 * A CSS animation: one {@code animation-name} entry playing its {@code @keyframes}. Keyframes are resolved against
 * the element's base style and re-resolved when that changes; the animation's timing function eases each keyframe
 * segment that has none of its own.
 */
final class CssAnimation extends Player {
    final String name;
    private List<ResolvedKeyframe> keyframes;
    /** The base style {@link #keyframes} were resolved against, and the easing the effect was built with. */
    private ComputedStyle keyframesBase;
    private TimingFunction easing;

    CssAnimation(AnimationEngine engine, Element element, PseudoElement pseudo, AnimationSpec spec,
                 ComputedStyle base, List<ResolvedKeyframe> keyframes, double now) {
        super(engine, element, pseudo, engine.effective(Timing.of(spec)), null);
        this.name = spec.name();
        this.keyframes = keyframes;
        this.keyframesBase = base;
        play(now);
        update(spec, base, now);
    }

    /** Applies the current animation properties and base style without restarting. */
    void update(AnimationSpec spec, ComputedStyle base, double now) {
        timing = engine.effective(Timing.of(spec));
        if (base != keyframesBase) {
            keyframes = engine.resolveKeyframes(element, pseudo, name, base);
            keyframesBase = base;
            effect = null;
        }
        if (effect == null || !Objects.equals(easing, spec.timing())) {
            easing = spec.timing();
            effect = KeyframeEffect.of(keyframes, easing);
        }
        if (spec.paused()) pause(now);
        else resume(now);
    }

    @Override
    String eventType(Kind kind) {
        return kind == Kind.RUN ? null : named("animation", kind);
    }

    @Override
    String eventName() {
        return name;
    }
}
