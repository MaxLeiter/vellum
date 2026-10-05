package dev.vellum.engine.layout;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Text;
import dev.vellum.engine.host.Host;
import dev.vellum.engine.testing.TestHost;

/**
 * Builds a styled document for layout tests without the CSS engine: every element's computed style comes from
 * {@link TestStyles}. {@code html}, {@code head} and {@code body} are styled as the UA sheet would.
 */
final class TestDoc {
    final Document doc;
    final Element html, body;

    TestDoc() {
        this(new TestHost());
    }

    TestDoc(Host host) {
        doc = Document.create(host, "test:x");
        html = doc.documentElement();
        body = doc.body();
        html.style = TestStyles.forTag("html", "", null);
        doc.head().style = TestStyles.forTag("head", "", html.style);
        body.style = TestStyles.forTag("body", "", html.style);
    }

    /** Appends a {@code tag} element styled by {@code css} to {@code parent}. */
    Element add(Element parent, String tag, String css) {
        Element e = doc.createElement(tag);
        parent.appendChild(e);
        e.style = TestStyles.forTag(tag, css, parent.style);
        return e;
    }

    Element div(Element parent, String css) {
        return add(parent, "div", css);
    }

    Text text(Element parent, String data) {
        return parent.appendChild(doc.createTextNode(data));
    }

    /** Gives {@code e} a ::before pseudo-element (the css must set {@code content}). */
    void before(Element e, String css) {
        e.beforeStyle = TestStyles.forTag("", css, e.style);
    }

    void after(Element e, String css) {
        e.afterStyle = TestStyles.forTag("", css, e.style);
    }

    TestDoc layout(float width, float height) {
        doc.setViewport(width, height, 1);
        doc.layoutEngine().layout();
        return this;
    }

    TestDoc layout() {
        return layout(320, 240);
    }
}
