package dev.vellum.engine.html;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Node;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A forgiving HTML parser. It is not the HTML5 tree-construction algorithm, but it handles what UI authors write:
 * implied {@code html}/{@code head}/{@code body}, void elements, self-closing syntax on any element
 * ({@code <slot index="0"/>}), raw-text {@code <script>}/{@code <style>}, RCDATA {@code <textarea>}/{@code <title>},
 * character references, comments, unquoted and boolean attributes, implied end tags for {@code p}, {@code li},
 * {@code dt}/{@code dd}, {@code option}, and stray end tags (ignored).
 */
public final class HtmlParser {
    static final Set<String> VOID = Set.of("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta",
            "source", "track", "wbr");
    private static final Set<String> RAW_TEXT = Set.of("script", "style");
    private static final Set<String> RCDATA = Set.of("textarea", "title");
    private static final Set<String> HEAD_ELEMENTS = Set.of("base", "link", "meta", "style", "title", "script", "template");
    /** Start tags that close an open {@code <p>}. */
    private static final Set<String> CLOSES_P = Set.of("address", "article", "aside", "blockquote", "details", "dialog",
            "div", "dl", "fieldset", "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6",
            "header", "hgroup", "hr", "main", "menu", "nav", "ol", "p", "pre", "section", "table", "ul");

    private final Document doc;
    private final String src;
    private int pos;
    private final List<Element> stack = new ArrayList<>();
    private final Node root;
    private final boolean fullDocument;
    private Element html, head, body;

    private HtmlParser(Document doc, String src, Node root, boolean fullDocument) {
        this.doc = doc;
        this.src = src == null ? "" : src;
        this.root = root;
        this.fullDocument = fullDocument;
    }

    /** Parses a full document into {@code doc}, which must be empty. Always produces html, head and body. */
    public static void parseInto(Document doc, String html) {
        HtmlParser p = new HtmlParser(doc, html, doc, true);
        p.run();
    }

    /** Parses a fragment (for innerHTML); the returned nodes are detached and owned by {@code doc}. */
    public static List<Node> parseFragment(Document doc, String html) {
        Element container = doc.createElement("template");
        HtmlParser p = new HtmlParser(doc, html, container, false);
        p.run();
        // Scripts created by fragment parsing (innerHTML, v-html) never run, as in browsers: server-sent markup
        // must not be able to smuggle code past the page's own scripts.
        for (Element script : container.getElementsByTagName("script")) script.controlState = Boolean.TRUE;
        List<Node> out = new ArrayList<>(container.childNodes());
        for (Node n : out) container.removeChild(n);
        return out;
    }

    // ---- Tree building ----

    private Node current() {
        if (!stack.isEmpty()) return stack.get(stack.size() - 1);
        if (fullDocument) return ensureBody();
        return root;
    }

    private Element ensureHtml() {
        if (html == null) {
            html = doc.createElement("html");
            doc.appendChild(html);
        }
        return html;
    }

    private Element ensureHead() {
        if (head == null) {
            head = doc.createElement("head");
            Element h = ensureHtml();
            h.insertBefore(head, h.firstChild());
        }
        return head;
    }

    private Element ensureBody() {
        ensureHead();
        if (body == null) {
            body = doc.createElement("body");
            ensureHtml().appendChild(body);
            stack.clear();
            stack.add(body);
        }
        return body;
    }

