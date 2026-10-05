package dev.vellum.engine.input;

import dev.vellum.engine.dom.Element;

/**
 * An advisory tooltip for the host to show at the pointer ({@link InputHandler#tooltip()}). Its lines come from the
 * {@code title} attribute of {@code element}, as plain text with {@code '\n'} line breaks, and/or its
 * {@code title-json} attribute, a Minecraft chat component in JSON (formatted like {@code <mc-text json>}); hosts that
 * can read {@code json} prefer it and fall back to {@code text}. ({@code x}, {@code y}) is the pointer in viewport px.
 *
 * <p>When {@code content} is set, the pointer is on an element whose replaced content shows a tooltip of its own
 * ({@link dev.vellum.engine.host.ReplacedContent#showsTooltip}, an {@code <item tooltip>}): the host shows that
 * tooltip with the title's lines after its own, in one box. {@code element}, {@code text} and {@code json} are then
 * null when no title applies. Otherwise {@code element} is set and at least one of {@code text} and {@code json} is.
 *
 * @param wrap whether the title's lines wrap at the host's tooltip width (vanilla's 170 px for widget tooltips);
 *             false for lines after a content's tooltip, which never wrap, and for a title with {@code title-nowrap}.
 *             Lines always break at {@code '\n'}.
 */
public record Tooltip(Element element, String text, String json, float x, float y, Element content, boolean wrap) {}
