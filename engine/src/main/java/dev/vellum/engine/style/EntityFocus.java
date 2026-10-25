package dev.vellum.engine.style;

/**
 * {@code -mc-entity-focus}: what of an {@code <entity>} fills its box. {@code BODY} fits the whole entity;
 * {@code CENTER} fits it the same way but {@code -mc-model-scale} zooms around its middle instead of its feet;
 * {@code EYES} frames its head and shoulders around the point at its eye height, which {@code object-position}
 * places.
 */
public enum EntityFocus {
    BODY, CENTER, EYES
}
