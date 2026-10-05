package dev.vellum.engine.html;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Node;
import dev.vellum.engine.dom.Text;

import java.util.Map;

/** Serialises nodes back to HTML (innerHTML / outerHTML), escaping as the HTML serialisation algorithm does. */
public final class HtmlSerializer {
    private HtmlSerializer() {}

    public static String innerHTML(Node node) {
        StringBuilder sb = new StringBuilder();
        boolean raw = node instanceof Element e && (e.tagName().equals("script") || e.tagName().equals("style"));
        for (Node c : node.childNodes()) write(sb, c, raw);
        return sb.toString();
    }

    public static String outerHTML(Node node) {
        StringBuilder sb = new StringBuilder();
        write(sb, node, false);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Node node, boolean rawText) {
        if (node instanceof Text t) {
            if (rawText) sb.append(t.data());
            else escape(sb, t.data(), false);
            return;
        }
        if (!(node instanceof Element e)) {
            for (Node c : node.childNodes()) write(sb, c, rawText);
            return;
        }
        String tag = e.tagName();
        sb.append('<').append(tag);
        for (Map.Entry<String, String> a : e.attributes().entrySet()) {
            sb.append(' ').append(a.getKey());
            sb.append("=\"");
            escape(sb, a.getValue(), true);
            sb.append('"');
        }
        sb.append('>');
        if (HtmlParser.VOID.contains(tag)) return;
        boolean raw = tag.equals("script") || tag.equals("style");
        for (Node c : e.childNodes()) write(sb, c, raw);
        sb.append("</").append(tag).append('>');
    }

    private static void escape(StringBuilder sb, String s, boolean attribute) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case ' ' -> sb.append("&nbsp;");
                case '<' -> sb.append(attribute ? "<" : "&lt;");
                case '>' -> sb.append(attribute ? ">" : "&gt;");
                case '"' -> sb.append(attribute ? "&quot;" : "\"");
                default -> sb.append(c);
            }
        }
    }
}
