package dev.vellum.engine.css;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.style.BorderStyle;
import dev.vellum.engine.style.BoxSizing;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Cursor;
import dev.vellum.engine.style.Display;
import dev.vellum.engine.style.Image;
import dev.vellum.engine.style.Length;
import dev.vellum.engine.style.Shadow;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static dev.vellum.engine.css.StyleTesting.element;
import static dev.vellum.engine.css.StyleTesting.page;
import static dev.vellum.engine.css.StyleTesting.style;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserAgentSheetTest {
    private static final String CONTROLS = """
            <button id=b>OK</button><button id=bd disabled>No</button><select id=sel></select>
            <input id=text><input id=pw type=password><textarea id=ta></textarea>
            <input id=cb type=checkbox><input id=cbc type=CHECKBOX checked><input id=r type=radio>
            <input id=range type=range><input id=sub type=submit value=Go><input id=h type=hidden>
            <progress id=pr></progress>""";

    private static String sprite(ComputedStyle s) {
        return ((Image.Sprite) s.backgroundLayers.get(0).image()).id();
    }

    @Test
    void parsesWithoutProblems() throws IOException {
        String css;
        try (InputStream in = StyleEngine.class.getResourceAsStream("/vellum/ua.css")) {
            css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> problems = new ArrayList<>();
        Stylesheet.parse(css, "vellum:ua.css", null, problems::add);
        assertEquals(List.of(), problems);
    }

    @Test
    void rootAndBasics() {
        Document doc = page("<p>a</p><span>b</span><h1>c</h1><h3>d</h3><a href=x>e</a><code>f</code><b>g</b><u>h</u>");
        ComputedStyle html = style(doc, "html");
        assertEquals(0xFFFFFFFF, html.color);
        assertEquals(8, html.fontSize);
        assertEquals(List.of("minecraft:default"), html.fontFamily);
        assertTrue(Float.isNaN(html.lineHeight));
        assertEquals(BoxSizing.BORDER_BOX, html.boxSizing);
        assertEquals(Length.ZERO, style(doc, "body").marginTop);
        assertEquals(Display.BLOCK, style(doc, "p").display);
        assertEquals(Length.px(8), style(doc, "p").marginBottom);
        assertEquals(Display.INLINE, style(doc, "span").display);
        assertEquals(16, style(doc, "h1").fontSize);
        assertEquals(700, style(doc, "h1").fontWeight);
        assertEquals(8, style(doc, "h3").fontSize);
        ComputedStyle a = style(doc, "a");
        assertTrue(a.underline);
        assertEquals(Cursor.POINTER, a.cursor);
        assertEquals(List.of("minecraft:uniform"), style(doc, "code").fontFamily);
        assertEquals(700, style(doc, "b").fontWeight);
        assertTrue(style(doc, "u").underline);
    }

    @Test
    void hiddenElements() {
        Document doc = page("<style></style><div hidden id=h></div><dialog id=d></dialog><dialog open id=o></dialog><div v-cloak id=c "
                + "style='display: block'></div><template id=t></template>");
        for (String sel : List.of("head", "style", "#h", "#d", "#c", "#t")) {
            assertEquals(Display.NONE, style(doc, sel).display, sel);
        }
        assertEquals(Display.BLOCK, style(doc, "#o").display);
    }

    @Test
    void buttonsLookVanilla() {
        Document doc = page(CONTROLS);
        ComputedStyle b = style(doc, "#b");
        assertEquals(Display.INLINE_FLEX, b.display);
        assertEquals("minecraft:widget/button", sprite(b));
        assertEquals(Length.px(20), b.height);
        assertEquals(Length.px(8), b.paddingLeft);
        assertEquals(List.of(Shadow.MINECRAFT), b.textShadow);
        assertEquals(Cursor.POINTER, b.cursor);
        ComputedStyle disabled = style(doc, "#bd");
        assertEquals("minecraft:widget/button_disabled", sprite(disabled));
        assertEquals(0xFFA0A0A0, disabled.color);
        doc.setHovered(element(doc, "#b"), true);
        doc.styleEngine().restyle();
        assertEquals("minecraft:widget/button_highlighted", sprite(style(doc, "#b")));
        assertEquals("minecraft:widget/button", sprite(style(doc, "#sub")));
        assertEquals(dev.vellum.engine.style.Align.FLEX_START, style(doc, "#sel").justifyContent);
    }

    @Test
    void textFieldsAndToggles() {
        Document doc = page(CONTROLS);
        ComputedStyle text = style(doc, "#text");
        assertEquals("minecraft:widget/text_field", sprite(text));
        assertEquals(Length.px(150), text.width);
        assertEquals(0xFFE0E0E0, text.color);
        assertEquals(Cursor.TEXT, text.cursor);
        assertEquals("minecraft:widget/text_field", sprite(style(doc, "#pw")));
        assertEquals(Length.px(60), style(doc, "#ta").height);
        element(doc, "#text").focus();
        doc.styleEngine().restyle();
        assertEquals("minecraft:widget/text_field_highlighted", sprite(style(doc, "#text")));

        assertEquals("minecraft:widget/checkbox", sprite(style(doc, "#cb")));
        assertEquals(Length.px(20), style(doc, "#cb").width);
        assertEquals("minecraft:widget/checkbox_selected", sprite(style(doc, "#cbc")));
        assertEquals("minecraft:widget/checkbox", sprite(style(doc, "#r")));
        element(doc, "#cbc").focus(); // pointer focus: no highlight
        doc.setHovered(element(doc, "#cbc"), true);
        doc.styleEngine().restyle();
        assertEquals("minecraft:widget/checkbox_selected_highlighted", sprite(style(doc, "#cbc")));
        assertEquals("minecraft:widget/slider", sprite(style(doc, "#range")));
        assertEquals(Display.NONE, style(doc, "#h").display);
        assertEquals(Length.px(8), style(doc, "#pr").height);
    }

    @Test
    void minecraftElements() {
        Document doc = page("<slot index=0></slot><item id=i></item><entity></entity><player-head></player-head>"
                + "<sprite></sprite><mc-text>t</mc-text>");
        ComputedStyle slot = style(doc, "slot");
        assertEquals(Display.INLINE_BLOCK, slot.display);
        assertEquals(Length.px(18), slot.width);
        assertEquals(0xFF8B8B8B, slot.backgroundColor);
        assertEquals(1, slot.borderTopWidth);
        assertEquals(0xFF373737, slot.borderLeftColor);
        assertEquals(0xFFFFFFFF, slot.borderBottomColor);
        assertEquals(Length.px(16), style(doc, "item").height);
        assertEquals(Length.px(48), style(doc, "entity").height);
        assertEquals(Length.px(8), style(doc, "player-head").width);
        assertEquals(Display.INLINE_BLOCK, style(doc, "sprite").display);
        assertEquals(Display.INLINE, style(doc, "mc-text").display);
    }

    @Test
    void listsAndDetails() {
        Document doc = page("<ul><li id=u>a</li></ul><ol><li id=o>b</li></ol><details id=d><summary id=s>x</summary></details>"
                + "<details open><summary id=so>y</summary></details>");
        Element u = element(doc, "#u");
        assertEquals("• ", u.beforeStyle.content);
        assertEquals(Display.BLOCK, u.baseStyle.display);
        assertEquals(Length.px(12), style(doc, "ul").paddingLeft);
        assertNull(element(doc, "#o").beforeStyle);
        assertEquals("▶ ", element(doc, "#s").beforeStyle.content);
        assertEquals("▼ ", element(doc, "#so").beforeStyle.content);
        assertEquals(BoxSizing.BORDER_BOX, u.beforeStyle.boxSizing);
    }

    @Test
    void utilityClasses() {
        Document doc = page("<div class=mc-panel><span class=mc-label>Inventory</span></div><div class=mc-inset></div>"
                + "<div class=mc-tooltip></div><div class=mc-dark></div>");
        ComputedStyle panel = style(doc, ".mc-panel");
        assertEquals(0xFFC6C6C6, panel.backgroundColor);
        assertEquals(2, panel.borderTopWidth);
        assertEquals(0xFFFFFFFF, panel.borderTopColor);
        assertEquals(0xFF555555, panel.borderRightColor);
        assertEquals(0xFF404040, panel.color);
        assertEquals(Length.px(7), panel.paddingLeft);
        assertEquals(BorderStyle.SOLID, style(doc, ".mc-inset").borderTopStyle);
        assertEquals(0xF0100010, style(doc, ".mc-tooltip").backgroundColor);
        assertEquals(Length.px(4), style(doc, ".mc-tooltip").paddingLeft);
        assertEquals(0x80000000, style(doc, ".mc-dark").backgroundColor);
        assertTrue(style(doc, ".mc-label").textShadow.isEmpty());
    }
}
