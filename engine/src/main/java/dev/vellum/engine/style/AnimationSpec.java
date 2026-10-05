package dev.vellum.engine.style;

/**
 * One entry of the {@code animation} list, referring to an {@code @keyframes} rule by name.
 * {@code iterations} is {@link Float#POSITIVE_INFINITY} for {@code infinite}.
 */
public record AnimationSpec(String name, float durationMs, float delayMs, TimingFunction timing, float iterations,
                            Direction direction, FillMode fillMode, boolean paused) {
    public enum Direction { NORMAL, REVERSE, ALTERNATE, ALTERNATE_REVERSE }
    public enum FillMode { NONE, FORWARDS, BACKWARDS, BOTH }
}
