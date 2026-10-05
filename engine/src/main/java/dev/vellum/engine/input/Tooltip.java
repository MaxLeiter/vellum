package dev.vellum.engine.input;

import dev.vellum.engine.dom.Element;

/**
 * An advisory tooltip for the host to show at the pointer ({@link InputHandler#tooltip()}): the {@code title}
 * attribute of {@code element}, as plain text with {@code '\n'} line breaks, and/or its {@code title-json}
 * attribute, a Minecraft chat component in JSON (formatted like {@code <mc-text json>}). At least one is non-null;
 * hosts that can read {@code json} prefer it and fall back to {@code text}. ({@code x}, {@code y}) is the pointer in
 * viewport px.
 */
public record Tooltip(Element element, String text, String json, float x, float y) {}
