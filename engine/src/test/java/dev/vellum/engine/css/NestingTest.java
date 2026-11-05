package dev.vellum.engine.css;

import dev.vellum.engine.style.Length;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NestingTest {
    private static final int RED = 0xFFFF0000, BLUE = 0xFF0000FF, GREEN = 0xFF008000, DEFAULT = 0xFFFFFFFF;

    @Test
    void ampersandWithCombinators() {
        Page page = new TestHost().load("""
                <style>
                  .window {
                    color: red;
                    & > .header { color: blue }
                    & .deep { color: green }
                    &.active { margin-left: 3px }
                    + .next { margin-left: 4px }
                  }
                </style>
                <div class="window active" id=w><div class=header id=h><span class=deep id=d>x</span></div></div>
                <div class=next id=n><div class=header id=other>y</div></div>""");
        assertEquals(RED, page.style("#w").color);
        assertEquals(BLUE, page.style("#h").color);
        assertEquals(GREEN, page.style("#d").color);
        assertEquals(Length.px(3), page.style("#w").marginLeft);
        assertEquals(Length.px(4), page.style("#n").marginLeft);
        assertEquals(DEFAULT, page.style("#other").color, "> .header only matches inside .window");
    }

    @Test
    void selectorsWithoutAmpersandAreDescendants() {
        Page page = new TestHost().load("""
                <style>.a { .b { color: red } p { color: blue } }</style>
                <div class=a><span class=b id=b>x</span><p id=p>y</p></div><span class=b id=out>z</span>""");
        assertEquals(RED, page.style("#b").color);
        assertEquals(BLUE, page.style("#p").color);
        assertEquals(DEFAULT, page.style("#out").color);
    }

    @Test
    void pseudoClassesAndDeepNesting() {
        Page page = new TestHost().load("""
                <style>
                  ul { li { &:first-child { color: red } &:not(:first-child) { color: blue } } }
                  .x, .y { & & { color: green } }
                  div:hover { color: red }
                  .c { div:first-child { color: green } }
                </style>
                <ul><li id=one>1</li><li id=two>2</li></ul>
                <div class=x><div class=y id=xy>x</div></div>
                <div class=c><div id=cd>c</div></div>""");
        assertEquals(RED, page.style("#one").color);
        assertEquals(BLUE, page.style("#two").color);
        assertEquals(GREEN, page.style("#xy").color);
        assertEquals(GREEN, page.style("#cd").color, "a nested rule may start like a declaration");
    }

    @Test
    void specificityIsTheParents() {
        // & is :is(#id .a): (1,1,0) + (0,1,0) beats .a.b.c (0,3,0).
        Page page = new TestHost().load("""
                <style>
                  #id .a { & .b { color: red } }
                  .a .b.b.b { color: blue }
                </style>
                <div id=id><div class=a><div class=b id=t>x</div></div></div>""");
        assertEquals(RED, page.style("#t").color);
    }

    @Test
    void declarationsAfterNestedRulesKeepSourceOrder() {
        Page page = new TestHost().load("""
                <style>.a { color: red; & { color: blue } color: green; }</style>
                <div class=a id=a>x</div>""");
        assertEquals(GREEN, page.style("#a").color);
    }

    @Test
    void nestedAtRules() {
        Page page = new TestHost().load("""
                <style>
                  .a {
                    @media (min-width: 1px) { color: red; & .b { color: blue } }
                    @media (max-width: 1px) { margin-left: 9px }
                    @supports (display: flex) { margin-top: 2px }
                  }
                </style>
                <div class=a id=a><div class=b id=b>x</div></div>""");
        assertEquals(RED, page.style("#a").color);
        assertEquals(BLUE, page.style("#b").color);
        assertEquals(Length.px(0), page.style("#a").marginLeft);
        assertEquals(Length.px(2), page.style("#a").marginTop);
    }

    @Test
    void customPropertiesMayHoldBlocks() {
        List<String> log = new ArrayList<>();
        Stylesheet sheet = Stylesheet.parse(".a { --x: { a: b }; color: red; .b { color: blue } }", null, null,
                log::add);
        assertEquals(2, sheet.rules.size());
        assertTrue(log.isEmpty(), log.toString());
    }
}
