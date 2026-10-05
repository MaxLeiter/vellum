package dev.vellum.engine.css;

import dev.vellum.engine.style.Prop;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CssParserTest {
    private final List<String> problems = new ArrayList<>();

    private Stylesheet sheet(String css) {
        return Stylesheet.parse(css, "test:a.css", null, problems::add);
    }

    private static List<String> names(List<Decl> decls) {
        return decls.stream().map(d -> d.isCustom() ? d.customName : d.property.name).toList();
    }

    @Test
    void declarationsWithImportantAndRecovery() {
        List<CssParser.Declaration> d = CssParser.parseDeclarations(
                "color: red ! important; ; width 10px; height: 5px; --X: { a; b } ;margin:0");
        assertEquals(List.of("color", "height", "--X", "margin"), d.stream().map(CssParser.Declaration::name).toList());
        assertTrue(d.get(0).important());
        assertEquals("red", ComponentValue.text(d.get(0).value()));
        assertEquals("{ a; b }", ComponentValue.text(d.get(2).value()));
    }

    @Test
    void rulesRecoverFromErrors() {
        Stylesheet s = sheet("""
                a { color: red; bogus: 1; width: -5px }
                b) { color: blue }
                c { color: green }
                @unknown foo { x { y: z } }
                d { color: #fff
                """);
        // a (color only), c, and d (closed at EOF).
        List<Stylesheet.StyleRule> rules = s.rules.stream().map(r -> (Stylesheet.StyleRule) r).toList();
        assertEquals(List.of("a", "c", "d"), rules.stream().map(r -> r.selectors().get(0).subject().tag()).toList());
        assertEquals(List.of("color"), names(rules.get(0).decls()));
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("2: Invalid selector 'b)'")), problems::toString);
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("1: Invalid declaration 'bogus")), problems::toString);
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("1: Invalid declaration 'width")), problems::toString);
        assertTrue(problems.stream().anyMatch(p -> p.contains("Unknown at-rule @unknown")), problems::toString);
    }

    @Test
    void lineNumbersInProblems() {
        sheet("a {\n  color: red;\n  colour: red;\n}");
        assertEquals(List.of("3: Invalid declaration 'colour: red'"), problems);
    }

    @Test
    void shorthandsExpandToLonghands() {
        Stylesheet s = sheet("a { margin: 1px 2px; border: 1px solid red }");
        List<Decl> decls = ((Stylesheet.StyleRule) s.rules.get(0)).decls();
        assertEquals(List.of("margin-top", "margin-right", "margin-bottom", "margin-left"), names(decls.subList(0, 4)));
        assertEquals(16, decls.size());
    }

    @Test
    void constantValuesAreComputedOnce() {
        List<Decl> decls = ((Stylesheet.StyleRule) sheet("a { width: 10px; height: 2em; color: currentColor }")
                .rules.get(0)).decls();
        assertEquals(dev.vellum.engine.style.Length.px(10), decls.get(0).constant);
        assertNull(decls.get(1).constant, "em depends on the element");
        assertNull(decls.get(2).constant, "currentColor depends on the element");
    }

    @Test
    void atRules() {
        Stylesheet s = sheet("""
                @charset "utf-8";
                @import url(b.css) screen;
                @import "c.css";
                a { color: red }
                @import "late.css";
                @media (min-width: 100px) { b { color: blue } }
                @supports (display: grid) and (not (display: nonsense)) { c { color: green } }
                @supports (display: nonsense) { d { color: green } }
                @layer base { e { color: red } }
                @font-face { font-family: x }
                @keyframes spin { from { opacity: 0 } 50%, 75% { opacity: .5 } to { opacity: 1 } }
                """);
        assertEquals(2, s.rules.stream().filter(r -> r instanceof Stylesheet.ImportRule).count());
        assertEquals("test:b.css", ((Stylesheet.ImportRule) s.rules.get(0)).url());
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("5: Ignored @import")), problems::toString);
        assertTrue(s.rules.stream().anyMatch(r -> r instanceof Stylesheet.MediaRule));
        List<String> tags = s.rules.stream().filter(r -> r instanceof Stylesheet.StyleRule)
                .map(r -> ((Stylesheet.StyleRule) r).selectors().get(0).subject().tag()).toList();
        assertEquals(List.of("a", "c", "e"), tags);
        Stylesheet.KeyframesRule k = (Stylesheet.KeyframesRule) s.rules.get(s.rules.size() - 1);
        assertEquals("spin", k.name());
        assertEquals(List.of(0.5f, 0.75f), k.keyframes().get(1).offsets());
    }

    @Test
    void keyframesIgnoreImportant() {
        Stylesheet s = sheet("@keyframes k { to { opacity: 1 !important; width: 5px } }");
        List<Decl> decls = ((Stylesheet.KeyframesRule) s.rules.get(0)).keyframes().get(0).decls();
        assertEquals(List.of("width"), names(decls));
    }

    @Test
    void varInShorthandIsPending() {
        List<Decl> decls = ((Stylesheet.StyleRule) sheet("a { margin: var(--m) 2px }").rules.get(0)).decls();
        assertEquals(4, decls.size());
        assertTrue(decls.stream().allMatch(d -> d.hasVar && d.pending != null && d.pending.name().equals("margin")));
    }

    @Test
    void cssWideKeywords() {
        List<Decl> decls = ((Stylesheet.StyleRule) sheet("a { color: inherit; padding: initial; width: revert }")
                .rules.get(0)).decls();
        assertEquals(Decl.Keyword.INHERIT, decls.get(0).keyword);
        assertEquals(Decl.Keyword.INITIAL, decls.get(1).keyword);
        assertEquals(Decl.Keyword.UNSET, decls.get(5).keyword);
    }

    @Test
    void everyPropHasALonghandOrIsDerived() {
        for (Prop p : Prop.values()) {
            boolean derived = p == Prop.BACKGROUND_LAYERS || p == Prop.TRANSITION || p == Prop.ANIMATION
                    || p == Prop.CUSTOM_PROPERTIES;
            assertEquals(!derived, Properties.of(p) != null, p.name());
        }
        assertFalse(Properties.all().isEmpty());
    }
}
