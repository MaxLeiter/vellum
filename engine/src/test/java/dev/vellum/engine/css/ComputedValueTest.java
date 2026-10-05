package dev.vellum.engine.css;

import dev.vellum.engine.style.ComputedStyle;
import org.junit.jupiter.api.Test;

import static dev.vellum.engine.css.StyleTesting.computed;
import static dev.vellum.engine.css.StyleTesting.styleOf;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ComputedValueTest {
    @Test
    void longhands() {
        ComputedStyle s = styleOf("width: calc(50% + 2px); height: 1.5em; color: rgba(255, 0, 0, .5); "
                + "display: inline-block; opacity: .25; z-index: 2; border-top: 1px solid; font-weight: bold; "
                + "line-height: 1.5; transform: translate(1px, 2px) rotate(45deg); font-family: monospace, 'A B'; "
                + "--x: 1px  2px; content: 'hi'; aspect-ratio: 2; grid-template-columns: repeat(2, 1fr) 10px; "
                + "grid-row: span 2; text-shadow: minecraft; box-shadow: 1px 2px red inset");
        assertEquals("calc(50% + 2px)", computed(s, "width"));
        assertEquals("12px", computed(s, "height"));
        assertEquals("rgba(255, 0, 0, 0.502)", computed(s, "color"));
        assertEquals("inline-block", computed(s, "display"));
        assertEquals("0.25", computed(s, "opacity"));
        assertEquals("2", computed(s, "z-index"));
        assertEquals("auto", computed(styleOf("color: red"), "z-index"));
        assertEquals("1px", computed(s, "border-top-width"));
        assertEquals("0px", computed(s, "border-left-width"));
        assertEquals("solid", computed(s, "border-top-style"));
        assertEquals("700", computed(s, "font-weight"));
        assertEquals("1.5", computed(s, "line-height"));
        assertEquals("normal", computed(styleOf("color: red"), "line-height"));
        assertEquals("translate(1px, 2px) rotate(45deg)", computed(s, "transform"));
        assertEquals("minecraft:uniform, A B", computed(s, "font-family"));
        assertEquals("1px  2px", computed(s, "--x"));
        assertEquals("\"hi\"", computed(s, "content"));
        assertEquals("2", computed(s, "aspect-ratio"));
        assertEquals("1fr 1fr 10px", computed(s, "grid-template-columns"));
        assertEquals("span 2", computed(s, "grid-row-start"));
        assertEquals("minecraft", computed(s, "text-shadow"));
        assertEquals("rgb(255, 0, 0) 1px 2px 0px 0px inset", computed(s, "box-shadow"));
        assertEquals("none", computed(styleOf("color: red"), "transform"));
        assertEquals("", computed(s, "nonsense"));
    }

    @Test
    void shorthands() {
        ComputedStyle s = styleOf("margin: 1px 2px; padding: 3px; inset: 1px 2px 3px 4px; border-radius: 1px 1px 2px 1px; "
                + "gap: 4px; overflow: hidden scroll; border: 2px dashed blue; flex: 1; "
                + "text-decoration: underline line-through; grid-area: a / b");
        assertEquals("1px 2px", computed(s, "margin"));
        assertEquals("3px", computed(s, "padding"));
        assertEquals("1px 2px 3px 4px", computed(s, "inset"));
        assertEquals("1px 1px 2px", computed(s, "border-radius"));
        assertEquals("4px", computed(s, "gap"));
        assertEquals("hidden scroll", computed(s, "overflow"));
        assertEquals("2px dashed rgb(0, 0, 255)", computed(s, "border"));
        assertEquals("2px", computed(s, "border-width"));
        assertEquals("1 1 0px", computed(s, "flex"));
        assertEquals("underline line-through", computed(s, "text-decoration"));
        assertEquals("a / b / a / b", computed(s, "grid-area"));
        assertEquals("", computed(styleOf("border-top: 1px solid"), "border"), "sides differ");
    }

    @Test
    void lists() {
        ComputedStyle s = styleOf("transition: opacity .2s linear, transform 1s ease 50ms; animation: spin 2s infinite; "
                + "background: url(a.png) center / cover no-repeat, red");
        assertEquals("opacity 0.2s linear 0s, transform 1s ease 0.05s", computed(s, "transition"));
        assertEquals("0.2s, 1s", computed(s, "transition-duration"));
        assertEquals("spin 2s ease 0s infinite normal none running", computed(s, "animation"));
        assertEquals("url(\"test:a.png\") 50% 50% / cover no-repeat no-repeat border-box rgb(255, 0, 0)",
                computed(s, "background"));
        assertEquals("url(\"test:a.png\")", computed(s, "background-image"));
        ComputedStyle none = styleOf("color: red");
        assertEquals("all 0s ease 0s", computed(none, "transition"));
        assertEquals("none", computed(none, "background-image"));
        assertEquals("rgba(0, 0, 0, 0)", computed(none, "background-color"));
    }
}
