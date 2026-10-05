package dev.vellum.engine.layout;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.FontMetrics;
import dev.vellum.engine.host.FontSpec;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Chrome-generated layout fixtures from the Taffy project (MIT licensed, https://github.com/DioxusLabs/taffy,
 * {@code tests/xml/<category>/*__border_box_ltr.xml}), bundled per category in {@code src/test/resources/taffy/}.
 * Each fixture is a tree of divs whose attributes are CSS properties, with the layout Chrome produced. Fixtures
 * using features Vellum does not have (right-to-left, writing modes, floats, scrollbar gutters, the stretch and
 * content keywords, named grid lines, flex-wrap: balance, safe/unsafe alignment, baseline alignment in grid) are
 * left out, as are six known gaps: re-running column sizing for aspect-ratio items whose percentage heights depend
 * on the rows, grid placement of absolutely positioned descendants that are not direct children, and a percentage
 * width against Taffy's max-content viewport. As in Taffy's Chrome harness, every div defaults to
 * {@code display: flex}, text uses the Ahem font (every glyph 10px square, zero-width spaces as break
 * opportunities). The test root is a child of the body (usually absolutely positioned), or, when the fixture gives
 * a sized viewport, a child of an absolutely positioned "viewport" flex container ({@code align-items: start}) of
 * that size. Offsets are compared from each node's offsetParent, as Chrome reports them.
 */
class TaffyFixtureTest {
    @TestFactory
    Stream<DynamicTest> block() throws Exception {
        return fixtures("block");
    }

    @TestFactory
    Stream<DynamicTest> flex() throws Exception {
        return fixtures("flex");
    }

    @TestFactory
    Stream<DynamicTest> grid() throws Exception {
        return fixtures("grid");
    }

    private static Stream<DynamicTest> fixtures(String category) throws Exception {
        try (InputStream in = TaffyFixtureTest.class.getResourceAsStream("/taffy/" + category + ".xml")) {
            List<org.w3c.dom.Element> tests = children(DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(in).getDocumentElement());
            return tests.stream().map(test -> DynamicTest.dynamicTest(
                    test.getAttribute("name").replace("__border_box_ltr", ""), () -> run(test)));
        }
    }

    private static void run(org.w3c.dom.Element test) {
        boolean rounding = !"false".equals(test.getAttribute("use-rounding"));
        org.w3c.dom.Element viewport = child(test, "viewport");
        org.w3c.dom.Element input = children(child(test, "input")).get(0);
        org.w3c.dom.Element expected = children(child(test, "expectations")).get(0);

        TestDoc t = new TestDoc(new AhemHost());
        Element parent = t.body;
        String width = viewport.getAttribute("width"), height = viewport.getAttribute("height");
        if (!width.equals("max-content") || !height.equals("max-content")) {
            parent = t.div(t.body, "display: flex; position: absolute; align-items: start; justify-content: start; "
                    + "width: " + width + "; height: " + height);
        }
        Element rootElement = build(t, parent, input, true);
        t.layout(100_000, 100_000);
        compare(rootElement, expected, rounding, "root");
    }

    private static Element build(TestDoc t, Element parent, org.w3c.dom.Element node, boolean root) {
        StringBuilder css = new StringBuilder("display: flex; ");
        if (root) css.append("font-size: 10px; line-height: 1; ");
        var attrs = node.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            css.append(a.getNodeName()).append(": ").append(a.getNodeValue()).append("; ");
        }
        Element e = t.div(parent, css.toString());
        if (node.getTagName().equals("text")) t.text(e, node.getTextContent());
        for (org.w3c.dom.Element c : children(node)) build(t, e, c, false);
        return e;
    }

    private static void compare(Element e, org.w3c.dom.Element expected, boolean rounding, String path) {
        Box b = e.box;
        float x = 0, y = 0, w = 0, h = 0;
        if (b != null) {
            // Chrome reports offsets from the offsetParent: the nearest positioned ancestor (else the viewport).
            float ax = abs(b, true), ay = abs(b, false);
            Box parent = offsetParent(e);
            float px = parent == null ? 0 : abs(parent, true), py = parent == null ? 0 : abs(parent, false);
            if (rounding) {
                // Taffy's rounding: round absolute edges, so sizes and offsets snap consistently.
                x = Math.round(ax) - Math.round(px);
                y = Math.round(ay) - Math.round(py);
                w = Math.round(ax + b.width) - Math.round(ax);
                h = Math.round(ay + b.height) - Math.round(ay);
            } else {
                x = ax - px;
                y = ay - py;
                w = b.width;
                h = b.height;
            }
        }
        float tolerance = rounding ? 0.01f : 0.1f;
        String at = path + " " + b;
        assertEquals(Float.parseFloat(expected.getAttribute("x")), x, tolerance, at + " x");
        assertEquals(Float.parseFloat(expected.getAttribute("y")), y, tolerance, at + " y");
        assertEquals(Float.parseFloat(expected.getAttribute("width")), w, tolerance, at + " width");
        assertEquals(Float.parseFloat(expected.getAttribute("height")), h, tolerance, at + " height");
        List<org.w3c.dom.Element> kids = children(expected);
        List<Element> elements = e.children();
        assertEquals(kids.size(), elements.size(), path + " child count");
        for (int i = 0; i < kids.size(); i++) compare(elements.get(i), kids.get(i), rounding, path + "/" + i);
    }

    private static Box offsetParent(Element e) {
        for (Element p = e.parentElement(); p != null; p = p.parentElement()) {
            if (p.style.position.isPositioned()) return p.box;
        }
        return null;
    }

    /** Absolute position ignoring scroll offsets (as Taffy's absolute layout does). */
    private static float abs(Box b, boolean horizontal) {
        float v = 0;
        for (Box p = b; p != null; p = p.parent) v += horizontal ? p.x : p.y;
        return v;
    }

    private static org.w3c.dom.Element child(org.w3c.dom.Element e, String tag) {
        return (org.w3c.dom.Element) e.getElementsByTagName(tag).item(0);
    }

    private static List<org.w3c.dom.Element> children(org.w3c.dom.Element e) {
        List<org.w3c.dom.Element> out = new ArrayList<>();
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof org.w3c.dom.Element c) out.add(c);
        }
        return out;
    }

    /** The Ahem test font: every glyph is an em square; zero-width spaces have no width. */
    private static final class AhemHost extends TestHost {
        private final FontMetrics fonts = new FontMetrics() {
            @Override
            public float width(String text, FontSpec font) {
                float w = 0;
                for (int i = 0; i < text.length(); i++) if (text.charAt(i) != '​') w += font.size();
                return w;
            }

            @Override
            public float glyphHeight(FontSpec font) {
                return font.size();
            }

            @Override
            public float ascent(FontSpec font) {
                return font.size() * 0.8f;
            }
        };

        @Override
        public FontMetrics fonts() {
            return fonts;
        }
    }
}
