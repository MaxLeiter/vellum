package dev.vellum.engine.css;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.style.BorderStyle;
import dev.vellum.engine.style.BoxSizing;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Cursor;
import dev.vellum.engine.style.Display;
import dev.vellum.engine.style.Image;
import dev.vellum.engine.style.Length;
import dev.vellum.engine.style.ObjectFit;
import dev.vellum.engine.style.Shadow;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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
        Page page = new TestHost().load("<p>a</p><span>b</span><h1>c</h1><h3>d</h3><a href=x>e</a><code>f</code><b>g</b><u>h</u>");
        ComputedStyle html = page.style("html");
        assertEquals(0xFFFFFFFF, html.color);
        assertEquals(8, html.fontSize);
        assertEquals(List.of("minecraft:default"), html.fontFamily);
        assertTrue(Float.isNaN(html.lineHeight));
        assertEquals(BoxSizing.BORDER_BOX, html.boxSizing);
        assertEquals(Length.ZERO, page.style("body").marginTop);
        assertEquals(Display.BLOCK, page.style("p").display);
        assertEquals(Length.px(8), page.style("p").marginBottom);
        assertEquals(Display.INLINE, page.style("span").display);
        assertEquals(16, page.style("h1").fontSize);
        assertEquals(700, page.style("h1").fontWeight);
        assertEquals(8, page.style("h3").fontSize);
        ComputedStyle a = page.style("a");
        assertTrue(a.underline);
        assertEquals(Cursor.POINTER, a.cursor);
        assertEquals(List.of("minecraft:uniform"), page.style("code").fontFamily);
        assertEquals(700, page.style("b").fontWeight);
        assertTrue(page.style("u").underline);
    }

    @Test
    void hiddenElements() {
        Page page = new TestHost().load("<style></style><div hidden id=h></div><dialog id=d></dialog><dialog open id=o></dialog><div v-hidden id=c "
                + "style='display: block'></div><template id=t></template>");
        for (String sel : List.of("head", "style", "#h", "#d", "#c", "#t")) {
            assertEquals(Display.NONE, page.style(sel).display, sel);
        }
        assertEquals(Display.BLOCK, page.style("#o").display);
    }

    @Test
    void buttonsLookVanilla() {
        Page page = new TestHost().load(CONTROLS);
        ComputedStyle b = page.style("#b");
        assertEquals(Display.INLINE_FLEX, b.display);
        assertEquals("minecraft:widget/button", sprite(b));
        assertEquals(Length.px(20), b.height);
        assertEquals(Length.px(8), b.paddingLeft);
        assertEquals(List.of(Shadow.MINECRAFT), b.textShadow);
        assertEquals(Cursor.POINTER, b.cursor);
        ComputedStyle disabled = page.style("#bd");
        assertEquals("minecraft:widget/button_disabled", sprite(disabled));
        assertEquals(0xFFA0A0A0, disabled.color);
        page.doc.setHovered(page.query("#b"), true);
        page.frame();
        assertEquals("minecraft:widget/button_highlighted", sprite(page.style("#b")));
        assertEquals("minecraft:widget/button", sprite(page.style("#sub")));
        assertEquals(dev.vellum.engine.style.Align.FLEX_START, page.style("#sel").justifyContent);
    }

    @Test
    void textFieldsAndToggles() {
        Page page = new TestHost().load(CONTROLS);
        ComputedStyle text = page.style("#text");
        assertEquals("minecraft:widget/text_field", sprite(text));
        assertEquals(Length.px(150), text.width);
        assertEquals(0xFFE0E0E0, text.color);
        assertEquals(Cursor.TEXT, text.cursor);
        assertEquals("minecraft:widget/text_field", sprite(page.style("#pw")));
        assertEquals(Length.px(60), page.style("#ta").height);
        page.query("#text").focus();
        page.frame();
        assertEquals("minecraft:widget/text_field_highlighted", sprite(page.style("#text")));

        assertEquals("minecraft:widget/checkbox", sprite(page.style("#cb")));
        assertEquals(Length.px(20), page.style("#cb").width);
        assertEquals("minecraft:widget/checkbox_selected", sprite(page.style("#cbc")));
        assertEquals("minecraft:widget/checkbox", sprite(page.style("#r")));
        page.query("#cbc").focus(); // pointer focus: no highlight
        page.doc.setHovered(page.query("#cbc"), true);
        page.frame();
        assertEquals("minecraft:widget/checkbox_selected_highlighted", sprite(page.style("#cbc")));
        assertEquals("minecraft:widget/slider", sprite(page.style("#range")));
        assertEquals(Display.NONE, page.style("#h").display);
        assertEquals(Length.px(8), page.style("#pr").height);
    }

    @Test
    void minecraftElements() {
        Page page = new TestHost().load("<slot index=0></slot><item id=i></item><entity></entity><player-head></player-head>"
                + "<sprite></sprite><mc-text>t</mc-text>");
        ComputedStyle slot = page.style("slot");
        assertEquals(Display.INLINE_BLOCK, slot.display);
        assertEquals(Length.px(18), slot.width);
        assertEquals(0xFF8B8B8B, slot.backgroundColor);
        assertEquals(1, slot.borderTopWidth);
        assertEquals(0xFF373737, slot.borderLeftColor);
        assertEquals(0xFFFFFFFF, slot.borderBottomColor);
        assertEquals(Length.px(16), page.style("item").height);
        assertEquals(Length.px(48), page.style("entity").height);
        assertEquals(Length.px(8), page.style("player-head").width);
        assertEquals(ObjectFit.CONTAIN, page.style("item").objectFit, "items and faces are squares");
        assertEquals(ObjectFit.CONTAIN, page.style("player-head").objectFit);
        assertEquals(ObjectFit.FILL, page.style("entity").objectFit, "entities fit their own way");
        assertEquals(Display.INLINE_BLOCK, page.style("sprite").display);
        assertEquals(Display.INLINE, page.style("mc-text").display);
    }

    @Test
    void listsAndDetails() {
        Page page = new TestHost().load("<ul><li id=u>a</li></ul><ol><li id=o>b</li></ol><details id=d><summary id=s>x</summary></details>"
                + "<details open><summary id=so>y</summary></details>");
        Element u = page.query("#u");
        assertEquals("• ", u.beforeStyle.content);
        assertEquals(Display.BLOCK, u.baseStyle.display);
        assertEquals(Length.px(12), page.style("ul").paddingLeft);
        assertNull(page.query("#o").beforeStyle);
        assertEquals("▶ ", page.query("#s").beforeStyle.content);
        assertEquals("▼ ", page.query("#so").beforeStyle.content);
        assertEquals(BoxSizing.BORDER_BOX, u.beforeStyle.boxSizing);
    }

    @Test
    void listStyleTypeSetsTheBullet() {
        Page page = new TestHost().load("<style>.none { list-style: none } .sq { list-style-type: square }"
                + " .circle { list-style: circle inside }</style>"
                + "<ul class=none><li id=a>a</li></ul><ul class=sq><li id=b>b</li></ul><ul class=circle><li id=c>c</li></ul>"
                + "<ul><li id=d style='list-style: none'>d</li><li id=e>e</li></ul>");
        assertNull(page.query("#a").beforeStyle, "list-style: none on the list removes its bullets");
        assertEquals("▪ ", page.query("#b").beforeStyle.content);
        assertEquals("◦ ", page.query("#c").beforeStyle.content, "position keywords in the shorthand are ignored");
        assertNull(page.query("#d").beforeStyle, "or on one item");
        assertEquals("• ", page.query("#e").beforeStyle.content);
    }

    @Test
    void utilityClasses() {
        Page page = new TestHost().load("<div class=mc-panel><span class=mc-label>Inventory</span></div><div class=mc-inset></div>"
                + "<div class=mc-tooltip></div><div class=mc-dark></div>");
        ComputedStyle panel = page.style(".mc-panel");
        assertEquals(0xFFC6C6C6, panel.backgroundColor);
        assertEquals(2, panel.borderTopWidth);
        assertEquals(0xFFFFFFFF, panel.borderTopColor);
        assertEquals(0xFF555555, panel.borderRightColor);
        assertEquals(0xFF404040, panel.color);
        assertEquals(Length.px(7), panel.paddingLeft);
        assertEquals(BorderStyle.SOLID, page.style(".mc-inset").borderTopStyle);
        assertEquals(0xF0100010, page.style(".mc-tooltip").backgroundColor);
        assertEquals(Length.px(4), page.style(".mc-tooltip").paddingLeft);
        assertEquals(0x80000000, page.style(".mc-dark").backgroundColor);
        assertTrue(page.style(".mc-label").textShadow.isEmpty());
    }
}
