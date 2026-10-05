package dev.vellum.engine.replaced;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.Host;
import dev.vellum.engine.host.ReplacedContent;

import java.util.Map;
import java.util.function.Function;

/**
 * Which elements are replaced, and the content each gets: the engine's {@code img}, {@code sprite} and
 * {@code canvas}, then the host's own ({@link Host#replacedElements}). An element is replaced exactly when this
 * creates content for it. One per document.
 */
public final class ReplacedElements {
    private static final Map<String, Function<Element, ReplacedContent>> ENGINE =
            Map.of("img", ImageContent::new, "sprite", ImageContent::new, "canvas", CanvasContent::new);

    private final Map<String, Function<Element, ReplacedContent>> host;

    public ReplacedElements(Host host) {
        this.host = Map.copyOf(host.replacedElements());
    }

    /** New content for {@code element}, or null when its tag is not a replaced element. */
    public ReplacedContent create(Element element) {
        Function<Element, ReplacedContent> factory = ENGINE.get(element.tagName());
        if (factory == null) factory = host.get(element.tagName());
        return factory == null ? null : factory.apply(element);
    }
}
