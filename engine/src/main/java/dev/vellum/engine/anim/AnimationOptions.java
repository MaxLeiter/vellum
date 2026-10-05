package dev.vellum.engine.anim;

import dev.vellum.engine.style.AnimationSpec;
import dev.vellum.engine.style.TimingFunction;

/** Options for {@link AnimationEngine#animate} ({@code element.animate(keyframes, options)}). */
public record AnimationOptions(float durationMs, float delayMs, TimingFunction easing, float iterations,
                               AnimationSpec.Direction direction, AnimationSpec.FillMode fill) {
    public static AnimationOptions of(float durationMs) {
        return new AnimationOptions(durationMs, 0, TimingFunction.LINEAR, 1, AnimationSpec.Direction.NORMAL,
                AnimationSpec.FillMode.NONE);
    }
}
