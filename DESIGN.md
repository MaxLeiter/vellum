# Vellum — Design

Vellum is a small web engine for Minecraft GUIs. You write a screen, inventory, HUD or map in HTML, CSS and a little
JavaScript. Vellum lays it out with real CSS (block, inline, flexbox, grid, positioning), animates it (transitions,
keyframes), runs its scripts in a sandbox, and paints it with Minecraft's own GUI renderer, so text is the game's font,
items are real items and slots are real slots. Other mods (Chronicle, claudemons) use it as a library; servers can send
UIs to clients.

This document is the source of truth for the architecture and is updated as the code changes. `DECISIONS.md` records
why. `docs/MC_26_3_GUI_API.md` is the reference for Minecraft's 26.3 GUI APIs.

## 1. Goals and non-goals

Goals
- Author Minecraft UIs the way you author web pages: HTML for structure, CSS for layout and look, JS for behaviour.
  Web knowledge transfers; where Vellum differs from browsers it says so.
- Look native by default. The user-agent stylesheet makes `<button>`, `<input>`, panels, slots and tooltips look like
  vanilla, using vanilla sprites, so an unstyled page already fits the game.
- Minecraft-native elements: `<item>`, `<slot>`, `<entity>`, sprites, translations, player heads.
- Inventories are easy: put `<slot index="0">` where you want a slot; the vanilla container logic (click, drag,
  shift-click, tooltips, carried item) keeps working.
- Server-deliverable: a server mod opens a UI (bundled in the client mod, or sent inline), pushes JSON data to it,
  and receives messages back. Scripts are sandboxed because of this.
- Pure Java, no natives, no jar clashes: safe in big modpacks. The engine is a pure-Java module compiled into each
  loader jar; the JS engine is relocated.
- Fast enough to not matter: documents of a few hundred elements restyle and relayout in well under a millisecond,
  and paint every frame without allocation storms.
- A standalone previewer and snapshot tests, so UIs can be built and checked without launching the game.

Non-goals (for now)
- Being a browser. No networking (fetch, XHR, websockets), no iframes, no cookies, no full HTML5 parsing algorithm,
  no CSS floats, tables (use grid), multi-column, writing modes, bidi, or 3D transforms (the GUI pose is 2D).
- Arbitrary fonts: text uses Minecraft fonts (resource-pack fonts, including TTF providers, work).

## 2. Modules

```
engine/    Pure Java (compiled with --release 21). Zero Minecraft/loader deps. DOM, HTML parser, CSS (parser, selectors,
           cascade), layout, animation, paint (to an abstract Canvas), input and forms, scripting (sandboxed JS).
common/    Minecraft glue (vanilla-only, compiled against NeoForm): the Canvas over GuiGraphicsExtractor, font metrics,
           replaced elements (item, slot, entity...), VellumScreen, VellumContainerScreen, HUD layers, networking,
           resources and hot reload, the public API, demo UIs, GameTests and the dev autopilot.
neoforge/, fabric/  Thin loader entrypoints and event wiring.
preview/   (tool) Standalone previewer: renders a UI file in a Swing window with the engine and Minecraft's real
           font glyphs (read from the user's Minecraft jar), reloads on save, and has an inspector.
```
Dependency direction: `neoforge|fabric → common → engine`. The engine never imports `net.minecraft`. Engine sources
are compiled into each loader jar (same as Chronicle's sim-core and claudemons' core).

Packages in `engine/` (`dev.vellum.engine.*`):

| Package | Owns |
|---|---|
| `dom` | `Node`, `Element`, `Text`, `Document` (the frame pipeline), `Scheduler` (timers, rAF) |
| `event` | Event classes and the listener interface; dispatch lives in `Node.dispatchEvent` |
| `host` | `Host` (what the engine needs from its environment), `FontMetrics`, `FontSpec`, `FontFamilies` (family names → Minecraft fonts), `MinecraftGlyphs`, `ReplacedContent`, `PixelSurface` (canvas pixels), `Urls`, `FileStamps` (reload on save) |
| `style` | `ComputedStyle`, `Prop` (property registry), value types (`Length`, colours, enums, `Image`, `Shadow`...) |
| `css` | Tokenizer, parser, selectors, cascade (`StyleEngine`), the user-agent stylesheet |
| `html` | `HtmlParser`, `HtmlSerializer` |
| `layout` | `LayoutEngine`, `Box`, `LineBox`, `Fragment`; block, inline, flex, grid, positioning |
| `paint` | `Painter` (paint order + hit testing), `Canvas` (backend contract), `Shapes` (tessellation) |
| `replaced` | The engine's replaced elements (`img`, `sprite`, `canvas`), the registry that adds the host's, `ImageSources` (image sizes, `canvas:` images), `Context2D` (the canvas 2D context) |
| `anim` | `AnimationEngine`: transitions, @keyframes animations, `element.animate()` |
| `input` | `InputHandler` (pointer, wheel, keyboard, focus), form controls, smooth scrolling |
| `script` | `ScriptRuntime` contract, the Rhino-based runtime, DOM bindings, `vellum.*` API, template bindings |

## 3. The pipeline

`Document` drives everything on one thread (the render thread in Minecraft):

