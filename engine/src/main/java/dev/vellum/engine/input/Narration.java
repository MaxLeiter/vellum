package dev.vellum.engine.input;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;

import java.util.List;

/**
 * What the page gives a narrator (Minecraft's, Ctrl+B): the element to read for the focus and for the pointer, and
 * what its live regions announce. The screen's title is {@link Document#title()}. Hosts ask; the engine speaks to no
 * one, and does no work for narration while no one asks.
 *
 * <p>An element reads as an {@link Accessible}: a role (its {@code role} attribute, else what its tag implies:
 * buttons, links, checkboxes, radios, sliders, text fields, selects, images, {@code <item>}, {@code <slot>}), a name,
 * a value and state, and a hint. Its name is the first of these that says something:
 * <ol>
 *   <li>{@code aria-label};</li>
 *   <li>the text of the elements {@code aria-labelledby} names;</li>
 *   <li>what the element provides: an image's {@code alt}, a button input's {@code value}, the item of an
 *       {@code <item>} or {@code <slot>}, a control's {@code <label>};</li>
 *   <li>its text content, whitespace collapsed and cut to about 100 characters (not for fields, images and slots);</li>
 *   <li>its own {@code title} (or the text of its {@code title-json});</li>
 *   <li>a text field's {@code placeholder}.</li>
 * </ol>
 * The hint is the text of {@code aria-describedby}, else the title that applies to the element when it is not the
 * name, so a button with a title reads its label, then its tooltip, as vanilla reads a widget's. Anything inside
 * {@code aria-hidden="true"} is never read.
 */
public final class Narration {
    /**
     * Text a live region announces. {@code interrupt}: the region is assertive ({@code aria-live="assertive"},
     * {@code role="alert"}), so it cuts off what the narrator is saying; otherwise it waits its turn.
     */
    public record Announcement(Element region, String text, boolean interrupt) {}

    private final Document document;
    private final Pointer pointer;
    private final FocusNavigator focus;
    private final LiveRegions live;

    Narration(Document document, Pointer pointer, FocusNavigator focus) {
        this.document = document;
        this.pointer = pointer;
        this.focus = focus;
        this.live = new LiveRegions(document);
    }

    /** The focused element as the narrator reads it, or null: nothing focused, or it is hidden or has nothing to say. */
    public Accessible focused() {
        return document.guard(() -> describe(document.focusedElement()), null);
    }

    /**
     * What the narrator reads for the pointer, or null: the nearest element, from the hovered one up, that is
     * interactive (a control, a link, a slot, a tab stop, an {@code <item tooltip>}) or has a title or an
     * {@code aria-label}. Plain text and decoration under the pointer are not read, nor is anything past an element
     * with an empty title, which says "no tooltip here". Hosts wait for the pointer to rest before reading it, as
     * Minecraft waits for its widgets.
     */
    public Accessible hovered() {
        return document.guard(() -> describe(Accessibility.hoverTarget(pointer.known() ? pointer.hoverTarget() : null)),
                null);
    }

    /** {@code element} as the narrator reads it, wherever the pointer and focus are; null when hidden or nameless. */
    public Accessible describe(Element element) {
        return element == null || element.ownerDocument() != document ? null
                : document.guard(() -> Accessibility.describe(element, focus.order()), null);
    }

    /**
     * What the live regions announce since the last call, in document order: a region's text when it changed (a log's
     * new entries), and the text of regions the page did not have at the last call, which on the first call is all of
     * them (a log, its last entry). Hosts call it once a frame while a narrator listens.
     */
    public List<Announcement> announcements() {
        return document.guard(live::poll, List.of());
    }
}
