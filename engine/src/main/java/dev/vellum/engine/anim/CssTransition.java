package dev.vellum.engine.anim;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.style.Prop;
import dev.vellum.engine.style.TransitionSpec;

import java.util.Objects;

/** A CSS transition of one property, with the state CSS Transitions 1 §3 keeps for retargeting and reversing. */
final class CssTransition extends Player {
    final Prop prop;
    final Object from, to;
    /** Where a transition reversing this one would end: the spec's reversing-adjusted start value. */
    private final Object reversingAdjustedStart;
    private final double shorteningFactor;

    CssTransition(AnimationEngine engine, Element element, Prop prop, Object from, Object to,
                  Object reversingAdjustedStart, double shorteningFactor, TransitionSpec spec, double now) {
        super(engine, element, Timing.transition(
                        spec.delayMs() < 0 ? spec.delayMs() * shorteningFactor : spec.delayMs(),
                        spec.durationMs() * shorteningFactor, spec.timing()),
                KeyframeEffect.between(prop, from, to));
        this.prop = prop;
        this.from = from;
        this.to = to;
        this.reversingAdjustedStart = reversingAdjustedStart;
        this.shorteningFactor = shorteningFactor;
        play(now);
    }

    /** A transition from {@code from} to {@code to}, or null when the values are equal or cannot interpolate. */
    static CssTransition start(AnimationEngine engine, Element element, Prop prop, Object from, Object to,
                               TransitionSpec spec, double now) {
        return changes(prop, from, to) ? new CssTransition(engine, element, prop, from, to, from, 1, spec, now) : null;
    }

    /**
     * Cancels this transition because the property's value became {@code after}, and returns the transition from
     * the current value that replaces it, or null if none should run ({@code spec} is null when transitions of the
     * property are disabled).
     */
    CssTransition retarget(Object after, TransitionSpec spec, double now) {
        Object current = currentValue(now);
        double progress = progress();
        cancel();
        if (spec == null || !changes(prop, current, after)) return null;
        if (!Objects.equals(after, reversingAdjustedStart)) {
            return new CssTransition(engine, element, prop, current, after, current, 1, spec, now);
        }
        // Reversing: go back over the distance covered so far, in proportionally less time.
        double factor = Math.min(Math.abs(progress * shorteningFactor + 1 - shorteningFactor), 1);
        return new CssTransition(engine, element, prop, current, after, to, factor, spec, now);
    }

    /** The value at {@code now}: interpolated while running, the end value once done. */
    Object currentValue(double now) {
        sample(now);
        double progress = progress();
        return Double.isNaN(progress) ? to : Interpolate.value(prop, from, to, (float) progress);
    }

    private static boolean changes(Prop prop, Object from, Object to) {
        return !Objects.equals(from, to) && Interpolate.canInterpolate(prop, from, to);
    }

    @Override
    String eventType(Kind kind) {
        return kind == Kind.ITERATION ? null : named("transition", kind);
    }

    @Override
    String eventName() {
        return prop.cssName;
    }
}