    private void run() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '<' && pos + 1 < src.length()) {
                char n = src.charAt(pos + 1);
                if (n == '!') { markupDeclaration(); continue; }
                if (n == '/') { if (endTag()) continue; }
                else if (n == '?') { skipUntil(">"); continue; }
                else if (Character.isLetter(n)) { startTag(); continue; }
            }
            text();
        }
        if (fullDocument) {
            ensureBody();
        }
    }

    private void text() {
        int start = pos;
        pos++;
        while (pos < src.length()) {
            if (src.charAt(pos) == '<' && pos + 1 < src.length()) {
                char n = src.charAt(pos + 1);
                if (n == '!' || n == '/' || n == '?' || Character.isLetter(n)) break;
            }
            pos++;
        }
        appendText(Entities.decode(src.substring(start, pos), false));
    }

    private void appendText(String text) {
        if (text.isEmpty()) return;
        if (fullDocument && body == null && stack.isEmpty()) {
            // Before <body>: whitespace is dropped; other text starts the body.
            if (text.isBlank()) return;
        }
        Node parent = current();
        Node last = parent.lastChild();
        if (last instanceof dev.vellum.engine.dom.Text t) {
            t.setData(t.data() + text);
        } else {
            parent.appendChild(doc.createTextNode(text));
        }
    }

    private void markupDeclaration() {
        if (src.startsWith("<!--", pos)) {
            int end = src.indexOf("-->", pos + 4);
            pos = end < 0 ? src.length() : end + 3;
        } else if (src.startsWith("<![CDATA[", pos)) {
            int end = src.indexOf("]]>", pos + 9);
            String data = src.substring(pos + 9, end < 0 ? src.length() : end);
            pos = end < 0 ? src.length() : end + 3;
            appendText(data);
        } else {
            skipUntil(">"); // <!DOCTYPE ...>
        }
    }

    private void skipUntil(String s) {
        int end = src.indexOf(s, pos);
        pos = end < 0 ? src.length() : end + s.length();
    }

    private void startTag() {
        pos++; // <
        String name = readName().toLowerCase();
        List<String[]> attrs = new ArrayList<>();
        boolean selfClosing = false;
        while (pos < src.length()) {
            skipWhitespace();
            if (pos >= src.length()) break;
            char c = src.charAt(pos);
            if (c == '>') { pos++; break; }
            if (c == '/') {
                pos++;
                if (pos < src.length() && src.charAt(pos) == '>') { selfClosing = true; pos++; break; }
                continue;
            }
            String attrName = readAttrName();
            if (attrName.isEmpty()) { pos++; continue; }
            skipWhitespace();
            String value = "";
            if (pos < src.length() && src.charAt(pos) == '=') {
                pos++;
                skipWhitespace();
                value = readAttrValue();
            }
            attrs.add(new String[] {attrName.toLowerCase(), value});
        }

        if (fullDocument && handleStructural(name, attrs)) return;

        Element el = doc.createElement(name);
        for (String[] a : attrs) if (!el.hasAttribute(a[0])) el.setAttribute(a[0], a[1]);

        if (fullDocument && body == null && HEAD_ELEMENTS.contains(name) && stack.isEmpty()) {
            ensureHead().appendChild(el);
            if (RAW_TEXT.contains(name) || RCDATA.contains(name)) readRawText(el, name);
            else if (!VOID.contains(name) && !selfClosing && name.equals("template")) {
                stack.add(el);
            }
            return;
        }

        closeImplied(name);
        current().appendChild(el);
        if (VOID.contains(name) || selfClosing) return;
        if (RAW_TEXT.contains(name) || RCDATA.contains(name)) {
            readRawText(el, name);
            return;
        }
        stack.add(el);
    }

    /** html, head and body tags: merge attributes into the implied elements. Returns true if handled. */
    private boolean handleStructural(String name, List<String[]> attrs) {
        switch (name) {
            case "html" -> {
                Element h = ensureHtml();
                for (String[] a : attrs) if (!h.hasAttribute(a[0])) h.setAttribute(a[0], a[1]);
                return true;
            }
            case "head" -> {
                Element h = ensureHead();
                for (String[] a : attrs) if (!h.hasAttribute(a[0])) h.setAttribute(a[0], a[1]);
                return true;
            }
            case "body" -> {
                Element b = ensureBody();
                for (String[] a : attrs) if (!b.hasAttribute(a[0])) b.setAttribute(a[0], a[1]);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void closeImplied(String name) {
        if (CLOSES_P.contains(name)) closeIfOpen("p", Set.of("button", "td", "th", "li", "dd", "dt"));
        switch (name) {
            case "li" -> closeIfOpen("li", Set.of("ul", "ol", "menu"));
            case "dt", "dd" -> { closeIfOpen("dt", Set.of("dl")); closeIfOpen("dd", Set.of("dl")); }
            case "option" -> closeIfOpen("option", Set.of("select", "datalist"));
            case "optgroup" -> { closeIfOpen("option", Set.of("select")); closeIfOpen("optgroup", Set.of("select")); }
            default -> { }
        }
    }

    /** Pops up to and including the nearest open {@code tag}, unless a {@code boundary} element is closer. */
    private void closeIfOpen(String tag, Set<String> boundaries) {
        for (int i = stack.size() - 1; i >= 0; i--) {
            String t = stack.get(i).tagName();
            if (t.equals(tag)) {
                while (stack.size() > i) stack.remove(stack.size() - 1);
                return;
            }
            if (boundaries.contains(t) || t.equals("body") || t.equals("template")) return;
        }
    }

    private boolean endTag() {
        int save = pos;
        pos += 2;
        String name = readName().toLowerCase();
        if (name.isEmpty()) {
            pos = save;
            return false; // "</" followed by junk is text
        }
        skipUntil(">");
        if (fullDocument && (name.equals("html") || name.equals("body") || name.equals("head"))) return true;
        if (name.equals("br")) { // </br> is treated as <br>
            current().appendChild(doc.createElement("br"));
            return true;
        }
        if (name.equals("p") && !hasOpen("p")) { // </p> without <p> makes an empty paragraph
            current().appendChild(doc.createElement("p"));
            return true;
        }
        for (int i = stack.size() - 1; i >= 0; i--) {
            if (stack.get(i).tagName().equals(name)) {
                while (stack.size() > i) stack.remove(stack.size() - 1);
                if (fullDocument && stack.isEmpty() && body != null) stack.add(body);
                return true;
            }
        }
        return true; // stray end tag: ignored
    }

    private boolean hasOpen(String tag) {
        for (Element e : stack) if (e.tagName().equals(tag)) return true;
        return false;
    }

    private void readRawText(Element el, String name) {
        String close = "</" + name;
        int end = indexOfIgnoreCase(src, close, pos);
        String text = src.substring(pos, end < 0 ? src.length() : end);
        if (RCDATA.contains(name)) text = Entities.decode(text, false);
        if (name.equals("textarea") && text.startsWith("\n")) text = text.substring(1);
        if (!text.isEmpty()) el.appendChild(doc.createTextNode(text));
        if (end < 0) {
            pos = src.length();
        } else {
            pos = end;
            skipUntil(">");
        }
    }

    private static int indexOfIgnoreCase(String s, String needle, int from) {
        int n = needle.length();
        for (int i = from; i + n <= s.length(); i++) {
            if (s.regionMatches(true, i, needle, 0, n)) return i;
        }
        return -1;
    }

    // ---- Lexing helpers ----

    private String readName() {
        int start = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isWhitespace(c) || c == '/' || c == '>') break;
            pos++;
        }
        return src.substring(start, pos);
    }

    private String readAttrName() {
        int start = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isWhitespace(c) || c == '/' || c == '>' || c == '=') break;
            if ((c == '"' || c == '\'' || c == '<') && pos > start) break;
            pos++;
        }
        return src.substring(start, pos);
    }

    private String readAttrValue() {
        if (pos >= src.length()) return "";
        char q = src.charAt(pos);
        if (q == '"' || q == '\'') {
            int end = src.indexOf(q, pos + 1);
            String raw = src.substring(pos + 1, end < 0 ? src.length() : end);
            pos = end < 0 ? src.length() : end + 1;
            return Entities.decode(raw, true);
        }
        int start = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isWhitespace(c) || c == '>') break;
            pos++;
        }
        return Entities.decode(src.substring(start, pos), true);
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }
}
