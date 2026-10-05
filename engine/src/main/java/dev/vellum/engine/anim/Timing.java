package dev.vellum.engine.anim;

import dev.vellum.engine.style.AnimationSpec;
import dev.vellum.engine.style.AnimationSpec.Direction;
import dev.vellum.engine.style.AnimationSpec.FillMode;
import dev.vellum.engine.style.TimingFunction;

import java.util.Objects;

/**
 * The Web Animations timing model, shared by CSS transitions, CSS animations and scripted animations: maps an
 * animation's current time onto a phase, an iteration and an eased iteration progress. Times are in ms. There is no
 * end delay and no iteration start.
 *
 * @param easing applied to each whole iteration. Transitions and scripted animations use it; CSS animations apply
 *               their timing function per keyframe instead, so theirs is linear
 */
record Timing(double delay, double duration, double iterations, Direction direction, FillMode fill,
              TimingFunction easing) {
    enum Phase { BEFORE, ACTIVE, AFTER }

    /**
     * The timing at one moment.
     *
     * @param iteration the current iteration index, infinite once an infinite animation is finished, NaN when
     *                  {@code progress} is
     * @param progress  the directed, eased iteration progress (0..1, beyond for overshooting easings), or NaN when
     *                  the animation has no effect: outside its active interval and not filling
     */
    record Sample(Phase phase, double iteration, double progress) {
        boolean hasEffect() { return !Double.isNaN(progress); }
    }

    Timing {
        delay = Double.isFinite(delay) ? delay : 0;
        duration = duration > 0 ? duration : 0;
        iterations = iterations >= 0 ? iterations : 0;
        direction = Objects.requireNonNullElse(direction, Direction.NORMAL);
        fill = Objects.requireNonNullElse(fill, FillMode.NONE);
        easing = Objects.requireNonNullElse(easing, TimingFunction.LINEAR);
    }

    static Timing of(AnimationSpec spec) {
        return new Timing(spec.delayMs(), spec.durationMs(), spec.iterations(), spec.direction(), spec.fillMode(),
                TimingFunction.LINEAR);
    }

    static Timing of(AnimationOptions options) {
        return new Timing(options.delayMs(), options.durationMs(), options.iterations(), options.direction(),
                options.fill(), options.easing());
    }

    /** A transition: one iteration, filling backwards so the start value shows during the delay. */
    static Timing transition(double delay, double duration, TimingFunction easing) {
        return new Timing(delay, duration, 1, Direction.NORMAL, FillMode.BACKWARDS, easing);
    }

    /** This timing without delay or duration, so it ends as soon as it starts (reduced motion). */
    Timing instant() {
        return new Timing(0, 0, iterations, direction, fill, easing);
    }

    double activeDuration() {
        return duration == 0 || iterations == 0 ? 0 : duration * iterations; // avoids 0 × ∞
    }

    double endTime() {
        return Math.max(delay + activeDuration(), 0);
    }

    /** The elapsed time reported by start events. */
    double intervalStart() {
        return Math.max(Math.min(-delay, activeDuration()), 0);
    }

    /** The elapsed time reported by end events. */
    double intervalEnd() {
        return Math.max(Math.min(endTime() - delay, activeDuration()), 0);
    }

    /** The active time as if filling both ways, reported by cancel events. */
    double clampedActiveTime(double time) {
        return Math.max(Math.min(time - delay, activeDuration()), 0);
    }

    /**
     * Samples the timing at {@code time}. {@code backwards} is true while playing in reverse, which makes the start
     * edge belong to the before phase (so a reversed animation finishes without effect unless it fills backwards).
     */
    Sample sample(double time, boolean backwards) {
        double active = activeDuration();
        double end = endTime();
        double beforeEdge = Math.max(Math.min(delay, end), 0);
        double afterEdge = Math.max(Math.min(delay + active, end), 0);
        Phase phase = time < beforeEdge || backwards && time == beforeEdge ? Phase.BEFORE
                : time > afterEdge || !backwards && time == afterEdge ? Phase.AFTER : Phase.ACTIVE;
        double activeTime = switch (phase) {
            case BEFORE -> fills(FillMode.BACKWARDS) ? Math.max(time - delay, 0) : Double.NaN;
            case ACTIVE -> time - delay;
            // Without an end delay the after phase always sits at the end of the active interval.
            case AFTER -> fills(FillMode.FORWARDS) ? active : Double.NaN;
        };
        if (Double.isNaN(activeTime)) return new Sample(phase, Double.NaN, Double.NaN);

        double overall = duration == 0 ? (phase == Phase.BEFORE ? 0 : iterations) : activeTime / duration;
        double simple = Double.isInfinite(overall) ? 0 : overall % 1;
        // The end of an iteration shows its last frame, not the first frame of the next one.
        if (simple == 0 && phase != Phase.BEFORE && activeTime == active && iterations != 0) simple = 1;
        double iteration = phase == Phase.AFTER && Double.isInfinite(iterations) ? Double.POSITIVE_INFINITY
                : simple == 1 ? Math.floor(overall) - 1 : Math.floor(overall);
        boolean forwards = switch (direction) {
            case NORMAL -> true;
            case REVERSE -> false;
            case ALTERNATE, ALTERNATE_REVERSE -> {
                double d = direction == Direction.ALTERNATE ? iteration : iteration + 1;
                yield Double.isInfinite(d) || d % 2 == 0;
            }
        };
        return new Sample(phase, iteration, easing.apply((float) (forwards ? simple : 1 - simple)));
    }

    private boolean fills(FillMode mode) {
        return fill == mode || fill == FillMode.BOTH;
    }
}
