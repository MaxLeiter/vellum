package dev.vellum.engine.dom;

import dev.vellum.engine.host.Host;

import java.util.Arrays;
import java.util.List;

/**
 * {@code <mc-text>}: Minecraft text as ordinary inline content, so it wraps and inherits CSS like any text.
 * <ul>
 *   <li>{@code <mc-text key="item.minecraft.diamond" args="a,b">}: a translation ({@link Host#translate}), with
 *       comma-separated arguments;</li>
 *   <li>{@code <mc-text json='{"text":"Hi","color":"gold"}'>}: a chat component, which the host formats
 *       ({@link Host#formatText}) into runs that become {@code <span style>} children.</li>
 * </ul>
 * The document expands an element when it is parsed or inserted, and again when one of these attributes changes,
 * so it works in templates and in content that scripts add.
 */
final class MinecraftText {
    static final String TAG = "mc-text";

    private MinecraftText() {}

    /** Whether changing {@code attribute} of {@code element} changes its text. */
    static boolean expandsOn(Element element, String attribute) {
        return element.tagName().equals(TAG) && (attribute.equals("key") || attribute.equals("args") || attribute.equals("json"));
    }

    /** Replaces the element's children with its text; false (children kept) when the host cannot format it. */
    static boolean expand(Document document, Element element) {
        List<Host.TextRun> runs = runs(document.host(), element);
        if (runs == null) return false;
        element.removeAllChildren();
        for (Host.TextRun run : runs) {
            if (run.text().isEmpty()) continue;
            Text text = document.createTextNode(run.text());
            if (run.css().isEmpty()) {
                element.appendChild(text);
            } else {
                Element span = document.createElement("span");
                span.setAttribute("style", run.css());
                span.appendChild(text);
                element.appendChild(span);
            }
        }
        return true;
    }

    private static List<Host.TextRun> runs(Host host, Element element) {
        String key = element.getAttribute("key");
        if (key != null) {
            String args = element.getAttribute("args");
            String[] values = args == null || args.isBlank() ? new String[0]
                    : Arrays.stream(args.split(",")).map(String::strip).toArray(String[]::new);
            return List.of(new Host.TextRun(host.translate(key.strip(), values), ""));
        }
        String json = element.getAttribute("json");
        return json == null ? null : host.formatText(json);
    }
}
