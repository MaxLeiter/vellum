package dev.vellum.engine.css;

import dev.vellum.engine.style.ComputedStyle;
import org.junit.jupiter.api.Test;

import static dev.vellum.engine.css.StyleEngine.computedValue;
import static dev.vellum.engine.testing.Page.styleOf;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ComputedValueTest {
    @Test
    void longhands() {
        ComputedStyle s = styleOf("width: calc(50% + 2px); height: 1.5em; color: rgba(255, 0, 0, .5); "
                + "display: inline-block; opacity: .25; z-index: 2; border-top: 1px solid; font-weight: bold; "
                + "line-height: 1.5; transform: translate(1px, 2px) rotate(45deg); font-family: monospace, 'A B'; "
                + "--x: 1px  2px; content: 'hi'; aspect-ratio: 2; grid-template-columns: repeat(2, 1fr) 10px; "
                + "grid-row: span 2; text-shadow: minecraft; box-shadow: 1px 2px red inset");
        assertEquals("calc(50% + 2px)", computedValue(s, "width"));
        assertEquals("12px", computedValue(s, "height"));
        assertEquals("rgba(255, 0, 0, 0.502)", computedValue(s, "color"));
        assertEquals("inline-block", computedValue(s, "display"));
        assertEquals("0.25", computedValue(s, "opacity"));
        assertEquals("2", computedValue(s, "z-index"));
        assertEquals("auto", computedValue(styleOf("color: red"), "z-index"));
        assertEquals("1px", computedValue(s, "border-top-width"));
        assertEquals("0px", computedValue(s, "border-left-width"));
        assertEquals("solid", computedValue(s, "border-top-style"));
        assertEquals("700", computedValue(s, "font-weight"));
        assertEquals("1.5", computedValue(s, "line-height"));
        assertEquals("normal", computedValue(styleOf("color: red"), "line-height"));
        assertEquals("translate(1px, 2px) rotate(45deg)", computedValue(s, "transform"));
        assertEquals("minecraft:uniform, A B", computedValue(s, "font-family"));
        assertEquals("1px  2px", computedValue(s, "--x"));
        assertEquals("\"hi\"", computedValue(s, "content"));
        assertEquals("2", computedValue(s, "aspect-ratio"));
        assertEquals("1fr 1fr 10px", computedValue(s, "grid-template-columns"));
        assertEquals("span 2", computedValue(s, "grid-row-start"));
        assertEquals("minecraft", computedValue(s, "text-shadow"));
        assertEquals("rgb(255, 0, 0) 1px 2px 0px 0px inset", computedValue(s, "box-shadow"));
        assertEquals("none", computedValue(styleOf("color: red"), "transform"));
        assertEquals("", computedValue(s, "nonsense"));
    }

    @Test
    void shorthands() {
        ComputedStyle s = styleOf("margin: 1px 2px; padding: 3px; inset: 1px 2px 3px 4px; border-radius: 1px 1px 2px 1px; "
                + "gap: 4px; overflow: hidden scroll; border: 2px dashed blue; flex: 1; "
                + "text-decoration: underline line-through; grid-area: a / b");
        assertEquals("1px 2px", computedValue(s, "margin"));
        assertEquals("3px", computedValue(s, "padding"));
        assertEquals("1px 2px 3px 4px", computedValue(s, "inset"));
        assertEquals("1px 1px 2px", computedValue(s, "border-radius"));
        assertEquals("4px", computedValue(s, "gap"));
        assertEquals("hidden scroll", computedValue(s, "overflow"));
        assertEquals("2px dashed rgb(0, 0, 255)", computedValue(s, "border"));
        assertEquals("2px", computedValue(s, "border-width"));
        assertEquals("1 1 0px", computedValue(s, "flex"));
        assertEquals("underline line-through", computedValue(s, "text-decoration"));
        assertEquals("a / b / a / b", computedValue(s, "grid-area"));
        assertEquals("", computedValue(styleOf("border-top: 1px solid"), "border"), "sides differ");
    }

    @Test
    void lists() {
        ComputedStyle s = styleOf("transition: opacity .2s linear, transform 1s ease 50ms; animation: spin 2s infinite; "
                + "background: url(a.png) center / cover no-repeat, red");
        assertEquals("opacity 0.2s linear 0s, transform 1s ease 0.05s", computedValue(s, "transition"));
        assertEquals("0.2s, 1s", computedValue(s, "transition-duration"));
        assertEquals("spin 2s ease 0s infinite normal none running", computedValue(s, "animation"));
        assertEquals("url(\"test:a.png\") 50% 50% / cover no-repeat no-repeat border-box rgb(255, 0, 0)",
                computedValue(s, "background"));
        assertEquals("url(\"test:a.png\")", computedValue(s, "background-image"));
        ComputedStyle none = styleOf("color: red");
        assertEquals("all 0s ease 0s", computedValue(none, "transition"));
        assertEquals("none", computedValue(none, "background-image"));
        assertEquals("rgba(0, 0, 0, 0)", computedValue(none, "background-color"));
    }
}
