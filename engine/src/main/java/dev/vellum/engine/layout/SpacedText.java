package dev.vellum.engine.layout;

/**
 * Text with {@code letter-spacing} or {@code word-spacing}, cut into the parts the host draws separately: each glyph
 * when letters are spaced, else each word with the spaces after it. {@code x[i]} is where part {@code i} starts,
 * relative to the text's start. Built once ({@link TextMeasure#spaced}) and drawn every frame without allocating.
 */
public record SpacedText(String[] parts, float[] x) {}
