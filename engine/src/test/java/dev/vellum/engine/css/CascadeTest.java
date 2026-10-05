package dev.vellum.engine.css;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.style.BorderStyle;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Display;
import dev.vellum.engine.style.Length;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import static dev.vellum.engine.css.StyleTesting.element;
import static dev.vellum.engine.css.StyleTesting.page;
import static dev.vellum.engine.css.StyleTesting.restyleInvalidatesLayout;
import static dev.vellum.engine.css.StyleTesting.style;
import static dev.vellum.engine.css.StyleTesting.styleOf;
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
        Document doc = page("""
                <style>#a { color: red } div { color: blue } .c { color: green } .c { color: blue }</style>
                <div id=a class=c>x</div><div class=c>y</div>""");
        assertEquals(RED, style(doc, "#a").color);
        assertEquals(BLUE, style(doc, "div:not(#a)").color);
    }

    @Test
    void originsImportanceAndInlineStyle() {
        Document doc = page("""
                <style>
                  p { margin: 1px }
                  #i { color: blue }
                  #imp { color: blue !important }
                  #both { color: blue !important }
                  [v-cloak] { display: block !important }
                </style>
                <p id=ua>x</p><p id=i style="color: red">x</p><p id=imp style="color: red">x</p>
                <p id=both style="color: red !important">x</p><p id=cloak v-cloak>x</p>""");
        assertEquals(Length.px(1), style(doc, "#ua").marginBottom, "author beats the UA sheet");
        assertEquals(RED, style(doc, "#i").color, "inline beats ids");
        assertEquals(BLUE, style(doc, "#imp").color, "author !important beats inline");
        assertEquals(RED, style(doc, "#both").color, "inline !important beats author !important");
        assertEquals(Display.NONE, style(doc, "#cloak").display, "UA !important beats author !important");
    }

    @Test
    void inheritanceAndCssWideKeywords() {
        Document doc = page("""
                <style>
                  #p { color: red; width: 50px; padding: 3px; visibility: hidden }
                  #inherit { width: inherit; padding: inherit }
                  #initial { color: initial; visibility: initial }
                  #unset { color: unset; padding: unset }
                  html { width: inherit }
                </style>
                <div id=p><div id=plain></div><div id=inherit></div><div id=initial></div>
                <div id=unset style="padding: 9px"></div></div>""");
        ComputedStyle plain = style(doc, "#plain");
        assertEquals(RED, plain.color);
        assertEquals(Length.AUTO, plain.width);
        assertEquals(dev.vellum.engine.style.Visibility.HIDDEN, plain.visibility);
        assertEquals(Length.px(50), style(doc, "#inherit").width);
        assertEquals(Length.px(3), style(doc, "#inherit").paddingTop);
        assertEquals(ComputedStyle.INITIAL.color, style(doc, "#initial").color);
        assertEquals(dev.vellum.engine.style.Visibility.VISIBLE, style(doc, "#initial").visibility);
        assertEquals(RED, style(doc, "#unset").color, "unset inherits inherited properties");
        assertEquals(Length.px(9), style(doc, "#unset").paddingTop, "inline padding beats unset from the sheet");
        assertEquals(Length.AUTO, style(doc, "html").width, "inherit on the root gives the initial value");
    }

    @Test
    void customPropertiesAndVar() {
        Document doc = page("""
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
        assertEquals(BLUE, style(doc, "#a").color, "the element's own --main wins, then var() substitutes it");
        assertEquals(Length.px(4), style(doc, "#a").paddingRight);
        assertEquals(GREEN, style(doc, "#b").color);
        assertEquals(Length.px(4), style(doc, "#b").marginTop);
        assertEquals(Length.px(2), style(doc, "#b").marginRight);
        assertEquals(RED, style(doc, "#c").color, "invalid at computed-value time: unset, so inherited");
        assertEquals(BLUE, style(doc, "#d").color, "a reference cycle makes the variables invalid");
        assertEquals(Length.px(10), style(doc, "#e > p").marginTop);
        assertEquals(Length.px(20), style(doc, "#f").width);
        assertEquals("4px", style(doc, "#a").var("--pad"));
        assertEquals("4px 4px", style(doc, "#a").var("--both"), "custom properties store substituted text");
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
        Document doc = page("""
                <style>
                  html { font-size: 10px }
                  #p { font-size: 2em; width: 1em; height: 2rem; margin: 10vw 10vh 10vmin 10vmax; padding-top: 2dp }
                  #c { font-size: 50% }
                  #k { font-size: large } #k2 { font-size: larger }
                </style>
                <div id=p><div id=c></div><div id=k></div><div id=k2></div></div>""");
        ComputedStyle p = style(doc, "#p");
        assertEquals(20, p.fontSize, "em in font-size is the parent's size");
        assertEquals(Length.px(20), p.width, "em elsewhere is the element's own size");
        assertEquals(Length.px(20), p.height);
        assertEquals(Length.px(32), p.marginTop);
        assertEquals(Length.px(24), p.marginRight);
        assertEquals(Length.px(24), p.marginBottom);
        assertEquals(Length.px(32), p.marginLeft);
        assertEquals(Length.px(1), p.paddingTop, "dp is a device pixel (GUI scale 2)");
        assertEquals(10, style(doc, "#c").fontSize);
        assertEquals(12, style(doc, "#k").fontSize);
        assertEquals(30, style(doc, "#k2").fontSize);
    }

    @Test
    void currentColorAndBorderDefaults() {
        Document doc = page("""
                <style>
                  #a { color: red; border: 1px solid; outline: thick solid }
                  #b { color: currentColor; border-style: solid; border-left-style: none }
                </style>
                <div style="color: blue"><div id=a></div><div id=b></div></div>""");
        ComputedStyle a = style(doc, "#a");
        assertEquals(RED, a.borderTopColor, "border colours default to currentColor");
        assertEquals(RED, a.outlineColor);
        assertEquals(3, a.outlineWidth);
        ComputedStyle b = style(doc, "#b");
        assertEquals(BLUE, b.color, "color: currentColor is the inherited colour");
        assertEquals(2, b.borderTopWidth, "the initial border width is medium (2px)");
        assertEquals(0, b.borderLeftWidth, "no border without a style");
        assertEquals(BorderStyle.NONE, b.borderLeftStyle);
    }

    @Test
    void lineHeightFactorsInherit() {
        Document doc = page("""
                <style>
                  #a { font-size: 10px; line-height: 1.5 } #a p { font-size: 20px }
                  #b { font-size: 10px; line-height: 150% } #b p { font-size: 20px }
                  #c { line-height: normal }
                </style>
                <div id=a><p>x</p></div><div id=b><p>y</p></div><div id=c></div>""");
        assertEquals(15, style(doc, "#a").lineHeight);
        assertEquals(30, style(doc, "#a p").lineHeight, "a unitless factor is recomputed for the child's font");
        assertEquals(1.5f, style(doc, "#a p").lineHeightFactor);
        assertEquals(15, style(doc, "#b p").lineHeight, "a percentage inherits as px");
        assertTrue(Float.isNaN(style(doc, "#c").lineHeight));
    }

    @Test
    void fontWeights() {
        Document doc = page("""
                <style>#n { font-weight: 300 } #n b { font-weight: bolder } #n i { font-weight: lighter }
                strong strong { font-weight: bolder }</style>
                <div id=n><b>x</b><i>y</i></div><strong><strong>z</strong></strong>""");
        assertEquals(400, style(doc, "#n b").fontWeight);
        assertEquals(100, style(doc, "#n i").fontWeight);
        assertEquals(900, style(doc, "strong strong").fontWeight);
    }

    @Test
    void blockification() {
        Document doc = page("""
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
        assertEquals(Display.BLOCK, style(doc, "html").display, "the root is blockified");
        assertEquals(Display.BLOCK, style(doc, "#fi").display);
        assertTrue(style(doc, "#fi").isFlexOrGridItemHint);
        assertFalse(style(doc, "#fa").isFlexOrGridItemHint, "absolutely positioned children are not flex items");
        assertEquals(Display.BLOCK, style(doc, "#fa").display);
        assertTrue(style(doc, "#through").isFlexOrGridItemHint, "children of display: contents are items of the flex");
        assertEquals(Display.GRID, style(doc, "#gi").display);
        assertEquals(Display.BLOCK, style(doc, "#abs").display);
        assertFalse(style(doc, "#contents > span").display == Display.INLINE);
    }

    @Test
    void pseudoElementStyles() {
        Document doc = page("""
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
        Element a = element(doc, "#a");
        assertEquals("[hi]", a.beforeStyle.content);
        assertEquals(RED, a.beforeStyle.color);
        assertNull(a.afterStyle, "no content, no ::after");
        assertEquals("", element(doc, "#b").beforeStyle.content, "counters render as nothing");
        assertNull(element(doc, "#c").afterStyle);
        assertEquals(Display.BLOCK, element(doc, "#flex").afterStyle.display, "pseudo-elements are flex items too");
        assertEquals(GREEN, element(doc, "#i").placeholderStyle.color);
        assertEquals(0xFF808080, element(doc, "#t").placeholderStyle.color, "the UA sheet styles every placeholder");
        assertNull(element(doc, "#d").placeholderStyle);

        a.setAttribute("data-x", "yo");
        doc.styleEngine().restyle();
        assertEquals("[yo]", a.beforeStyle.content, "attr() follows attribute changes");
    }

    @Test
    void unchangedStylesKeepTheirIdentity() {
        Document doc = page("""
                <style>.hot:hover { color: red } .big:hover { padding: 5px }</style>
                <div id=outer><p class=hot>a</p><p class=big>b</p><p id=other>c</p></div>""");
        Element hot = element(doc, ".hot"), big = element(doc, ".big"), other = element(doc, "#other");
        ComputedStyle hotBefore = hot.baseStyle, otherBefore = other.baseStyle, outer = style(doc, "#outer");
        assertFalse(restyleInvalidatesLayout(doc), "nothing changed");
        assertSame(hotBefore, hot.baseStyle);
        assertSame(hot.baseStyle, hot.style, "the animation stub passes the base style through");

        doc.setHovered(hot, true);
        assertFalse(restyleInvalidatesLayout(doc), "a colour change only needs a repaint");
        assertNotSame(hotBefore, hot.baseStyle);
        assertEquals(RED, hot.baseStyle.color);
        assertSame(otherBefore, other.baseStyle);
        assertSame(outer, style(doc, "#outer"));

        doc.setHovered(big, true);
        assertTrue(restyleInvalidatesLayout(doc), "a padding change needs layout");
        doc.setHovered(hot, false);
        doc.setHovered(big, false);
        assertTrue(restyleInvalidatesLayout(doc));
        assertEquals(hotBefore.color, hot.baseStyle.color);
    }

    @Test
    void childrenKeepTheirStylesWhenOnlyNonInheritedParentPropertiesChange() {
        Document doc = page("""
                <style>.box { border: 1px solid blue } .box:hover { border-color: red; border-width: 3px }
                .kid { border-top: inherit }</style>
                <div class=box><p id=plain>a <b>b</b></p><p id=kid class=kid>c</p></div>""");
        ComputedStyle plain = style(doc, "#plain"), bold = style(doc, "b");
        doc.setHovered(element(doc, ".box"), true);
        doc.styleEngine().restyle();
        assertEquals(RED, style(doc, ".box").borderTopColor);
        assertSame(plain, style(doc, "#plain"), "the inherited properties did not change");
        assertSame(bold, style(doc, "b"));
        assertEquals(3, style(doc, "#kid").borderTopWidth, "an explicit inherit follows the parent");
    }

    @Test
    void interactionStateReachesEveryDependentElement() {
        Document doc = page("""
                <style>.a:hover .b, .a:hover + .c, .f:focus-within, li:nth-child(2):hover, :has(> .d:active) { color: red }</style>
                <div class=a><span class=b>x</span></div><div class=c>y</div>
                <div class=f><input id=i></div><ul><li>1</li><li id=two>2</li></ul><div id=p><i class=d>d</i></div>""");
        Element a = element(doc, ".a");
        doc.setHovered(a, true);
        doc.styleEngine().restyle();
        assertEquals(RED, style(doc, ".b").color);
        assertEquals(RED, style(doc, ".c").color);
        doc.setHovered(a, false);
        element(doc, "#i").focus();
        doc.setHovered(element(doc, "#two"), true);
        doc.setActive(element(doc, ".d"), true);
        doc.styleEngine().restyle();
        assertEquals(WHITE, style(doc, ".b").color);
        assertEquals(WHITE, style(doc, ".c").color);
        assertEquals(RED, style(doc, ".f").color);
        assertEquals(RED, style(doc, "#two").color);
        assertEquals(RED, style(doc, "#p").color);

        // A DOM change in the same pass as an interaction change is seen too.
        doc.setHovered(a, true);
        element(doc, ".c").setAttribute("class", "z");
        doc.styleEngine().restyle();
        assertEquals(RED, style(doc, ".b").color);
        assertEquals(WHITE, style(doc, ".z").color);
    }

    @Test
    void newElementsAndRemovedPseudoElementsInvalidateLayout() {
        Document doc = page("<style>.x::before { content: 'a' }</style><div id=a class=x></div>");
        Element a = element(doc, "#a");
        a.appendChild(doc.createElement("span"));
        assertTrue(restyleInvalidatesLayout(doc));
        a.removeClass("x");
        assertTrue(restyleInvalidatesLayout(doc));
        assertNull(a.beforeStyle);
    }

    @Test
    void rootFontSizeChangesReachRemUsers() {
        Document doc = page("<style>html.big { font-size: 16px } p { width: 2rem }</style><div><p>x</p></div>");
        assertEquals(Length.px(16), style(doc, "p").width);
        element(doc, "html").addClass("big");
        doc.styleEngine().restyle();
        assertEquals(Length.px(32), style(doc, "p").width);
    }

    @Test
    void mediaQueries() {
        TestHost host = new TestHost() {
            @Override
            public boolean prefersReducedMotion() {
                return true;
            }
        };
        Document doc = page(host, """
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
        assertEquals(Length.px(2), style(doc, "#a").width);
        assertEquals(Length.px(4), style(doc, "#b").width);
        assertEquals(Length.AUTO, style(doc, "#c").width);
        assertEquals(Length.px(6), style(doc, "#c").height);
        assertEquals(Length.px(7), style(doc, "#d").width);
        assertEquals(Length.AUTO, style(doc, "#d").height);
        assertEquals(Length.AUTO, style(doc, "#e").width);
        doc.setViewport(100, 200, 3);
        doc.styleEngine().restyle();
        assertEquals(Length.px(3), style(doc, "#a").width);
        assertEquals(Length.AUTO, style(doc, "#b").width, "portrait now");
        assertEquals(Length.px(5), style(doc, "#c").width);
        assertEquals(Length.px(9), style(doc, "#e").width);
    }

    @Test
    void linkedAndImportedSheets() {
        TestHost host = new TestHost()
                .resource("test:css/main.css", "@import 'base.css'; #a { color: blue }")
                .resource("test:css/base.css", "#a { color: red; width: 3px } #b { background: url(img.png) }");
        Document doc = page(host, """
                <link rel=stylesheet href="css/main.css"><link rel="preload stylesheet" href="missing.css">
                <div id=a></div><div id=b></div>""");
        assertEquals(BLUE, style(doc, "#a").color);
        assertEquals(Length.px(3), style(doc, "#a").width);
        assertEquals(new dev.vellum.engine.style.Image.Url("test:css/img.png"),
                style(doc, "#b").backgroundLayers.get(0).image(), "urls resolve against the stylesheet");
        assertTrue(host.logs.contains("WARN: Stylesheet not found: test:missing.css"), host.logs::toString);
    }

    @Test
    void styleElementChangesAreSeen() {
        Document doc = page("<style id=s>#a { color: red }</style><template><style>#a { color: blue }</style></template><div id=a></div>");
        assertEquals(RED, style(doc, "#a").color, "styles inside templates are inert");
        element(doc, "#s").setTextContent("#a { color: green }");
        doc.styleEngine().restyle();
        assertEquals(GREEN, style(doc, "#a").color);
    }

    @Test
    void invalidDeclarationsAreLoggedAtDebug() {
        TestHost host = new TestHost();
        page(host, "<style>\n#a { colr: red }</style><div id=a style='width: nope'></div>");
        assertTrue(host.logs.contains("DEBUG: test:page.html:2: Invalid declaration 'colr: red'"), host.logs::toString);
        assertTrue(host.logs.stream().anyMatch(l -> l.startsWith("DEBUG:") && l.contains("'width: nope'")));
        assertTrue(host.errors.isEmpty());
    }

    @Test
    void everyElementGetsAStyle() {
        Document doc = page("<div><span>a</span><template><b>t</b></template></div><svg-thing></svg-thing>");
        doc.querySelectorAll("*").forEach(e -> assertTrue(e.baseStyle != null && e.style != null, e.toString()));
        assertEquals(WHITE, style(doc, "span").color);
    }
}
