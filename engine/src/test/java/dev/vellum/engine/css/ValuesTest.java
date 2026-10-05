package dev.vellum.engine.css;

import dev.vellum.engine.style.Align;
import dev.vellum.engine.style.AnimationSpec;
import dev.vellum.engine.style.BackgroundLayer;
import dev.vellum.engine.style.BorderStyle;
import dev.vellum.engine.style.BoxSizing;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Cursor;
import dev.vellum.engine.style.Display;
import dev.vellum.engine.style.FlexDirection;
import dev.vellum.engine.style.FlexWrap;
import dev.vellum.engine.style.GridAutoFlow;
import dev.vellum.engine.style.GridLine;
import dev.vellum.engine.style.GridTrack;
import dev.vellum.engine.style.Image;
import dev.vellum.engine.style.ImageRendering;
import dev.vellum.engine.style.Length;
import dev.vellum.engine.style.ObjectFit;
import dev.vellum.engine.style.Overflow;
import dev.vellum.engine.style.PointerEvents;
import dev.vellum.engine.style.Position;
import dev.vellum.engine.style.Shadow;
import dev.vellum.engine.style.TextAlign;
import dev.vellum.engine.style.TextOverflow;
import dev.vellum.engine.style.TextTransform;
import dev.vellum.engine.style.TimingFunction;
import dev.vellum.engine.style.TransformFunction;
import dev.vellum.engine.style.TransitionSpec;
import dev.vellum.engine.style.UserSelect;
import dev.vellum.engine.style.VerticalAlign;
import dev.vellum.engine.style.Visibility;
import dev.vellum.engine.style.WhiteSpace;
import dev.vellum.engine.style.WordBreak;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.vellum.engine.testing.Page.styleOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValuesTest {
    private static int color(String css) {
        return styleOf("color: " + css).color;
    }

    @Test
    void colors() {
        assertEquals(0xFFFF0000, color("#f00"));
        assertEquals(0x88FF0000, color("#f008"));
        assertEquals(0xFF123456, color("#123456"));
        assertEquals(0x80123456, color("#12345680"));
        assertEquals(0xFF0A141E, color("rgb(10, 20, 30)"));
        assertEquals(0x800A141E, color("rgba(10, 20, 30, .5)"));
        assertEquals(0x800A141E, color("rgb(10 20 30 / 50%)"));
        assertEquals(0xFFFF8000, color("rgb(100% 50% 0%)"));
        assertEquals(0xFF000000, color("rgb(none none none)"));
        assertEquals(0xFFFF0000, color("hsl(0, 100%, 50%)"));
        assertEquals(0xFF00FF00, color("hsl(120deg 100% 50%)"));
        assertEquals(0x800000FF, color("hsla(240, 100%, 50%, 0.5)"));
        assertEquals(0xFF663399, color("RebeccaPurple"));
        assertEquals(0xFFFFAA00, color("mc-gold"));
        assertEquals(0xFF555555, color("mc-dark-gray"));
        assertEquals(0, color("transparent"));
        assertEquals(0xFF800080, color("color-mix(in srgb, red, blue)"));
        assertEquals(0xFFBF0040, color("color-mix(in srgb, red 75%, blue)"));
        assertEquals(0x80800080, color("color-mix(in srgb, red 25%, blue 25%)"));
        // Invalid colours leave the inherited white.
        for (String bad : List.of("#ff", "rgb(1, 2)", "rgb(1 2 3 4)", "rgb(1, 2 3)", "nocolor", "hsl(1px 1% 1%)")) {
            assertEquals(0xFFFFFFFF, color(bad), bad);
        }
    }

    @Test
    void sizesAndKeywords() {
        ComputedStyle s = styleOf("width: min-content; height: fit-content; min-width: max-content; "
                + "max-height: none; max-width: 50%; min-height: auto");
        assertEquals(Length.MIN_CONTENT, s.width);
        assertEquals(Length.FIT_CONTENT, s.height);
        assertEquals(Length.MAX_CONTENT, s.minWidth);
        assertEquals(Length.NONE, s.maxHeight);
        assertEquals(Length.percent(50), s.maxWidth);
        assertEquals(Length.AUTO, s.minHeight);
        assertEquals(Length.AUTO, styleOf("width: -5px").width, "negative widths are invalid");
        assertEquals(Length.px(-5), styleOf("margin-left: -5px").marginLeft);
    }

    @Test
    void boxShorthands() {
        ComputedStyle s = styleOf("margin: 1px 2px 3px; padding: 4px 5px; inset: 1px auto");
        assertEquals(List.of(Length.px(1), Length.px(2), Length.px(3), Length.px(2)),
                List.of(s.marginTop, s.marginRight, s.marginBottom, s.marginLeft));
        assertEquals(List.of(Length.px(4), Length.px(5), Length.px(4), Length.px(5)),
                List.of(s.paddingTop, s.paddingRight, s.paddingBottom, s.paddingLeft));
        assertEquals(List.of(Length.px(1), Length.AUTO, Length.px(1), Length.AUTO), List.of(s.top, s.right, s.bottom, s.left));
        ComputedStyle r = styleOf("border-radius: 1px 2px 3px 4px / 9px");
        assertEquals(List.of(Length.px(1), Length.px(2), Length.px(3), Length.px(4)),
                List.of(r.radiusTopLeft, r.radiusTopRight, r.radiusBottomRight, r.radiusBottomLeft));
        assertEquals(Length.percent(50), styleOf("border-top-left-radius: 50% 10%").radiusTopLeft);
    }

    @Test
    void borders() {
        ComputedStyle s = styleOf("border: 2px dashed red; border-left: thick double; border-top-style: none; "
                + "border-right-width: thin");
        assertEquals(0, s.borderTopWidth);
        assertEquals(1, s.borderRightWidth);
        assertEquals(BorderStyle.DASHED, s.borderBottomStyle);
        assertEquals(0xFFFF0000, s.borderBottomColor);
        assertEquals(3, s.borderLeftWidth);
        assertEquals(BorderStyle.DOUBLE, s.borderLeftStyle);
        assertEquals(0xFFFFFFFF, s.borderLeftColor, "border-left reset the colour to currentColor");
        ComputedStyle o = styleOf("outline: 1px solid blue; outline-offset: -2px");
        assertEquals(1, o.outlineWidth);
        assertEquals(0xFF0000FF, o.outlineColor);
        assertEquals(-2, o.outlineOffset);
        ComputedStyle c = styleOf("border-style: solid; border-color: red blue; border-width: 1px 2px 3px 4px");
        assertEquals(0xFF0000FF, c.borderLeftColor);
        assertEquals(4, c.borderLeftWidth);
        assertEquals(BorderStyle.INSET, styleOf("border: inset").borderTopStyle);
    }

    @Test
    void images() {
        BackgroundLayer url = styleOf("background-image: url(img/a.png)").backgroundLayers.get(0);
        assertEquals(new Image.Url("test:img/a.png"), url.image());
        assertEquals(new Image.Sprite("minecraft:widget/button"),
                styleOf("background-image: sprite(minecraft:widget/button)").backgroundLayers.get(0).image());
        assertEquals(new Image.Sprite("mymod:a/b_c-1"),
                styleOf("background-image: sprite('mymod:a/b_c-1')").backgroundLayers.get(0).image());
        Image.LinearGradient g = (Image.LinearGradient) styleOf(
                "background-image: linear-gradient(to top right, red, blue 30% 60%, 70%, #000 10px)")
                .backgroundLayers.get(0).image();
        assertEquals(45, g.angleDeg());
        assertEquals(4, g.stops().size(), "two positions make two stops; hints are dropped");
        assertEquals(new Image.ColorStop(0xFF0000FF, Length.percent(60)), g.stops().get(2));
        assertEquals(180, ((Image.LinearGradient) styleOf("background-image: linear-gradient(red, blue)")
                .backgroundLayers.get(0).image()).angleDeg());
        assertEquals(90, ((Image.LinearGradient) styleOf("background-image: repeating-linear-gradient(0.25turn, red, blue)")
                .backgroundLayers.get(0).image()).angleDeg());
        Image.RadialGradient r = (Image.RadialGradient) styleOf(
                "background-image: radial-gradient(circle at left 10px top, red, blue)").backgroundLayers.get(0).image();
        assertTrue(r.circle());
        assertEquals(Length.px(10), r.centerX());
        assertEquals(Length.ZERO, r.centerY());
        assertTrue(styleOf("background-image: linear-gradient(red)").backgroundLayers.isEmpty(), "one stop is invalid");
        assertEquals(Image.RadialSize.FARTHEST_CORNER, r.size());
    }

    @Test
    void radialGradientSizes() {
        assertEquals(new Image.RadialSize(null, Length.px(40), Length.px(10)),
                radial("ellipse 40px 10px at 50% 50%").size());
        Image.RadialGradient circle = radial("circle 20px");
        assertTrue(circle.circle());
        assertEquals(new Image.RadialSize(null, Length.px(20), Length.px(20)), circle.size());
        assertTrue(radial("20px").circle(), "one length is a circle");
        assertFalse(radial("20px 50%").circle(), "two are an ellipse");
        assertEquals(Image.RadialSize.Extent.CLOSEST_CORNER, radial("closest-corner circle at top").size().extent());
        assertFalse(radial("farthest-side").circle());
        for (String invalid : List.of("circle 10%", "circle 1px 2px", "ellipse 5px", "closest-side 5px", "-5px",
                "circle circle")) {
            assertTrue(styleOf("background-image: radial-gradient(" + invalid + ", red, blue)").backgroundLayers.isEmpty(),
                    invalid);
        }
    }

    private static Image.RadialGradient radial(String prelude) {
        return (Image.RadialGradient) styleOf("background-image: radial-gradient(" + prelude + ", red, blue)")
                .backgroundLayers.getFirst().image();
    }

    @Test
    void backgroundShorthandAndLonghands() {
        ComputedStyle s = styleOf("background: url(a.png) right 4px bottom / 16px no-repeat padding-box, "
                + "sprite(x:y) center / cover repeat-x red");
        assertEquals(0xFFFF0000, s.backgroundColor);
        assertEquals(2, s.backgroundLayers.size());
        BackgroundLayer top = s.backgroundLayers.get(0);
        assertEquals(Length.of(-4, 100), top.positionX());
        assertEquals(Length.PERCENT_100, top.positionY());
        assertEquals(Length.px(16), top.width());
        assertEquals(Length.AUTO, top.height());
        assertEquals(BackgroundLayer.Repeat.NO_REPEAT, top.repeatX());
        assertEquals(BackgroundLayer.Box.PADDING_BOX, top.clip());
        BackgroundLayer bottom = s.backgroundLayers.get(1);
        assertEquals("cover", bottom.sizeKeyword());
        assertEquals(Length.PERCENT_50, bottom.positionX());
        assertEquals(BackgroundLayer.Repeat.REPEAT, bottom.repeatX());
        assertEquals(BackgroundLayer.Repeat.NO_REPEAT, bottom.repeatY());

        ComputedStyle longhands = styleOf("background-image: url(a.png), url(b.png), url(c.png); "
                + "background-size: 1px 2px, contain; background-position: top left, 5px; background-repeat: space round");
        List<BackgroundLayer> layers = longhands.backgroundLayers;
        assertEquals(3, layers.size());
        assertEquals("contain", layers.get(1).sizeKeyword());
        assertEquals(Length.px(1), layers.get(2).width(), "shorter component lists repeat");
        assertEquals(Length.px(5), layers.get(1).positionX());
        assertEquals(Length.PERCENT_50, layers.get(1).positionY());
        assertEquals(BackgroundLayer.Repeat.ROUND, layers.get(2).repeatY());

        ComputedStyle colorOnly = styleOf("background: #00f");
        assertEquals(0xFF0000FF, colorOnly.backgroundColor);
        assertEquals(1, colorOnly.backgroundLayers.size(), "a layer without an image");
        assertFalse(colorOnly.hasBackgroundImage());
        assertEquals(0, styleOf("background: red url(a.png), blue").backgroundColor, "colour only in the last layer");
    }

    @Test
    void shadows() {
        List<Shadow> box = styleOf("color: red; box-shadow: inset 1px 2px 3px 4px blue, 0 1px").boxShadow;
        assertEquals(new Shadow(1, 2, 3, 4, 0xFF0000FF, true), box.get(0));
        assertEquals(new Shadow(0, 1, 0, 0, 0xFFFF0000, false), box.get(1), "the colour defaults to currentColor");
        assertEquals(List.of(Shadow.MINECRAFT), styleOf("text-shadow: minecraft").textShadow);
        assertEquals(List.of(new Shadow(1, 1, 0, 0, 0xFF000000, false)), styleOf("text-shadow: black 1px 1px").textShadow);
        assertTrue(styleOf("text-shadow: 1px 1px 1px 1px").textShadow.isEmpty(), "no spread on text shadows");
        assertTrue(styleOf("box-shadow: none").boxShadow.isEmpty());
    }

    @Test
    void transforms() {
        List<TransformFunction> t = styleOf("transform: translate(10px, 50%) translateY(2px) scale(2) scaleX(50%) "
                + "rotate(0.5turn) skew(10deg) matrix(1, 0, 0, 1, 5, 6) rotateZ(90deg)").transform;
        assertEquals(List.of(new TransformFunction.Translate(Length.px(10), Length.percent(50)),
                new TransformFunction.Translate(Length.ZERO, Length.px(2)), new TransformFunction.Scale(2, 2),
                new TransformFunction.Scale(0.5f, 1), new TransformFunction.Rotate(180),
                new TransformFunction.Skew(10, 0), new TransformFunction.Matrix(1, 0, 0, 1, 5, 6),
                new TransformFunction.Rotate(90)), t);
        assertEquals(new TransformFunction.Rotate((float) Math.toDegrees(1)),
                styleOf("transform: rotate(1rad)").transform.get(0));
        assertTrue(styleOf("transform: rotate(10px)").transform.isEmpty());
        ComputedStyle o = styleOf("transform-origin: top left");
        assertEquals(Length.ZERO, o.transformOriginX);
        assertEquals(Length.ZERO, o.transformOriginY);
        ComputedStyle o2 = styleOf("transform-origin: 10px bottom 5px");
        assertEquals(Length.px(10), o2.transformOriginX);
        assertEquals(Length.PERCENT_100, o2.transformOriginY);
        assertEquals(Length.PERCENT_50, styleOf("transform-origin: right").transformOriginY);
    }

    @Test
    void transitions() {
        assertEquals(List.of(new TransitionSpec("opacity", 200, 50, TimingFunction.EASE_IN),
                        new TransitionSpec("all", 1000, 0, TimingFunction.EASE)),
                styleOf("transition: opacity .2s ease-in 50ms, 1s").transitions);
        List<TransitionSpec> longhands = styleOf("transition-property: color, width, height; "
                + "transition-duration: 1s, 2s; transition-timing-function: linear").transitions;
        assertEquals(List.of(1000f, 2000f, 1000f), longhands.stream().map(TransitionSpec::durationMs).toList());
        assertTrue(longhands.stream().allMatch(t -> t.timing() == TimingFunction.LINEAR));
        assertEquals(List.of(new TransitionSpec("all", 300, 0, TimingFunction.EASE)),
                styleOf("transition-duration: .3s").transitions, "transition-property defaults to all");
        assertTrue(styleOf("transition: none").transitions.isEmpty());
        assertEquals(List.of(new TransitionSpec("all", 1000, 0, TimingFunction.EASE), new TransitionSpec("opacity", 0, 0,
                TimingFunction.EASE)), styleOf("transition: all 1s, opacity 0s").transitions,
                "an entry that never runs stays, to keep its property from the earlier 'all'");
    }

    @Test
    void animations() {
        AnimationSpec a = styleOf("animation: 1s ease-out 2s infinite alternate both paused spin").animations.get(0);
        assertEquals(new AnimationSpec("spin", 1000, 2000, TimingFunction.EASE_OUT, Float.POSITIVE_INFINITY,
                AnimationSpec.Direction.ALTERNATE, AnimationSpec.FillMode.BOTH, true), a);
        AnimationSpec defaults = styleOf("animation: pulse").animations.get(0);
        assertEquals(new AnimationSpec("pulse", 0, 0, TimingFunction.EASE, 1, AnimationSpec.Direction.NORMAL,
                AnimationSpec.FillMode.NONE, false), defaults);
        List<AnimationSpec> list = styleOf("animation-name: a, b; animation-duration: 1s; "
                + "animation-iteration-count: 2.5, infinite; animation-timing-function: steps(4, jump-none)").animations;
        assertEquals(2, list.size());
        assertEquals(2.5f, list.get(0).iterations());
        assertEquals(1000, list.get(1).durationMs());
        assertEquals(new TimingFunction.Steps(4, TimingFunction.Steps.Jump.NONE), list.get(1).timing());
        assertTrue(styleOf("animation: none").animations.isEmpty());
        assertEquals("ease", styleOf("animation: ease 1s ease").animations.get(0).name(), "the leftover ident is the name");
    }

    @Test
    void timingFunctions() {
        assertEquals(new TimingFunction.CubicBezier(0.1f, -1, 0.9f, 2),
                styleOf("transition: cubic-bezier(.1, -1, .9, 2) 1s").transitions.get(0).timing());
        assertEquals(new TimingFunction.Steps(1, TimingFunction.Steps.Jump.START),
                styleOf("transition: step-start 1s").transitions.get(0).timing());
        assertEquals(new TimingFunction.Steps(3, TimingFunction.Steps.Jump.END),
                styleOf("transition: steps(3) 1s").transitions.get(0).timing());
        assertTrue(styleOf("transition: cubic-bezier(2, 0, 0, 0) 1s").transitions.isEmpty(), "x outside 0..1");
    }

    @Test
    void gridTemplates() {
        ComputedStyle s = styleOf("grid-template-columns: [a] 10px repeat(2, 1fr [b] auto) minmax(5px, 20%) "
                + "fit-content(30px) min-content; grid-template-rows: repeat(auto-fill, minmax(10px, 1fr))");
        assertEquals(List.of(new GridTrack.Fixed(Length.px(10)), new GridTrack.Flex(1), new GridTrack.Keyword(Length.AUTO),
                new GridTrack.Flex(1), new GridTrack.Keyword(Length.AUTO),
                new GridTrack.MinMax(new GridTrack.Fixed(Length.px(5)), new GridTrack.Fixed(Length.percent(20))),
                new GridTrack.FitContent(Length.px(30)), new GridTrack.Keyword(Length.MIN_CONTENT)), s.gridTemplateColumns);
        assertEquals(List.of(new GridTrack.Repeat(GridTrack.RepeatKind.AUTO_FILL, 0, List.of(new GridTrack.MinMax(
                new GridTrack.Fixed(Length.px(10)), new GridTrack.Flex(1))), List.of())), s.gridTemplateRows);
        assertTrue(styleOf("grid-template-columns: minmax(1fr, 10px)").gridTemplateColumns.isEmpty());

        ComputedStyle areas = styleOf("grid-template-areas: 'head head' 'nav main' '. main'");
        assertEquals(List.of(List.of("head", "head"), List.of("nav", "main"), List.of(".", "main")), areas.gridTemplateAreas);
        assertNull(styleOf("grid-template-areas: 'a b' 'b a'").gridTemplateAreas, "areas must be rectangles");
        assertNull(styleOf("grid-template-areas: 'a b' 'c'").gridTemplateAreas, "rows must have equal lengths");

        ComputedStyle t = styleOf("grid-template: 'a a' 20px 'b c' / 1fr 2fr");
        assertEquals(List.of(new GridTrack.Fixed(Length.px(20)), new GridTrack.Keyword(Length.AUTO)), t.gridTemplateRows);
        assertEquals(List.of(new GridTrack.Flex(1), new GridTrack.Flex(2)), t.gridTemplateColumns);
        assertEquals(List.of(List.of("a", "a"), List.of("b", "c")), t.gridTemplateAreas);
        ComputedStyle t2 = styleOf("grid-template: auto 1fr / 50px");
        assertEquals(2, t2.gridTemplateRows.size());
        assertNull(t2.gridTemplateAreas);
    }

    @Test
    void gridPlacementAndFlow() {
        ComputedStyle s = styleOf("grid-row: 1 / span 2; grid-column: -1; grid-auto-flow: column dense; "
                + "grid-auto-rows: 10px min-content");
        assertEquals(GridLine.line(1), s.gridRowStart);
        assertEquals(GridLine.span(2), s.gridRowEnd);
        assertEquals(GridLine.line(-1), s.gridColumnStart);
        assertEquals(GridLine.AUTO, s.gridColumnEnd);
        assertEquals(GridAutoFlow.COLUMN_DENSE, s.gridAutoFlow);
        assertEquals(2, s.gridAutoRows.size());
        ComputedStyle area = styleOf("grid-area: main");
        assertEquals(GridLine.named("main"), area.gridRowStart);
        assertEquals(GridLine.named("main"), area.gridColumnEnd);
        ComputedStyle four = styleOf("grid-area: 1 / 2 / 3 / 4");
        assertEquals(GridLine.line(4), four.gridColumnEnd);
        assertEquals(GridLine.AUTO, styleOf("grid-row-start: 0").gridRowStart, "line 0 is invalid");
        assertEquals(GridAutoFlow.ROW_DENSE, styleOf("grid-auto-flow: dense").gridAutoFlow);
    }

    @Test
    void flexShorthands() {
        assertFlex("flex: 1", 1, 1, Length.ZERO);
        assertFlex("flex: auto", 1, 1, Length.AUTO);
        assertFlex("flex: none", 0, 0, Length.AUTO);
        assertFlex("flex: 2 3", 2, 3, Length.ZERO);
        assertFlex("flex: 2 30px", 2, 1, Length.px(30));
        assertFlex("flex: 10%", 1, 1, Length.percent(10));
        assertFlex("flex: 0 0 0", 0, 0, Length.ZERO);
        assertFlex("flex: initial", 0, 1, Length.AUTO);
        ComputedStyle s = styleOf("flex-flow: wrap column-reverse; gap: normal 4px; place-items: center end; "
                + "place-content: space-between; place-self: safe flex-start; justify-content: first baseline");
        assertEquals(FlexDirection.COLUMN_REVERSE, s.flexDirection);
        assertEquals(FlexWrap.WRAP, s.flexWrap);
        assertEquals(Length.ZERO, s.rowGap);
        assertEquals(Length.px(4), s.columnGap);
        assertEquals(Align.CENTER, s.alignItems);
        assertEquals(Align.END, s.justifyItems);
        assertEquals(Align.SPACE_BETWEEN, s.alignContent);
        assertEquals(Align.BASELINE, s.justifyContent);
        assertEquals(Align.FLEX_START, s.justifySelf);
        assertEquals(5, styleOf("order: 5").order);
    }

    private static void assertFlex(String css, float grow, float shrink, Length basis) {
        ComputedStyle s = styleOf(css);
        assertEquals(List.of(grow, shrink, basis), List.of(s.flexGrow, s.flexShrink, s.flexBasis), css);
    }

    @Test
    void fonts() {
        ComputedStyle s = styleOf("font: italic bold 16px/2 minecraft:uniform, \"My Font\", monospace");
        assertTrue(s.fontItalic);
        assertEquals(700, s.fontWeight);
        assertEquals(16, s.fontSize);
        assertEquals(32, s.lineHeight);
        assertEquals(List.of("minecraft:uniform", "My Font", "minecraft:uniform"), s.fontFamily);
        assertEquals(List.of("minecraft:default", "minecraft:alt", "Comic Sans"),
                styleOf("font-family: sans-serif, minecraft:alt, Comic   Sans").fontFamily);
        ComputedStyle reset = styleOf("font-weight: 900; line-height: 3; font: 12px x");
        assertEquals(400, reset.fontWeight, "font resets omitted longhands");
        assertTrue(Float.isNaN(reset.lineHeight));
        assertEquals(List.of(4f, 5f, 6f, 8f, 12f, 16f, 24f, 32f), List.of("xx-small", "x-small", "small", "medium",
                "large", "x-large", "xx-large", "xxx-large").stream().map(k -> styleOf("font-size: " + k).fontSize).toList());
        assertEquals(400, styleOf("font: 8px").fontWeight, "a font without a family is invalid");
    }

    @Test
    void text() {
        ComputedStyle s = styleOf("text-decoration: underline red wavy; text-align: end; text-transform: uppercase; "
                + "text-indent: 4px; letter-spacing: normal; word-spacing: 2px; white-space: pre-wrap; "
                + "overflow-wrap: anywhere; text-overflow: ellipsis; -webkit-line-clamp: 3; vertical-align: text-top");
        assertTrue(s.underline);
        assertFalse(s.lineThrough);
        assertEquals(TextAlign.END, s.textAlign);
        assertEquals(TextTransform.UPPERCASE, s.textTransform);
        assertEquals(4, s.textIndent);
        assertEquals(0, s.letterSpacing);
        assertEquals(2, s.wordSpacing);
        assertEquals(WhiteSpace.PRE_WRAP, s.whiteSpace);
        assertEquals(WordBreak.BREAK_WORD, s.wordBreak);
        assertEquals(TextOverflow.ELLIPSIS, s.textOverflow);
        assertEquals(3, s.lineClamp);
        assertEquals(VerticalAlign.TEXT_TOP, s.verticalAlign);
        ComputedStyle both = styleOf("text-decoration-line: line-through underline");
        assertTrue(both.underline && both.lineThrough);
        assertFalse(styleOf("text-decoration: none").underline);
        assertEquals(WordBreak.BREAK_ALL, styleOf("word-break: break-all").wordBreak);
        assertEquals(0, styleOf("line-clamp: none").lineClamp);
        assertEquals(1.5f, styleOf("letter-spacing: .5em; font-size: 3px").letterSpacing);
    }

    @Test
    void interactionAndRendering() {
        assertEquals(Cursor.EW_RESIZE, styleOf("cursor: col-resize").cursor);
        assertEquals(Cursor.NS_RESIZE, styleOf("cursor: row-resize").cursor);
        assertEquals(Cursor.POINTER, styleOf("cursor: url(a.png) 2 3, pointer").cursor);
        assertEquals(Cursor.NOT_ALLOWED, styleOf("cursor: not-allowed").cursor);
        assertEquals(PointerEvents.NONE, styleOf("pointer-events: none").pointerEvents);
        assertEquals(UserSelect.ALL, styleOf("user-select: all").userSelect);
        assertEquals(ImageRendering.PIXELATED, styleOf("image-rendering: crisp-edges").imageRendering);
        assertEquals(ImageRendering.SMOOTH, styleOf("image-rendering: auto").imageRendering);
        assertEquals(ObjectFit.SCALE_DOWN, styleOf("object-fit: scale-down").objectFit);
        assertEquals(16 / 9f, styleOf("aspect-ratio: 16 / 9").aspectRatio);
        assertEquals(1.5f, styleOf("aspect-ratio: auto 1.5").aspectRatio);
        assertTrue(Float.isNaN(styleOf("aspect-ratio: auto").aspectRatio));
        ComputedStyle z = styleOf("z-index: -3");
        assertFalse(z.zIndexAuto);
        assertEquals(-3, z.zIndex);
        assertTrue(styleOf("z-index: auto").zIndexAuto);
        assertTrue(styleOf("z-index: 1.5").zIndexAuto, "z-index must be an integer");
        assertEquals(0.5f, styleOf("opacity: 50%").opacity);
        assertEquals(1f, styleOf("opacity: 3").opacity, "opacity is clamped");
        assertEquals(Visibility.COLLAPSE, styleOf("visibility: collapse").visibility);
        assertEquals(Position.STICKY, styleOf("position: sticky").position);
        assertEquals(BoxSizing.CONTENT_BOX, styleOf("box-sizing: content-box").boxSizing);
        assertEquals(Display.BLOCK, styleOf("display: flow-root").display);
        assertEquals(Display.INLINE_GRID, style("span", "display: inline-grid").display);
        assertEquals(Display.CONTENTS, styleOf("display: contents").display);
        ComputedStyle o = styleOf("overflow: hidden auto");
        assertEquals(Overflow.HIDDEN, o.overflowX);
        assertEquals(Overflow.AUTO, o.overflowY);
        assertEquals(Overflow.CLIP, styleOf("overflow: clip").overflowY);
    }

    private static ComputedStyle style(String tag, String css) {
        return new TestHost().load("<div><" + tag + " id=t style='" + css + "'></" + tag + "></div>").style("#t");
    }

    @Test
    void scrollingAndMinecraftExtensions() {
        ComputedStyle s = styleOf("scroll-behavior: auto; scrollbar-width: thin; scrollbar-color: red blue; "
                + "accent-color: lime; -mc-tint: #808080");
        assertFalse(s.scrollSmooth);
        assertEquals(1, s.scrollbarWidth);
        assertEquals(0xFFFF0000, s.scrollbarThumbColor);
        assertEquals(0xFF0000FF, s.scrollbarTrackColor);
        assertEquals(0xFF00FF00, s.accentColor);
        assertEquals(0xFF808080, s.tint);
        assertEquals(ComputedStyle.INITIAL.scrollbarThumbColor, styleOf("scrollbar-color: auto").scrollbarThumbColor);
        assertEquals(0, styleOf("scrollbar-width: none").scrollbarWidth);
        assertEquals(ComputedStyle.INITIAL.accentColor, styleOf("accent-color: auto").accentColor);
        assertTrue(styleOf("scroll-behavior: smooth").scrollSmooth);
        ComputedStyle listStyle = styleOf("list-style: none; color: red");
        assertEquals(0xFFFF0000, listStyle.color, "list-style is accepted and ignored");
    }
}
