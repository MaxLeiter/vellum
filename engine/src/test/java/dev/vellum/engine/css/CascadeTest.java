package dev.vellum.engine.css;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.style.BorderStyle;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Display;
import dev.vellum.engine.style.Length;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import static dev.vellum.engine.testing.Page.styleOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CascadeTest {
    private static final int RED = 0xFFFF0000, BLUE = 0xFF0000FF, GREEN = 0xFF008000, WHITE = 0xFFFFFFFF;

    @Test
    void specificityThenSourceOrder() {
        Page page = new TestHost().load("""
                <style>#a { color: red } div { color: blue } .c { color: green } .c { color: blue }</style>
                <div id=a class=c>x</div><div class=c>y</div>""");
        assertEquals(RED, page.style("#a").color);
        assertEquals(BLUE, page.style("div:not(#a)").color);
    }

    @Test
    void originsImportanceAndInlineStyle() {
        Page page = new TestHost().load("""
                <style>
                  p { margin: 1px }
                  #i { color: blue }
                  #imp { color: blue !important }
                  #both { color: blue !important }
                  [v-hidden] { display: block !important }
                </style>
                <p id=ua>x</p><p id=i style="color: red">x</p><p id=imp style="color: red">x</p>
                <p id=both style="color: red !important">x</p><p id=hidden v-hidden>x</p>""");
        assertEquals(Length.px(1), page.style("#ua").marginBottom, "author beats the UA sheet");
        assertEquals(RED, page.style("#i").color, "inline beats ids");
        assertEquals(BLUE, page.style("#imp").color, "author !important beats inline");
        assertEquals(RED, page.style("#both").color, "inline !important beats author !important");
        assertEquals(Display.NONE, page.style("#hidden").display, "UA !important beats author !important");
    }

    @Test
    void inheritanceAndCssWideKeywords() {
        Page page = new TestHost().load("""
                <style>
                  #p { color: red; width: 50px; padding: 3px; visibility: hidden }
                  #inherit { width: inherit; padding: inherit }
                  #initial { color: initial; visibility: initial }
                  #unset { color: unset; padding: unset }
                  html { width: inherit }
                </style>
                <div id=p><div id=plain></div><div id=inherit></div><div id=initial></div>
                <div id=unset style="padding: 9px"></div></div>""");
        ComputedStyle plain = page.style("#plain");
        assertEquals(RED, plain.color);
        assertEquals(Length.AUTO, plain.width);
        assertEquals(dev.vellum.engine.style.Visibility.HIDDEN, plain.visibility);
        assertEquals(Length.px(50), page.style("#inherit").width);
        assertEquals(Length.px(3), page.style("#inherit").paddingTop);
        assertEquals(ComputedStyle.INITIAL.color, page.style("#initial").color);
        assertEquals(dev.vellum.engine.style.Visibility.VISIBLE, page.style("#initial").visibility);
        assertEquals(RED, page.style("#unset").color, "unset inherits inherited properties");
        assertEquals(Length.px(9), page.style("#unset").paddingTop, "inline padding beats unset from the sheet");
        assertEquals(Length.AUTO, page.style("html").width, "inherit on the root gives the initial value");
    }

    @Test
    void customPropertiesAndVar() {
        Page page = new TestHost().load("""
                <style>
                  :root { --main: #ff0000; --pad: 4px; --both: var(--pad) var(--pad) }
                  #a { color: var(--main); padding: var(--both); --main: blue; }
                  #b { color: var(--missing, var(--other, green)); margin: var(--pad) 2px }
                  #c { color: var(--missing) }
                  #d { --x: var(--y); --y: var(--x); color: var(--x, blue) }
                  #e { --pad: 10px }
                  #e > p { margin-top: var(--pad) }
                  #f { --size: 2; width: calc(var(--size) * 10px) }
                </style>
                <div id=a></div><div id=b></div><div style="color: red"><div id=c></div></div><div id=d></div>
                <div id=e><p>x</p></div><div id=f></div>""");
        assertEquals(BLUE, page.style("#a").color, "the element's own --main wins, then var() substitutes it");
        assertEquals(Length.px(4), page.style("#a").paddingRight);
        assertEquals(GREEN, page.style("#b").color);
        assertEquals(Length.px(4), page.style("#b").marginTop);
        assertEquals(Length.px(2), page.style("#b").marginRight);
        assertEquals(RED, page.style("#c").color, "invalid at computed-value time: unset, so inherited");
        assertEquals(BLUE, page.style("#d").color, "a reference cycle makes the variables invalid");
        assertEquals(Length.px(10), page.style("#e > p").marginTop);
        assertEquals(Length.px(20), page.style("#f").width);
        assertEquals("4px", page.style("#a").var("--pad"));
        assertEquals("4px 4px", page.style("#a").var("--both"), "custom properties store substituted text");
    }

    @Test
    void calcAndMathFunctions() {
        ComputedStyle s = styleOf("font-size: 10px; width: calc(100% - 12px); height: calc(2em + 1px); "
                + "min-width: min(10px, 2em); max-width: max(5px, 1em); margin-left: clamp(1px, 50px, 20px); "
                + "padding-left: calc((100% - 10px) / 2); opacity: calc(1 / 4); margin-right: min(10%, 5px)");
        assertEquals(Length.of(-12, 100), s.width);
        assertEquals(Length.px(21), s.height);
        assertEquals(Length.px(10), s.minWidth);
        assertEquals(Length.px(10), s.maxWidth);
        assertEquals(Length.px(20), s.marginLeft);
        assertEquals(Length.of(-5, 50), s.paddingLeft);
        assertEquals(0.25f, s.opacity);
        assertEquals(Length.ZERO, s.marginRight, "min() over px and % cannot fold: invalid, so initial");
    }

    @Test
    void relativeUnits() {
        Page page = new TestHost().load("""
                <style>
                  html { font-size: 10px }
                  #p { font-size: 2em; width: 1em; height: 2rem; margin: 10vw 10vh 10vmin 10vmax; padding-top: 2dp }
                  #c { font-size: 50% }
                  #k { font-size: large } #k2 { font-size: larger }
                </style>
                <div id=p><div id=c></div><div id=k></div><div id=k2></div></div>""");
        ComputedStyle p = page.style("#p");
        assertEquals(20, p.fontSize, "em in font-size is the parent's size");
        assertEquals(Length.px(20), p.width, "em elsewhere is the element's own size");
        assertEquals(Length.px(20), p.height);
        assertEquals(Length.px(32), p.marginTop);
        assertEquals(Length.px(24), p.marginRight);
        assertEquals(Length.px(24), p.marginBottom);
        assertEquals(Length.px(32), p.marginLeft);
        assertEquals(Length.px(1), p.paddingTop, "dp is a device pixel (GUI scale 2)");
        assertEquals(10, page.style("#c").fontSize);
        assertEquals(12, page.style("#k").fontSize);
        assertEquals(30, page.style("#k2").fontSize);
    }

    @Test
    void currentColorAndBorderDefaults() {
        Page page = new TestHost().load("""
                <style>
                  #a { color: red; border: 1px solid; outline: thick solid }
                  #b { color: currentColor; border-style: solid; border-left-style: none }
                </style>
                <div style="color: blue"><div id=a></div><div id=b></div></div>""");
        ComputedStyle a = page.style("#a");
        assertEquals(RED, a.borderTopColor, "border colours default to currentColor");
        assertEquals(RED, a.outlineColor);
        assertEquals(3, a.outlineWidth);
        ComputedStyle b = page.style("#b");
        assertEquals(BLUE, b.color, "color: currentColor is the inherited colour");
        assertEquals(2, b.borderTopWidth, "the initial border width is medium (2px)");
        assertEquals(0, b.borderLeftWidth, "no border without a style");
        assertEquals(BorderStyle.NONE, b.borderLeftStyle);
    }

    @Test
    void lineHeightFactorsInherit() {
        Page page = new TestHost().load("""
                <style>
                  #a { font-size: 10px; line-height: 1.5 } #a p { font-size: 20px }
                  #b { font-size: 10px; line-height: 150% } #b p { font-size: 20px }
                  #c { line-height: normal }
                </style>
                <div id=a><p>x</p></div><div id=b><p>y</p></div><div id=c></div>""");
        assertEquals(15, page.style("#a").lineHeight);
        assertEquals(30, page.style("#a p").lineHeight, "a unitless factor is recomputed for the child's font");
        assertEquals(1.5f, page.style("#a p").lineHeightFactor);
        assertEquals(15, page.style("#b p").lineHeight, "a percentage inherits as px");
        assertTrue(Float.isNaN(page.style("#c").lineHeight));
    }

    @Test
    void fontWeights() {
        Page page = new TestHost().load("""
                <style>#n { font-weight: 300 } #n b { font-weight: bolder } #n i { font-weight: lighter }
                strong strong { font-weight: bolder }</style>
                <div id=n><b>x</b><i>y</i></div><strong><strong>z</strong></strong>""");
        assertEquals(400, page.style("#n b").fontWeight);
        assertEquals(100, page.style("#n i").fontWeight);
        assertEquals(900, page.style("strong strong").fontWeight);
    }

    @Test
    void blockification() {
        Page page = new TestHost().load("""
                <style>
                  html { display: inline }
                  #flex { display: flex } #grid { display: grid } #contents { display: contents }
                  .abs { position: absolute; display: inline-block }
                  .ig { display: inline-grid }
                </style>
                <div id=flex><span id=fi>x</span><span class=abs id=fa>y</span>
                  <div id=contents><span id=through>z</span></div></div>
                <div id=grid><span class=ig id=gi>w</span></div>
                <span class=abs id=abs>v</span>""");
        assertEquals(Display.BLOCK, page.style("html").display, "the root is blockified");
        assertEquals(Display.BLOCK, page.style("#fi").display);
        assertTrue(page.style("#fi").isFlexOrGridItemHint);
        assertFalse(page.style("#fa").isFlexOrGridItemHint, "absolutely positioned children are not flex items");
        assertEquals(Display.BLOCK, page.style("#fa").display);
        assertTrue(page.style("#through").isFlexOrGridItemHint, "children of display: contents are items of the flex");
        assertEquals(Display.GRID, page.style("#gi").display);
        assertEquals(Display.BLOCK, page.style("#abs").display);
        assertFalse(page.style("#contents > span").display == Display.INLINE);
    }

    @Test
    void pseudoElementStyles() {
        Page page = new TestHost().load("""
                <style>
                  #a::before { content: "[" attr(data-x) "]"; color: red }
                  #a::after { color: blue }
                  #b::before { content: counter(item) }
                  #c::after { content: none }
                  #flex { display: flex } #flex::after { content: "x"; display: inline }
                  input::placeholder { color: green }
                </style>
                <div id=a data-x=hi>x</div><div id=b></div><div id=c></div><div id=flex></div>
                <input id=i placeholder=p><textarea id=t></textarea><div id=d></div>""");
        Element a = page.query("#a");
        assertEquals("[hi]", a.beforeStyle.content);
        assertEquals(RED, a.beforeStyle.color);
        assertNull(a.afterStyle, "no content, no ::after");
        assertEquals("", page.query("#b").beforeStyle.content, "counters render as nothing");
        assertNull(page.query("#c").afterStyle);
        assertEquals(Display.BLOCK, page.query("#flex").afterStyle.display, "pseudo-elements are flex items too");
        assertEquals(GREEN, page.query("#i").placeholderStyle.color);
        assertEquals(0xFF808080, page.query("#t").placeholderStyle.color, "the UA sheet styles every placeholder");
        assertNull(page.query("#d").placeholderStyle);

        a.setAttribute("data-x", "yo");
        page.frame();
        assertEquals("[yo]", a.beforeStyle.content, "attr() follows attribute changes");
    }

    @Test
    void unchangedStylesKeepTheirIdentity() {
        Page page = new TestHost().load("""
                <style>.hot:hover { color: red } .big:hover { padding: 5px }</style>
                <div id=outer><p class=hot>a</p><p class=big>b</p><p id=other>c</p></div>""");
        Element hot = page.query(".hot"), big = page.query(".big"), other = page.query("#other");
        ComputedStyle hotBefore = hot.baseStyle, otherBefore = other.baseStyle, outer = page.style("#outer");
        assertFalse(page.frameLaysOut(), "nothing changed");
        assertSame(hotBefore, hot.baseStyle);
        assertSame(hot.baseStyle, hot.style, "without animations the used style is the base style");

        page.doc.setHovered(hot, true);
        assertFalse(page.frameLaysOut(), "a colour change only needs a repaint");
        assertNotSame(hotBefore, hot.baseStyle);
        assertEquals(RED, hot.baseStyle.color);
        assertSame(otherBefore, other.baseStyle);
        assertSame(outer, page.style("#outer"));

        page.doc.setHovered(big, true);
        assertTrue(page.frameLaysOut(), "a padding change needs layout");
        page.doc.setHovered(hot, false);
        page.doc.setHovered(big, false);
        assertTrue(page.frameLaysOut());
        assertEquals(hotBefore.color, hot.baseStyle.color);
    }

    @Test
    void childrenKeepTheirStylesWhenOnlyNonInheritedParentPropertiesChange() {
        Page page = new TestHost().load("""
                <style>.box { border: 1px solid blue } .box:hover { border-color: red; border-width: 3px }
                .kid { border-top: inherit }</style>
                <div class=box><p id=plain>a <b>b</b></p><p id=kid class=kid>c</p></div>""");
        ComputedStyle plain = page.style("#plain"), bold = page.style("b");
        page.doc.setHovered(page.query(".box"), true);
        page.frame();
        assertEquals(RED, page.style(".box").borderTopColor);
        assertSame(plain, page.style("#plain"), "the inherited properties did not change");
        assertSame(bold, page.style("b"));
        assertEquals(3, page.style("#kid").borderTopWidth, "an explicit inherit follows the parent");
    }

    @Test
    void interactionStateReachesEveryDependentElement() {
        Page page = new TestHost().load("""
                <style>.a:hover .b, .a:hover + .c, .f:focus-within, li:nth-child(2):hover, :has(> .d:active) { color: red }</style>
                <div class=a><span class=b>x</span></div><div class=c>y</div>
                <div class=f><input id=i></div><ul><li>1</li><li id=two>2</li></ul><div id=p><i class=d>d</i></div>""");
        Element a = page.query(".a");
        page.doc.setHovered(a, true);
        page.frame();
        assertEquals(RED, page.style(".b").color);
        assertEquals(RED, page.style(".c").color);
        page.doc.setHovered(a, false);
        page.query("#i").focus();
        page.doc.setHovered(page.query("#two"), true);
        page.doc.setActive(page.query(".d"), true);
        page.frame();
        assertEquals(WHITE, page.style(".b").color);
        assertEquals(WHITE, page.style(".c").color);
        assertEquals(RED, page.style(".f").color);
        assertEquals(RED, page.style("#two").color);
        assertEquals(RED, page.style("#p").color);

        // A DOM change in the same pass as an interaction change is seen too.
        page.doc.setHovered(a, true);
        page.query(".c").setAttribute("class", "z");
        page.frame();
        assertEquals(RED, page.style(".b").color);
        assertEquals(WHITE, page.style(".z").color);
    }

    @Test
    void newElementsAndRemovedPseudoElementsInvalidateLayout() {
        Page page = new TestHost().load("<style>.x::before { content: 'a' }</style><div id=a class=x></div>");
        Element a = page.query("#a");
        a.appendChild(page.doc.createElement("span"));
        assertTrue(page.frameLaysOut());
        a.removeClass("x");
        assertTrue(page.frameLaysOut());
        assertNull(a.beforeStyle);
    }

    @Test
    void rootFontSizeChangesReachRemUsers() {
        Page page = new TestHost().load("<style>html.big { font-size: 16px } p { width: 2rem }</style><div><p>x</p></div>");
        assertEquals(Length.px(16), page.style("p").width);
        page.query("html").addClass("big");
        page.frame();
        assertEquals(Length.px(32), page.style("p").width);
    }

    @Test
    void mediaQueries() {
        TestHost host = new TestHost() {
            @Override
            public boolean prefersReducedMotion() {
                return true;
            }
        };
        Page page = host.load("""
                <style>
                  #a { width: 1px }
                  @media (min-width: 300px) { #a { width: 2px } }
                  @media screen and (max-width: 299px), print { #a { width: 3px } }
                  @media (orientation: landscape) and (prefers-reduced-motion: reduce) { #b { width: 4px } }
                  @media (min-gui-scale: 3) { #c { width: 5px } }
                  @media (200px < width <= 400px) { #c { height: 6px } }
                  @media not all and (min-width: 1000px) { #d { width: 7px } }
                  @media (unknown-feature) { #d { height: 8px } }
                </style>
                <style media="(max-width: 100px)">#e { width: 9px }</style>
                <div id=a></div><div id=b></div><div id=c></div><div id=d></div><div id=e></div>""");
        assertEquals(Length.px(2), page.style("#a").width);
        assertEquals(Length.px(4), page.style("#b").width);
        assertEquals(Length.AUTO, page.style("#c").width);
        assertEquals(Length.px(6), page.style("#c").height);
        assertEquals(Length.px(7), page.style("#d").width);
        assertEquals(Length.AUTO, page.style("#d").height);
        assertEquals(Length.AUTO, page.style("#e").width);
        page.doc.setViewport(100, 200, 3);
        page.frame();
        assertEquals(Length.px(3), page.style("#a").width);
        assertEquals(Length.AUTO, page.style("#b").width, "portrait now");
        assertEquals(Length.px(5), page.style("#c").width);
        assertEquals(Length.px(9), page.style("#e").width);
    }

    @Test
    void linkedAndImportedSheets() {
        TestHost host = new TestHost()
                .resource("test:css/main.css", "@import 'base.css'; #a { color: blue }")
                .resource("test:css/base.css", "#a { color: red; width: 3px } #b { background: url(img.png) }");
        Page page = host.load("""
                <link rel=stylesheet href="css/main.css"><link rel="preload stylesheet" href="missing.css">
                <div id=a></div><div id=b></div>""");
        assertEquals(BLUE, page.style("#a").color);
        assertEquals(Length.px(3), page.style("#a").width);
        assertEquals(new dev.vellum.engine.style.Image.Url("test:css/img.png"),
                page.style("#b").backgroundLayers.get(0).image(), "urls resolve against the stylesheet");
        assertTrue(host.logs.contains("WARN: Stylesheet not found: test:missing.css"), host.logs::toString);
    }

    @Test
    void styleElementChangesAreSeen() {
        Page page = new TestHost().load("<style id=s>#a { color: red }</style><template><style>#a { color: blue }</style></template><div id=a></div>");
        assertEquals(RED, page.style("#a").color, "styles inside templates are inert");
        page.query("#s").setTextContent("#a { color: green }");
        page.frame();
        assertEquals(GREEN, page.style("#a").color);
    }

    @Test
    void invalidDeclarationsAreLoggedAtDebug() {
        TestHost host = new TestHost();
        host.load("<style>\n#a { colr: red }</style><div id=a style='width: nope'></div>");
        assertTrue(host.logs.contains("DEBUG: test:page.html:2: Invalid declaration 'colr: red'"), host.logs::toString);
        assertTrue(host.logs.stream().anyMatch(l -> l.startsWith("DEBUG:") && l.contains("'width: nope'")));
        assertTrue(host.errors.isEmpty());
    }

    @Test
    void everyElementGetsAStyle() {
        Page page = new TestHost().load("<div><span>a</span><template><b>t</b></template></div><svg-thing></svg-thing>");
        page.doc.querySelectorAll("*").forEach(e -> assertTrue(e.baseStyle != null && e.style != null, e.toString()));
        assertEquals(WHITE, page.style("span").color);
    }
}