```
host: Document.parse(host, url, html, initialData, viewport)   parse, deliver initialData as vellum.data, run
                                     scripts (they see the viewport), bind templates (ScriptRuntime.documentLoaded),
                                     DOMContentLoaded, load
host: setViewport(w, h, guiScale)    on resize
host: input.mouseMove/mouseDown/...  on input  → DOM events, hover/active/focus flags, default actions
host: frame(nowMs)                   every frame:
        scheduler.run      timers, requestAnimationFrame
        input.tick         scrolling (smooth scrolls, scroll events), caret blink
        scripts.beforeRestyle  template bindings re-render if any script entry ran since the last frame
        updateStyle        if style dirty: cascade → baseStyle; animations.styleChanged → used style (element.style)
        animations.tick    advance transitions/animations → element.style; invalidates layout if needed
        updateLayout       if layout dirty: box tree → element.box
        input.afterLayout  after any layout (also one a script flushed): caret in view, autofocus, re-target hover
host: paint(canvas)                  every frame: painter walks boxes → canvas calls
```

Replaced content is created when its element enters the document (not during layout), so a script can draw on a
`<canvas>` as soon as it is parsed; the document keeps the live contents in a list, lets them catch up once per frame
before layout (canvas uploads, images that resized) and disposes them when their element leaves.

Hosts that can idle (the previewer) ask `Document.needsFrame(now)`: true while something would change what is painted
(a pending restyle, relayout or repaint, due timers or animation frames, running animations, smooth scrolls, a
blinking caret, template updates). Minecraft renders every frame anyway.

Scripts reading styles or geometry call `flushStyle()` / `flushLayout()`, which run the same `updateStyle` /
`updateLayout` stages and nothing else: no animation tick and no event-producing work, so no script runs inside a
flush.

The document is its own error boundary. An exception from the engine during any host call (parse, setViewport,
frame, paint, hitTest, input, receive) stops the document: it is reported once through `Host.reportError` and kept as
`Document.error()`, and later host calls do nothing (input returns false). Hosts check `error()` to show it. The
painter restores the canvas to the save count it found, also when it throws. Errors in scripts and listeners are
reported and do not stop anything.

Dirty tracking is document-wide (D-008): there is one style flag and one layout flag, and a pass restyles or
relayouts everything, but a change sets only the flags it can affect. Form and interaction state changes
(`:checked`, `:hover`, `:placeholder-shown`...) restyle, and so do attribute changes a style can read
(`StyleEngine.readsAttribute`: `style`, the attributes the current rules' selectors read by name, as a class or id
or through a pseudo-class, and those `attr()` has read); any attribute change repaints. The restyle invalidates
layout when a layout-affecting property changed. Layout is invalidated directly only by what layout reads without styles: tree
and text changes, and the `width`/`height`/`src` of replaced elements and an input's `type`. Typing in a field
restyles only when its emptiness flips. Changes to detached nodes invalidate nothing. Moving a node within the
document (`insertBefore` of a connected node) keeps its state (focus, hover, replaced content such as canvases);
only nodes that leave the document lose it.

Events are dispatched only when something in the document handles their type: the document counts listeners and
inline `on*` handlers per type, so `mousemove` with no listener costs nothing.

Restyles are incremental in effect: elements whose inputs are unchanged keep their style objects, and when only
hover/active/focus changed (`Document.domVersion` is unchanged) elements whose selector matching did not read that
state skip matching.

Per-element results live on `Element`: `baseStyle` (cascade) and `style` (the used style: after animations, read by
layout and paint), the same pair for the pseudo-elements (`beforeBaseStyle`/`beforeStyle`,
`afterBaseStyle`/`afterStyle`), `box`, `replaced`, scroll state, and opaque slots for subsystem state
(`animationState`, `controlState`, `parsedInlineStyle`, `scriptWrapper`). The animation engine is the only writer
of used styles (§8); when a change moves paint order without a relayout (z-index, opacity or a transform starting a
stacking context) it bumps `Document.stackingVersion()`.

### Contracts between subsystems
- **css → anim**: after computing an element's base styles the style engine calls
  `document.animations().styleChanged(el, which, oldBase, newBase)` for the element and for its ::before and
  ::after (`dom.PseudoElement`), parents first; the animation engine sets the used styles. It computes them with
  `StyleEngine.computeUsed(el, which, own)` (the cascade at used-value time, §8). For keyframes it calls
  `StyleEngine.resolveKeyframes(el, which, name, base)` which returns
  `List<ResolvedKeyframe(offset, timing, style, props)>`, each keyframe's declarations computed for that target.
- **layout ← style**: layout reads only `element.style` / `beforeStyle` / `afterStyle` and `Host.fonts()` (through
  `TextMeasure`).
  Boxes use the coordinate rules in `Box`'s javadoc.
- **paint ← layout**: the painter reads the box tree from `LayoutEngine.root()`. Form controls are painted by
  `input.Controls.paint(canvas, box, style)`, called by the painter after the box's background and border with the
  style it paints the box with.
- **geometry**: where a box is on screen is `paint.Coordinates` (`toViewport`, `fromViewport`, `boundingRect`),
  the one mapping painting, hit testing, input, scripts (`getBoundingClientRect`) and hosts (slot positions, the
  inspector) share: box positions, the scroll offsets of the boxes whose content they are in
  (`Box.contentParent()`), and CSS transforms resolved as the painter resolves them.
- **input ← paint**: hit testing is `Painter.hitTest(x, y)`, which mirrors paint order, transforms, clipping,
  scrolling, `pointer-events` and `visibility`. The `HitResult` carries the point in the hit box's coordinates, the
  scrollbar hit (if any), and the caret offset in text (computed on request).
