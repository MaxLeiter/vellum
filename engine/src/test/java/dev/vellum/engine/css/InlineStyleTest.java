package dev.vellum.engine.css;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.style.Length;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import static dev.vellum.engine.css.InlineStyle.cssText;
import static dev.vellum.engine.css.InlineStyle.getPropertyPriority;
import static dev.vellum.engine.css.InlineStyle.getPropertyValue;
import static dev.vellum.engine.css.InlineStyle.item;
import static dev.vellum.engine.css.InlineStyle.length;
import static dev.vellum.engine.css.InlineStyle.removeProperty;
import static dev.vellum.engine.css.InlineStyle.setCssText;
import static dev.vellum.engine.css.InlineStyle.setProperty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class InlineStyleTest {
    private static Element element(String style) {
        return new TestHost().load("<div id=t" + (style == null ? "" : " style='" + style + "'") + "></div>").byId("t");
    }

    @Test
    void readsDeclaredValues() {
        Element e = element("color: red; MARGIN: 1px 2px; --Gap: 4px ; width: 5px !important; height: nope");
        assertEquals("red", getPropertyValue(e, "color"));
        assertEquals("1px 2px", getPropertyValue(e, "margin"));
        assertEquals("2px", getPropertyValue(e, "margin-left"), "a longhand's part of a shorthand");
        assertEquals("4px", getPropertyValue(e, "--Gap"));
        assertEquals("", getPropertyValue(e, "--gap"), "custom properties are case-sensitive");
        assertEquals("important", getPropertyPriority(e, "width"));
        assertEquals("", getPropertyPriority(e, "color"));
        assertEquals("", getPropertyValue(e, "height"), "invalid declarations are dropped");
        assertEquals("", getPropertyValue(e, "padding"));
    }

    @Test
    void writesKeepOrderAndUpdateInPlace() {
        Element e = element("color: red; width: 5px");
        setProperty(e, "color", "blue", "");
        setProperty(e, "height", "3px", "important");
        assertEquals("color: blue; width: 5px; height: 3px !important;", cssText(e));
        assertEquals("color: blue; width: 5px; height: 3px !important;", e.getAttribute("style"));
        setProperty(e, "width", "bogus", "");
        assertEquals("5px", getPropertyValue(e, "width"), "invalid values are ignored");
        setProperty(e, "width", "", "");
        assertEquals("color: blue; height: 3px !important;", cssText(e), "an empty value removes");
    }

    @Test
    void shorthandsAndLonghandsInteract() {
        Element e = element("margin-top: 1px; padding: 2px");
        setProperty(e, "margin", "3px 4px", "");
        assertEquals("padding: 2px; margin: 3px 4px;", cssText(e), "a shorthand replaces its earlier longhands");
        setProperty(e, "margin-left", "9px", "");
        assertEquals("9px", getPropertyValue(e, "margin-left"));
        assertEquals("3px 4px 3px 9px", getPropertyValue(e, "margin"), "rebuilt from longhands once overridden");
        assertEquals("3px", removeProperty(e, "margin-top"));
        assertEquals("padding: 2px; margin-right: 4px; margin-bottom: 3px; margin-left: 9px;", cssText(e),
                "a partly removed shorthand splits into its remaining longhands");
        assertEquals("", getPropertyValue(e, "margin"));
        assertEquals("2px", removeProperty(e, "padding"));
        assertEquals("", removeProperty(e, "padding"));
    }

    @Test
    void lengthAndItemListLonghands() {
        Element e = element("--x: 1; border-radius: 2px; color: red; color: blue");
        assertEquals(6, length(e));
        assertEquals("--x", item(e, 0));
        assertEquals("border-top-left-radius", item(e, 1));
        assertEquals("color", item(e, 5));
        assertEquals("", item(e, 6));
    }

    @Test
    void cssTextRoundTripsAndDrivesTheCascade() {
        Element e = element(null);
        setCssText(e, "width: 10px;color: red; nonsense: 1; transition: opacity 1s");
        assertEquals("width: 10px; color: red; transition: opacity 1s;", cssText(e));
        Document doc = e.ownerDocument();
        doc.flushStyle();
        assertEquals(Length.px(10), e.baseStyle.width);
        assertEquals(1, e.baseStyle.transitions.size());
        setCssText(e, "");
        assertNull(e.getAttribute("style"));
        doc.flushStyle();
        assertEquals(Length.AUTO, e.baseStyle.width);
        assertFalse(e.hasAttribute("style"));
    }

    @Test
    void aliasesAndVariables() {
        Element e = element(null);
        setProperty(e, "word-wrap", "break-word", "");
        assertEquals("word-break: break-word;", cssText(e), "aliases are stored under the standard name");
        setProperty(e, "padding", "var(--p)", "");
        assertEquals("var(--p)", getPropertyValue(e, "padding"));
        assertEquals("", getPropertyValue(e, "padding-top"), "a shorthand with var() cannot be split");
    }
}
