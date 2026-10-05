package dev.vellum.engine.css;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.style.BackgroundLayer;
import dev.vellum.engine.style.Length;
import dev.vellum.engine.style.Prop;
import dev.vellum.engine.style.TimingFunction;
import dev.vellum.engine.style.TransformFunction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static dev.vellum.engine.css.StyleTesting.element;
import static dev.vellum.engine.css.StyleTesting.page;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyframesTest {
    private static final String PAGE = """
            <style>
              #a { color: red; font-size: 10px; --gap: 3px; width: 7px }
              @keyframes grow {
                to { width: 2em; background-color: currentColor; margin-left: var(--gap) }
                0%, 50% { width: 1px; animation-timing-function: ease-in }
                from { opacity: 0.5 }
                75% { background: url(x.png) }
                100% { transition: none; animation-name: other }
              }
              @keyframes grow { 50% { opacity: 1 } }
              @media (max-width: 1px) { @keyframes hidden { to { opacity: 0 } } }
            </style>
            <div style="color: blue"><div id=a></div></div>""";

    @Test
    void resolvesKeyframesForTheElement() {
        Document doc = page(PAGE.replace("@keyframes grow { 50% { opacity: 1 } }", ""));
        Element a = element(doc, "#a");
        List<ResolvedKeyframe> frames = doc.styleEngine().resolveKeyframes(a, "grow", a.baseStyle);
        assertEquals(List.of(0f, 0.5f, 0.75f, 1f), frames.stream().map(ResolvedKeyframe::offset).toList());

        ResolvedKeyframe first = frames.get(0);
        assertEquals(Length.px(1), first.style().width, "from and 0% merge in order: 0% comes later");
        assertEquals(0.5f, first.style().opacity);
        assertEquals(Set.of(Prop.WIDTH, Prop.OPACITY), first.props());
        assertEquals(TimingFunction.EASE_IN, first.timing());
        assertEquals(0xFFFF0000, first.style().color, "unset properties keep the base value");

        ResolvedKeyframe last = frames.get(3);
        assertEquals(Length.px(20), last.style().width, "em resolves against the element");
        assertEquals(0xFFFF0000, last.style().backgroundColor, "currentColor resolves against the element");
        assertEquals(Length.px(3), last.style().marginLeft, "var() resolves against the element");
        assertEquals(Set.of(Prop.WIDTH, Prop.BACKGROUND_COLOR, Prop.MARGIN_LEFT), last.props(),
                "animation and transition properties are ignored");
        assertNull(last.timing());

        BackgroundLayer layer = frames.get(2).style().backgroundLayers.get(0);
        assertEquals("test:x.png", ((dev.vellum.engine.style.Image.Url) layer.image()).url());
        assertTrue(frames.get(2).props().contains(Prop.BACKGROUND_LAYERS));
        assertSame(a.baseStyle.transitions, frames.get(1).style().transitions, "the base is copied, not changed");
    }

    @Test
    void laterRuleWithTheSameNameWins() {
        Document doc = page(PAGE);
        Element a = element(doc, "#a");
        List<ResolvedKeyframe> frames = doc.styleEngine().resolveKeyframes(a, "grow", a.baseStyle);
        assertEquals(1, frames.size());
        assertEquals(0.5f, frames.get(0).offset());
    }

    @Test
    void unknownOrInactiveRulesGiveNothing() {
        Document doc = page(PAGE);
        Element a = element(doc, "#a");
        assertTrue(doc.styleEngine().resolveKeyframes(a, "nope", a.baseStyle).isEmpty());
        assertTrue(doc.styleEngine().resolveKeyframes(a, "hidden", a.baseStyle).isEmpty(), "@media does not match");
    }

    @Test
    void computesScriptDeclarations() {
        Document doc = page(PAGE);
        Element a = element(doc, "#a");
        ResolvedKeyframe k = doc.styleEngine().computeDeclarations(a,
                "opacity: 0; transform: scale(2); padding: 1em; bogus: 1", a.baseStyle);
        assertEquals(0, k.offset());
        assertEquals(0f, k.style().opacity);
        assertEquals(List.of(new TransformFunction.Scale(2, 2)), k.style().transform);
        assertEquals(Length.px(10), k.style().paddingLeft);
        assertEquals(Set.of(Prop.OPACITY, Prop.TRANSFORM, Prop.PADDING_TOP, Prop.PADDING_RIGHT, Prop.PADDING_BOTTOM,
                Prop.PADDING_LEFT), k.props());
        assertEquals(Length.px(7), k.style().width);
        assertEquals(1f, a.baseStyle.opacity, "the base style is untouched");
    }

    @Test
    void lineHeightFactorFollowsKeyframeFontSize() {
        Document doc = page("<div id=a style='font-size: 10px; line-height: 2'></div>");
        Element a = element(doc, "#a");
        ResolvedKeyframe k = doc.styleEngine().computeDeclarations(a, "font-size: 20px", a.baseStyle);
        assertEquals(40, k.style().lineHeight);
    }
}