- **text**: `layout.TextMeasure` (one per document, `LayoutEngine.textMeasure()`) is how wide text is for layout,
  painting, hit testing and controls alike: the host's advances plus `letter-spacing` and `word-spacing`. It caches
  the host's string widths (bounded), so relayouts do not measure the same words again.
- **scrolling**: an element owns its scroll position and smooth-scroll destination (`Element.scrollTo/scrollBy/
  scrollIntoView` with a `ScrollBehavior`); input, scripts, focus and layout (re-clamping) all scroll through it,
  and `dom.Scrolling` eases smooth scrolls and fires `scroll` once per frame per element that moved.
- **script ↔ dom**: scripts wrap DOM nodes; `Node.scriptWrapper` caches the wrapper. Inline `on*` attributes are
  run by `ScriptRuntime.runInlineHandler`.

## 4. HTML

The parser is forgiving (unclosed tags, implied `<html>`/`<head>`/`<body>`, void elements, raw-text `<script>` and
`<style>`, entities, comments dropped). It is not the HTML5 tree-construction algorithm; it handles the common
implied-end-tag cases (`<p>`, `<li>`, `<option>`).

Elements with behaviour:

| Element | Behaviour |
|---|---|
| `html head body div span p h1–h6 ul ol li section header footer nav main article aside figure pre blockquote hr br b strong i em u s small code kbd label fieldset legend` | As in HTML with UA styles. `ul/ol` render markers via `li::before`. |
| `a href` | Link look; activation calls `Host.navigate(url)` (Minecraft: open another Vellum UI, or a URL with confirmation). |
| `button` | Vanilla button look (sprite), `:hover`/`:active`/`:disabled`/`:focus-visible`. Click sound. |
| `input type=text|password|number|search` | Single-line text field with caret, selection, clipboard, scrolling, placeholder. |
| `textarea` | Multi-line text field. |
| `input type=checkbox|radio` | Vanilla checkbox look; radios grouped by `name`. |
| `input type=range` | Vanilla slider look; `min`, `max`, `step`, `value`; drag and keys. |
| `select` / `option` | Button that opens a dropdown list (an overlay in a top layer). |
| `progress`, `meter` | Bars styled by CSS. |
| `details` / `summary` | Toggle `open`; closed, only the summary is rendered (UA stylesheet; loose text too). |
| `dialog` | Hidden unless `open`; `showModal()` puts it in the top layer with a backdrop. |
| `img src` | Texture (`ns:textures/...png`), sprite (`sprite:ns:path`), or canvas (`canvas:id`, the `<canvas>` with that id). The same URLs work in CSS `url()`; the engine reads the schemes (`Image.ofUrl`), hosts only see texture URLs. |
| `sprite src` | A GUI sprite at its natural size. |
| `canvas width height` | Pixels (300×150 by default, at most 2048 a side) in a host `PixelSurface` (in game a dynamic texture uploaded by the rows that changed). `getContext('2d')` is a subset of CanvasRenderingContext2D (`replaced.Context2D`): `fillStyle`/`strokeStyle` (CSS colours), `lineWidth`, `globalAlpha`, `save`/`restore`, `fillRect`, `strokeRect`, `clearRect`, `getImageData`/`putImageData`/`createImageData` (a `Uint8ClampedArray`), and `drawImage` of another canvas. Coordinates are pixels and edges snap to them; no text, paths, transforms or gradients. |
| `template` | Inert content for scripts: its contents are not rendered, queried (`getElementById`, `querySelector`...) or run. |
| `script`, `style`, `link rel=stylesheet` | As in HTML. Scripts run in document order after parsing (like `defer`). |

