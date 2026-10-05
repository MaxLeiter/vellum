package dev.vellum.engine.anim;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.event.TransitionEvent;
import dev.vellum.engine.style.Length;
import dev.vellum.engine.style.Prop;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.RecordingCanvas;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Used styles: animations on ::before and ::after, and values that follow animated ones because they are inherited
 * or computed from {@code color} ({@code currentColor}) or {@code font-size} ({@code em}).
 */
class UsedStyleTest {
    private static final int RED = 0xFFFF0000, BLUE = 0xFF0000FF, GREEN = 0xFF008000, WHITE = 0xFFFFFFFF;
    private static final int HALFWAY = (Integer) Interpolate.value(Prop.COLOR, RED, BLUE, 0.5f);

    @Test
    void pseudoElementsRunAnimationsAndTransitions() {
        Page page = new TestHost().load("""
                <style>
                  @keyframes bgc { from { background: red } to { background: blue } }
                  .p { position: relative; width: 40px; height: 20px }
                  .p::before { content: ""; position: absolute; width: 16px; height: 16px; animation: bgc 1s linear infinite }
                  .p::after { content: "x"; transition: opacity 100ms linear }
                  .p.off::after { opacity: 0 }
                </style>
                <div class=p id=p></div>""");
        Element p = page.byId("p");
        assertEquals(RED, p.beforeStyle.backgroundColor);
        page.frame(500);
        assertEquals(HALFWAY, p.beforeStyle.backgroundColor);
        assertEquals(0, p.beforeBaseStyle.backgroundColor, "the base style is the cascade's alone");
        RecordingCanvas canvas = page.paint();
        RecordingCanvas.Call fill = canvas.fill(HALFWAY);
        assertEquals(16, fill.w(), 0.01);

        List<String> events = new ArrayList<>();
        p.addEventListener("transitionend", e -> events.add(((TransitionEvent) e).pseudoElement));
        p.addClass("off");
        page.frame(1000);
        page.frame(1050);
        assertEquals(0.5f, p.afterStyle.opacity, 1e-4);
        page.frame(1100);
        assertEquals(0, p.afterStyle.opacity);
        assertEquals(List.of("::after"), events);
    }

    @Test
    void currentColorFollowsAnAnimatedColor() {
        Page page = new TestHost().load("""
                <style>
                  @keyframes hue { from { color: #f00 } to { color: #00f } }
                  .b { animation: hue 10s linear -5s infinite; border: 1px solid; box-shadow: 0 0 2px; -mc-tint: currentColor }
                  .b div { background-color: currentColor }
                  .b .own { color: green }
                </style>
                <div class=b id=b><div id=child>text</div><div class=own id=own></div></div>""");
        Element b = page.byId("b"), child = page.byId("child"), own = page.byId("own");
        assertEquals(HALFWAY, b.style.color);
        assertEquals(HALFWAY, b.style.borderTopColor, "border colours default to currentColor");
        assertEquals(HALFWAY, b.style.boxShadow.getFirst().color(), "a shadow without a colour is currentColor");
        assertEquals(HALFWAY, b.style.tint);
        assertEquals(HALFWAY, child.style.color, "descendants inherit the animated colour");
        assertEquals(HALFWAY, child.style.backgroundColor, "and resolve currentColor against it");
        assertEquals(GREEN, own.style.backgroundColor, "unless they set their own");
        assertTrue(page.paint().texts().contains("text"));
        assertEquals(HALFWAY, page.paint().ops("drawText").getFirst().color());

        page.frame(2500); // a quarter turn later: three quarters of the way
        int later = (Integer) Interpolate.value(Prop.COLOR, RED, BLUE, 0.75f);
        assertEquals(later, b.style.borderTopColor);
        assertEquals(later, child.style.backgroundColor);
        assertEquals(WHITE, b.baseStyle.color, "base styles are the cascade's alone");
        assertEquals(WHITE, child.baseStyle.backgroundColor);
    }

    @Test
    void emFollowsAnAnimatedFontSize() {
        Page page = new TestHost().load("""
                <style>
                  @keyframes big { from { font-size: 8px } to { font-size: 16px } }
                  #f { animation: big 100ms linear; padding-left: 1em; width: 0 }
                </style>
                <div id=f><span id=s style="margin-left: 1em"></span></div>""");
        page.frame(50);
        Element f = page.byId("f");
        assertEquals(12, f.style.fontSize, 1e-4);
        assertEquals(Length.px(12), f.style.paddingLeft);
        assertEquals(Length.px(12), page.byId("s").style.marginLeft, "children inherit the animated font-size");
        assertEquals(12, f.box.paddingLeft, 1e-4, "and layout follows");
        page.frame(100);
        assertSame(f.baseStyle, f.style, "once it ends nothing is animated");
    }

    @Test
    void childrenFollowATransitioningColor() {
        Page page = new TestHost().load("""
                <style>
                  #p { color: red; transition: color 100ms linear }
                  #p.on { color: blue }
                </style>
                <div id=p><span id=s>hi</span></div>""");
        page.byId("p").addClass("on");
        page.frame(16);
        page.frame(66);
        assertEquals(HALFWAY, page.byId("s").style.color);
        page.frame(116);
        assertEquals(BLUE, page.byId("s").style.color);
        assertSame(page.byId("s").baseStyle, page.byId("s").style);
    }
}
