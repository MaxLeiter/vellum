package dev.vellum.engine.style;

/**
 * One entry of the {@code transition} list. {@code property} is a CSS property name, or {@code "all"}.
 * Durations and delays are in milliseconds.
 */
public record TransitionSpec(String property, float durationMs, float delayMs, TimingFunction timing) {
}