Minecraft elements (the Minecraft host's replaced content, `Host.replacedElements`; the previewer draws stand-ins):

| Element | Behaviour |
|---|---|
| `<item id="minecraft:diamond_sword" count="1" components="{...}">` | Renders an item stack (with count, durability bar). 16×16 intrinsic; scaled by CSS size. `tooltip` attribute shows the vanilla item tooltip on hover. |
| `<slot index="n">` | A real container slot of the open menu at this position (only in container screens). 18×18 with the vanilla slot look; the item, hover highlight, clicks, drags and tooltips are vanilla. |
| `<entity type="minecraft:pig">` / `<entity player>` / `<entity id="123">` | A live entity render, optional `follow-mouse`, `scale`, `rotate`. |
| `<player-head name="..." uuid="...">` | A player's face from their skin. |
| `<sprite src="ns:path">` | Shorthand for a GUI sprite at its natural size. |
| `<mc-text>` with `key="..."` and optional `args`, or `json='...'` | Translated (`Host.translate`) or component text (`Host.formatText` gives styled runs, which become spans), as a normal inline element. Expanded by the engine when the element is parsed or inserted and when those attributes change, so templates and scripts can use it. |

## 5. CSS

### Syntax and cascade
- Full tokenizer per CSS Syntax 3 (idents, strings, numbers, dimensions, percentages, hashes, functions, url, at-rules,
  comments, escapes).
- Rules: style rules, `@keyframes`, `@media` (features: `width`, `height`, `min-/max-width`, `min-/max-height`,
  `orientation`, `prefers-reduced-motion`; plus Vellum's `gui-scale`, `min-gui-scale`, `max-gui-scale`), `@import`
  (resolved through the host), `@font-face` ignored, `@supports` evaluated against the property registry.
- Cascade: UA sheet < author sheets (document order) < inline style; `!important` reverses origin order; specificity;
  source order. Inheritance via `Prop.inherited`. `inherit`, `initial`, `unset`, `revert` (as unset).
- Custom properties `--x` and `var(--x, fallback)` anywhere in a value (substituted before parsing the value).
- `calc()`, `min()`, `max()`, `clamp()` for lengths, percentages and numbers. Mixed `px + %` folds into `Length`.

### Selectors
Type, universal, `#id`, `.class`, attribute (`[a]`, `=`, `~=`, `|=`, `^=`, `$=`, `*=`, ` i` flag), combinators
(descendant, `>`, `+`, `~`), selector lists, pseudo-classes `:hover :active :focus :focus-visible :focus-within
:checked :disabled :enabled :empty :root :first-child :last-child :only-child :nth-child(an+b [of S])
:nth-last-child :first-of-type :last-of-type :nth-of-type :not() :is() :where() :has()` (`:has` relative,
descendant/child only), `:placeholder-shown`, `:open` (details/dialog/select), and pseudo-elements `::before`,
`::after`, `::placeholder`. Specificity per Selectors 4.

### Values
- Lengths: `px` (a GUI pixel, which scales with Minecraft's GUI scale), `em`, `rem`, `%`, `vw`, `vh`, `vmin`,
  `vmax`, and `dp` (a device pixel: `1dp` = `1px / guiScale`, for hairlines). Default font size is **8px**, the
  native size of Minecraft's font; `1rem` = 8px. Sizes that are multiples of 8 (or 4 at GUI scale ≥ 2) stay crisp.
- Colours: `#rgb #rgba #rrggbb #rrggbbaa`, `rgb()/rgba()` (comma and space syntax), `hsl()/hsla()`, named colours
  (CSS list), `transparent`, `currentColor`, and Minecraft's chat colours as names: `mc-black mc-dark-blue
  mc-dark-green mc-dark-aqua mc-dark-red mc-dark-purple mc-gold mc-gray mc-dark-gray mc-blue mc-green mc-aqua mc-red
  mc-light-purple mc-yellow mc-white`. `color-mix(in srgb, a p%, b)`.
- Images: `url(...)`, `sprite(ns:path)`, `linear-gradient()`, `repeating-linear-gradient()`, `radial-gradient()`
  (`circle`/`ellipse`, a size keyword `closest-side`/`farthest-side`/`closest-corner`/`farthest-corner` or explicit
  radii, `at <position>`).
- Timing: `ease`, `linear`, `ease-in`, `ease-out`, `ease-in-out`, `cubic-bezier()`, `steps()`, `step-start`,
  `step-end`.

### Properties
Everything in `Prop`, with these shorthands expanded by the parser: `margin`, `padding`, `inset`, `border`,
`border-top|right|bottom|left`, `border-width|style|color`, `border-radius` (with `/` for elliptical, stored per
corner as a single Length — elliptical radii use the horizontal value), `background` (colour + layers),
`background-*` longhands (`-image`, `-size`, `-position`, `-repeat`, `-clip`), `flex`, `flex-flow`, `gap`,
`place-items`, `place-content`, `place-self`, `grid-template`, `grid-area`, `grid-row`, `grid-column`, `overflow`,
`font` (simplified), `text-decoration` (line keywords), `transition`, `animation`, `outline`, `transform-origin`,
`list-style` (ignored except `none`), `-webkit-line-clamp`.

Vellum extensions: `-mc-tint: <color>` (multiply images/sprites/items), `text-shadow: minecraft` (the game's
native 1px shadow), `font-family: minecraft:default | minecraft:uniform | minecraft:alt | minecraft:illageralt |
<any font id>` (also the aliases `monospace` → uniform, `sans-serif`/`serif`/`system-ui` → default; names without a
namespace are `minecraft:` ids). `host.FontFamilies` is the one mapping, used by the style engine and every host.

### User-agent stylesheet (`engine/src/main/resources/vellum/ua.css`)
- `*, ::before, ::after { box-sizing: border-box }` (deliberate deviation: border-box everywhere).
- `html { color: #fff; font: 8px minecraft:default; line-height: normal }`, `body { margin: 0 }`.
- Headings: `h1` 16px, `h2` 12px, `h3`–`h6` 8px bold; paragraphs get `margin: 0 0 8px`.
- Vanilla-looking controls via sprites (`minecraft:widget/button`, `_highlighted`, `_disabled`,
  `widget/text_field`, `widget/text_field_highlighted`, `widget/checkbox*`, `widget/slider*`), with
  `text-shadow: minecraft` on button text.
- Utility classes prefixed `mc-`: `.mc-panel` (the vanilla grey container panel with bevel border), `.mc-inset`
  (a sunken slot bevel), `.mc-tooltip` (tooltip background and frame), `.mc-dark` (translucent dark panel used by
  vanilla menus), `.mc-label` (`#404040`, no shadow: container labels).

## 6. Layout

All layout is in floats (GUI px). Painting snaps to device pixels.

- **Box tree.** One `Box` per rendered element (`display: none` → none; `contents` → children only). Text and inline
  elements inside a block container produce line boxes; block children of an inline are handled by splitting into
  anonymous blocks (simplified: an inline containing blocks is blockified). Loose text in flex/grid containers is
  wrapped in anonymous flex/grid items. `::before`/`::after` with `content` become `PSEUDO` boxes (inline or block
  per their display) containing their text.
- **Block formatting**: width from containing block minus margins; `auto` margins centre; `min/max` constraints;
  `box-sizing`; vertical margin collapsing between siblings and parent/first-child (no clearance — no floats).
- **Inline formatting**: whitespace processing per `white-space`; greedy line breaking at spaces (and anywhere for
  `word-break: break-all` / overlong words with `overflow-wrap: anywhere`); `text-align` including `justify`;
  `text-indent`; `letter-spacing`; `text-transform`; `line-height` with half-leading; `vertical-align` (baseline,
  middle, top, bottom, text-top, text-bottom, sub, super); inline boxes with padding/border/margin (horizontal only
  affects layout); atomic inlines (inline-block/flex/grid, replaced) aligned on the baseline; `<br>`;
  `text-overflow: ellipsis` with `white-space: nowrap` and `overflow` not visible; `line-clamp` (with ellipsis).
- **Flexbox**: the full CSS Flexbox §9 algorithm: direction and wrap (incl. reverse), `order`, flex base size from
  `flex-basis`/content, hypothetical main size with min/max (`min-width:auto` = content-based minimum), resolving
  flexible lengths with freezing, cross sizes, `align-items/self` (stretch, start, end, center, baseline),
  `justify-content` (all values), `align-content`, `gap`, auto margins, multi-line.
- **Grid**: explicit tracks (`px`, `%`, `fr`, `auto`, `min-content`, `max-content`, `minmax()`, `fit-content()`,
  `repeat(n | auto-fill | auto-fit, ...)`), `grid-template-areas`, line-based placement (numbers, negative, `span`,
  area names), auto-placement (row/column, dense), implicit tracks (`grid-auto-rows/columns`), `gap`, alignment
  (`justify-items/self`, `align-items/self`, `justify-content`, `align-content`). Track sizing is the spec algorithm
  simplified: no baseline alignment in grid.
- **Positioning**: relative (offset after layout), absolute (containing block = the padding box of the nearest
  positioned or transformed ancestor, an inline one contributing its fragments' bounds; `auto` insets resolve to the
  static position), fixed (the viewport, or the nearest transformed ancestor), sticky (as relative). Layout records
  the containing block (`Box.containingBlock`); an out-of-flow box's `Box.contentParent()` is the box whose content
  it is in, and the scrollers between it and its containing block neither scroll nor clip it. Gaining or losing a
  transform is layout-affecting (it changes containing blocks); a transform's value is paint-only. z-index and
  stacking are paint concerns.
- **Overflow**: scroll containers record `scrollWidth/scrollHeight` (`Box.maxScrollLeft/Top()` is the range); their
  content is laid out normally and painted shifted by the element's scroll offset. An out-of-flow box extends its
  containing block's scrollable overflow, not the scrollers it escapes. After a layout, scroll offsets are
  re-clamped through the element (firing `scroll` if that moves them). Text controls' overflow is their text
  (`Controls.overflow`), so a textarea scrolls by its element's offsets like any scroll container. Scrollbars are
  overlay (they do not take layout space), drawn by the painter, styled by `scrollbar-width` and the scrollbar colour
  properties.
- **Replaced elements**: intrinsic size from `ReplacedContent` (or `width`/`height` attributes), `aspect-ratio`,
  `object-fit` (applied at paint). Form controls are atomic boxes sized by the UA stylesheet; their children
  (option elements) are not laid out.
- **Intrinsic sizes**: min-content / max-content measurement for every formatting context (needed by flex, grid,
  inline-block shrink-to-fit, and `width: min-content | max-content | fit-content`). Cache per layout pass.

## 7. Paint

The painter walks the box tree each frame and calls the `Canvas`. Paint order follows CSS Appendix E, simplified:
for each stacking context: background and borders of the root, then positioned descendants with negative z-index,
then in-flow non-positioned blocks (background/border), then inline content (text, inline boxes, atomic inlines),
then positioned descendants with `z-index: auto | 0` in tree order, then positive z-index.

Per box:
1. Transform (`translate(origin) · transform · translate(-origin)`) and opacity (multiplied into alpha; Minecraft has
   no offscreen groups, so overlapping children of a translucent box blend individually) via `save`/`restore`.
2. `box-shadow` (outer): blurred by stacking concentric rounded rects with falling alpha; inset shadows likewise
   inside the padding box.
3. Background colour, then background layers bottom to top: images and sprites with size/position/repeat
   (repeat by tiling draws), gradients tessellated into quads (linear along any angle, radial as rings). Clipped to the
   border radius by tessellating the rounded shape; images with a radius are clipped to the box rect only.
4. Border: `fillBorder` with per-side colours; `inset`/`outset`/`groove`/`ridge` shade the sides (the classic
   Minecraft bevel: `border: 2px outset #c6c6c6`); `dashed`/`dotted` as segments.
5. Form control painting (`input.Controls.paint`).
6. Replaced content (`ReplacedContent.paint`), with `object-fit`. Minecraft content draws through the Minecraft
   canvas, which its paint finds in one documented place (`McReplaced`).
7. Children: clip to the padding box if `overflow` is not visible (rectangular clip; rounded clip is not supported),
   translate by `-scroll`, paint children and line fragments.
8. Scrollbars (overlay), outline (`outline`, `outline-offset`; focus rings), and `::after` order handled by the box
   tree.

Text runs: drawn by `paint.TextPainter` (also used by form controls) with colour, decorations, and `text-shadow`
layers (drawn first, offset, in the shadow colour; `text-shadow: minecraft` uses the host's native shadow).
Letter- and word-spaced text is drawn in the parts layout cut it into (`SpacedText`: glyphs, or words), at the
positions layout measured. Ellipsis is already in the fragment text. A run maps its processed text back to the
text node's data for caret offsets.

Inline elements: their fragments (inline box decorations, then the content up to the fragment's end) are a group:
`opacity` applies to the group, and an `outline` is drawn around each fragment after the block's lines (so links get
their focus ring). Positioned inline elements still paint in line order (no z-index for them).

The z-ordered lists of each stacking context are cached until the layout or `Document.stackingVersion()` changes;
painting and hit testing share them.

Snapping: rectangle edges round to device pixels (`Canvas.devicePixel()`) so borders stay crisp at every GUI scale.

Hit testing walks the same order in reverse, applies inverse transforms, honours clips and scroll offsets, skips
`pointer-events: none` and `visibility: hidden`, and returns the deepest element (text hits resolve to the parent
element and its inline box, with the character offset for caret placement computed when asked; scrollbar hits name
the scrollbar).

## 8. Animation

- **Transitions**: on restyle, for each property in `transition-property` whose base value changed (and interpolates),
  start a transition from the current animated value to the new value with the duration, delay and timing function.
  Retargeting mid-flight starts from the current value (with the spec's reversing shortening for reversed transitions).
  `transitionrun/start/end/cancel` events.
- **Keyframes**: `animation-name` maps to `@keyframes`; keyframes resolved per element via the style engine;
  per-keyframe timing functions; iterations, direction, fill mode, delay, play state; `animationstart/iteration/end`.
  Animated values override the base style; transitions apply under animations as in CSS.
- **Interpolation** by `Prop.Interp`: lengths (px and % parts separately; keyword ↔ length flips at 50%), floats,
  ints (rounded), colours (premultiplied), shadow lists (pairwise, padding with transparent zero shadows), transform
  lists (pairwise by function type when lists match; otherwise decompose both to matrices and interpolate
  translate/rotate/scale/skew), discrete for everything else.
- Pseudo-elements animate like elements: `::before` and `::after` have their own transitions and animations, whose
  events go to the element with `pseudoElement` set.
- **Used values.** A used style is the base style with the target's effects applied, computed at used-value time:
  what a target inherits follows its parent's *used* values (a pseudo-element's parent is its element), and what it
  computes from `color` or `font-size` follows its own animated ones: `currentColor` anywhere (border colours,
  which default to it, `background-color`, `-mc-tint`, shadows and gradients without a colour, `color-mix()`) and
  `em`. The cascade itself does this (`StyleEngine.computeUsed`): when a target's parent's used inherited values
  differ from its base ones, or its effects set `color` or `font-size`, it is computed again from the used inputs and
  its effects apply on top. So animating `color` recolours the element's borders and its children's text. Base styles
  stay the cascade's alone (inheriting base values), which is what transitions compare: a change an ancestor's
  animation causes never starts a transition. currentColor is resolved by recomputing rather than kept symbolic in
  colour fields (a sentinel would need resolving in every reader of every colour, inside shadows, gradients and
  `color-mix()` too); the cost is a cascade per frame for each target under an animated inherited value. A target
  with nothing animated in it or above it keeps its base style object as its used style. (Like the rest of the
  cascade, `currentColor` in an inherited property such as `-mc-tint` inherits as the colour it resolved to.)
- Layout-affecting animated properties invalidate layout each frame; paint-only ones (opacity, transform, colours)
  do not, for elements: their boxes paint with the element's live style. Pseudo-element boxes and their generated
  text paint with the style they were laid out with, so a pseudo-element whose used style changes is laid out again.
- A common trap (standard CSS): animations override normal declarations, and one that fills forwards
  (`animation-fill-mode: both` or `forwards`) keeps applying its last keyframe after it ends. So an entrance
  animation of `transform` with `both` pins the transform for good, and a later `:hover { transform: ... }` does
  nothing. Give entrances `backwards` (the first keyframe applies during the delay, then the animation lets go), or
  have them animate a property the hover does not.
- `element.animate(keyframes, options)` from scripts creates the same animation objects (Web Animations subset:
  `finished` promise-like callback, `cancel()`, `pause()`, `play()`, `reverse()`).
- `prefers-reduced-motion` media query reflects a host setting.

## 9. Input and forms

- Pointer: hover chain (`:hover` on target and ancestors), `mouseover/out/enter/leave/move`, `mousedown/up`, `click`
  (same element down and up), `dblclick`, `contextmenu` (right button), `:active` while pressed, pointer capture
  during drags (range thumb, scrollbar, text selection), and the cursor from `cursor` via `Host.setCursor`.
- Wheel: deltas in GUI px, a notch being `InputHandler.WHEEL_NOTCH` (24 px) in every host; `wheel` event; if not
  cancelled, scrolls the nearest scroll container whose content holds the target that can move in that direction
  (smooth when `scroll-behavior: smooth`, default on), with scroll chaining.
- Scrollbars: overlay thumbs appear when a container is scrollable; hover widens them; drag to scroll; click track
  to page. Hover and presses use the hit test's scrollbar hit; drags map the pointer through `Coordinates`.
- Scripts: `scrollTop`/`scrollLeft`, `scrollTo`/`scrollBy` (with `behavior`) and `scrollIntoView` (`block`,
  `inline`, `behavior`) go through the element's scroll API; without a behavior, `scroll-behavior` decides.
- Keyboard: `keydown`/`keyup` to the focused element (or body); hosts pass DOM `key` and `code` names, and the engine
  derives `keyCode` from `code` and tracks auto-repeat (a keydown with no keyup since); Tab / Shift+Tab focus navigation by tabindex order;
  Enter/Space activate buttons, checkboxes, links; arrow keys on range, radio groups and selects; Escape bubbles to the
  host (closes the screen unless a script calls preventDefault or a dropdown/dialog is open).
- Text fields: caret, selection (shift+arrows, mouse drag, double-click word, ctrl/cmd+A), clipboard (copy, cut,
  paste via `Host`), word-wise movement (ctrl/alt), Home/End, undo/redo (simple stack), `maxlength`, `placeholder`,
  `readonly`, horizontal scroll to keep the caret visible; `beforeinput`, `input`, `change` (on blur/Enter) events;
  textarea with line navigation.
- Focus: `focus`/`blur`/`focusin`/`focusout`; `:focus-visible` after keyboard navigation; `autofocus`; elements
  without a box (also the content of a closed `<details>`, hidden by the UA stylesheet) are not tab stops.
- Default actions run only if the event was not cancelled: checkbox/radio toggle, `label` forwards to its control,
  `details` toggle, link navigation, `select` dropdown, button click sound (`Host.playSound`).

## 10. Scripting

JavaScript, sandboxed. Engine choice and its reasons are in DECISIONS.md. The runtime:
- Blocks all Java access (no `Packages`, no `java.*`, class shutter denies everything), enforces a CPU budget per
  entry (instruction observer; runaway scripts throw and are reported, the UI keeps working), and caps recursion.
- Globals: `window` (= global), `document`, `console` (log/info/warn/error/debug → host log), `setTimeout`,
  `setInterval`, `clearTimeout`, `clearInterval`, `requestAnimationFrame`, `cancelAnimationFrame`,
  `performance.now()`, `queueMicrotask`, `JSON`, `Math`, `structuredClone` (via JSON), `localStorage` (per-UI,
  in-memory unless the host persists it).
- DOM API subset: `Node` (`parentNode`, `childNodes`, `firstChild`, `nextSibling`, `appendChild`, `insertBefore`,
  `removeChild`, `replaceChild`, `remove`, `append`, `prepend`, `before`, `after`, `replaceWith`, `cloneNode`,
  `contains`, `textContent`, `nodeType`, `nodeName`), `Element` (`tagName`, `id`, `className`, `classList`,
  `getAttribute`/`setAttribute`/`removeAttribute`/`hasAttribute`/`toggleAttribute`, `dataset`, `style` (a
  `CSSStyleDeclaration` writing the inline style: camelCase properties, `setProperty`, `removeProperty`,
  `cssText`), `innerHTML`, `outerHTML`, `insertAdjacentHTML`, `querySelector(All)`, `closest`, `matches`,
  `children`, `firstElementChild`, `parentElement`, `getBoundingClientRect`, `offsetWidth/Height`,
  `clientWidth/Height`, `scrollTop/Left/Width/Height`, `scrollTo`, `scrollIntoView`, `focus`, `blur`, `click`,
  `animate`, `addEventListener`/`removeEventListener`/`dispatchEvent`, `value`, `checked`, `disabled`),
  `document` (`getElementById`, `querySelector(All)`, `createElement`, `createTextNode`,
  `createDocumentFragment`, `body`, `head`, `documentElement`, `activeElement`), `getComputedStyle` (read-only
  string values), events with `preventDefault`, `stopPropagation`, `target`, `currentTarget`, `key`, `clientX`...,
  `new Event()` / `new CustomEvent()`.
- The `vellum` object:
  - `vellum.data`: the latest data from the host/server (a plain object). `vellum.on('data', fn)` runs on updates.
  - `vellum.send(channel, value)`: message to the server/mod (JSON-serialised).
  - `vellum.on(channel, fn)` / `vellum.off`: messages from the server/mod.
  - `vellum.close()`, `vellum.playSound(id, volume, pitch)`, `vellum.t(key, ...args)` (translation),
    `vellum.open(url, data)` (open another UI), `vellum.nextTick(fn)` (runs `fn` once templates have rendered).
- **Templates** (no build step, AngularJS-style dirty checking): `{{ expr }}` in text and attributes,
  `v-if="expr"`, `v-for="item in expr"` (with `v-key`), `v-show`, `v-bind:attr` / `:attr`, `v-class`, `v-style`,
  `v-on:event` / `@event`, `v-model` (two-way for inputs). Expressions are JS evaluated with the scope chain
  `loop variables → vellum.data → state → globals`, where `state` is a reactive object created with
  `vellum.state({...})`. Templates render when the document loads; after that, any event handler, timer, rAF
  callback or data update marks them dirty and bindings are re-evaluated once per frame, before restyle; the DOM is
  touched only when a value changed. So, as in Vue, a handler that changes state sees the old DOM until the next
  frame (`vellum.nextTick(fn)` runs after the update). This makes server-driven UIs a template plus JSON.

## 11. Minecraft integration (`common/`)

- **McCanvas** implements `Canvas` over `GuiGraphicsExtractor`: own affine matrix stack set into the pose (the pose
  stack is only 16 deep); clip stack → `enableScissor`; alpha stack multiplied into colours; `fillRect` → `fill`
  (sub-pixel via pose translate); `fillQuads` → a custom `GuiElementRenderState` with `RenderPipelines.GUI`
  (submitted through a mixin accessor for `guiRenderState`/scissor); `drawText` → `Font` with a `Style` (font,
  bold, italic, underline, strikethrough, colour) scaled by `size/8`, through Minecraft's bidi reordering only when
  the text has right-to-left characters; `drawImage` → a textured quad (identifiers cached in `McImages`, canvases
  are registered dynamic textures); `drawSprite` → `blitSprite`. Rectangles are one render state each, sharing a copy
  of the transform until it changes.
- **McFontMetrics**: `Font.getSplitter().stringWidth(...)` with the style (bold widens), scaled. Each `FontSpec` keeps
  its resolved styles in its host slot; the shared table is keyed by families, bold and italic (not the size).
- **Host**: resources from the resource manager (`assets/<ns>/...`; UIs conventionally in `assets/<ns>/vellum/`),
  `minecraft:`-style URLs, sounds, clipboard, cursor (`CursorTypes`), logging to the mod logger, translations.
- **Replaced elements**: `item`, `slot`, `entity`, `player-head` (`McReplaced.ELEMENTS`); canvases are `McSurface`s
  (NativeImage + DynamicTexture); `mc-text` JSON is formatted by `McText`.
- **VellumScreen** (`Screen`): owns a `Document`, forwards input (SDL key codes → DOM key names), sets the viewport
  to the GUI-scaled size, enables SDL text input while a text field is focused, `Escape` closes unless cancelled,
  `isPauseScreen` configurable (default false), background: none (the page draws its own; `isInGameUi` true so the
  world shows).
- **VellumContainerScreen** (`AbstractContainerScreen`): same, plus `<slot index>` elements position the menu's
  slots where they are painted, every frame (`McCanvas.placeSlot`: after scrolling, transforms and clipping; mutable
  `Slot.x/y` via mixin accessor); vanilla slot/item/tooltip/carried-item rendering stays, and slots not painted this
  frame are moved off-screen. Slot data is sent to the page only when a stack changed.
- **HUD layers**: `VellumHud.register(id, url)` shows a non-interactive document over the HUD (title cards, trackers).
- **Networking**: `vellum:open` (server → client: UI url or inline HTML, initial JSON data, session id),
  `vellum:data` (server → client: JSON for a session), `vellum:message` (client → server: session, channel, JSON),
  `vellum:close`. Server API: `VellumServer.open(player, url, data)` returns a session handle with `push(data)`,
  `onMessage(channel, handler)`, `close()`; container screens open via a `MenuType` whose extra data carries the url.
- **Resources and hot reload**: documents load through the resource manager and reload with resources (F3+T). In a
  dev environment pages are read from `src/main/resources`, and the files they were read from are polled
  (`FileStamps`) so saving one reloads open documents.
- **Commands**: `/vellum open <url>` (client), `/vellum demo`, `/vellum inspect` (toggle inspector overlay).
- **Inspector**: F12 inside a Vellum screen toggles an overlay that highlights the hovered element's margin, border,
  padding and content boxes and shows its selector and size.
- **Demo**: `assets/vellum/vellum/demo/` has a gallery: a vanilla-styled settings page, a chest-style inventory made
  of `<slot>`s, a flexbox/grid showcase, an animated menu, a scripted counter/todo with templates, and a map-like
  canvas.

## 12. Testing

- `engine` tests (JUnit): parser, selectors, cascade, each layout mode (compared against hand-computed and
  browser-verified expectations), animation timing, events, scripting, and `EndToEndTest` for whole pages.
- Tests go through the real pipeline, the way hosts drive it. `testing/TestHost` is a deterministic host
  (Minecraft's ASCII glyph widths, the Rhino runtime, in-memory resources and canvases, recorded logs, errors,
  sounds and cursors); `TestHost.load(html)` parses, sets the viewport and runs the first frame, and returns a
  `testing/Page`: frames at chosen times, input at viewport points through the real hit test (`click(element)`
  aims at the element's centre and checks the hit lands in it), and painting onto `testing/RecordingCanvas` (every
  call with its transform, alpha and clip, or as a string trace). Pages are styled by the real CSS engine; hand-built
  boxes, styles and hit testers are not used. The layout suite includes ~1250 Chrome-generated fixtures from Taffy,
  run as HTML pages with Taffy's Chrome setup as a stylesheet and Ahem metrics.
- `preview` snapshot tests render the canvas test sheet, `preview/src/test/resources/pages` and the demo UIs
  through the previewer's path (`ImageCanvas`, Java2D) with Minecraft's jar, ten frames 16 ms apart, and compare them
  with goldens in `preview/src/test/snapshots` (`-Dvellum.updateSnapshots=true` rewrites them).
- GameTests (both loaders, headless): networking codecs, server API, container menus.
- Dev autopilot (`./gradlew :neoforge:runClient -Pautopilot`): opens each demo UI in a real client, at GUI scales 2
  and 3, and screenshots it to `neoforge/runs/client/screenshots/`. It drives pages through `VellumAutomation`
  (docs/API.md), the public client API for dev automation: it hovers the showcase title screen's first button for a
  burst of screenshots a tick apart, and fills in the templates demo and checks its state.
- Previewer scripts (`--actions`, preview/README.md) drive a page headless with input and screenshots.
