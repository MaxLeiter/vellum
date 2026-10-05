# Vellum design

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
| `paint` | `Painter` (paint order + hit testing), `Canvas` (backend contract), `Shapes` (tessellation), `ScissorStack` (clips for scissor-based hosts) |
| `replaced` | The engine's replaced elements (`img`, `sprite`, `canvas`), the registry that adds the host's, `ImageSources` (image sizes, `canvas:` images), `Context2D` (the canvas 2D context) |
| `anim` | `AnimationEngine`: transitions, @keyframes animations, `element.animate()` |
| `input` | `InputHandler` (pointer, wheel, keyboard, focus), form controls, smooth scrolling, tooltips, `Narration` (accessible names and roles, live regions) |
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
host: input.tooltip()                every frame, after paint: the tooltip to draw on top, or null
host: input.narration()              while a narrator listens: the focused and hovered elements as it reads them,
                                     live regions' announcements (once a frame); the title is Document.title()
host: close()                        pagehide, unload (scripts still run), then dispose scripts, timers, replaced content
```

Replaced content is created when its element enters the document (not during layout), so a script can draw on a
`<canvas>` as soon as it is parsed; the document keeps the live contents in a list, lets them catch up once per frame
before layout (canvas uploads, images that resized) and disposes them when their element leaves.

Hosts that can idle (the previewer) ask `Document.needsFrame(now)`: true while something would change what is painted
(a pending restyle, relayout or repaint, due timers or animation frames, running animations, smooth scrolls, a
blinking caret, template updates). Minecraft renders every frame anyway.

Automation asks `Document.settled()` instead: whether the page will still change by itself. It counts what ends
(pending restyle, relayout or repaint, smooth scrolls and scroll events, template updates and `nextTick` callbacks,
transitions and finite animations, drags and spinning turntables, a tooltip's delay) and leaves out what never does
(infinite animations, timers, animation-frame callbacks, the caret), or pages with a clock or a spinner would never
settle. The work both predicates wait for is one private term, `Document.dirty()`, so a new kind of pending work is
added there once; replaced content that is still loading (`ReplacedContent.loading()`) is part of it.

`Element.getBoundingClientRect()` and `visibleRect()` lay out first when the document has changed, as browser
geometry getters do; paint and input read the painted boxes directly. `Document.reveal(element, whole)` lays out and
scrolls an element into view by the least instant scroll (always, or only when none of it shows) and returns its
visible rect. `Document.pointerTarget(element)` is where automation points: the centre of what `reveal` returns
(the border box cut to the viewport and to the clips of the content holding it, `Coordinates.visibleRect`), and only
if the hit test there finds the element.

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
- css → anim: after computing an element's base styles the style engine calls
  `document.animations().styleChanged(el, which, oldBase, newBase)` for the element and for its ::before and
  ::after (`dom.PseudoElement`), parents first; the animation engine sets the used styles. It computes them with
  `StyleEngine.computeUsed(el, which, own)` (the cascade at used-value time, §8). For keyframes it calls
  `StyleEngine.resolveKeyframes(el, which, name, base)` which returns
  `List<ResolvedKeyframe(offset, timing, style, props)>`, each keyframe's declarations computed for that target.
- layout ← style: layout reads only `element.style` / `beforeStyle` / `afterStyle` and `Host.fonts()` (through
  `TextMeasure`).
  Boxes use the coordinate rules in `Box`'s javadoc.
- paint ← layout: the painter reads the box tree from `LayoutEngine.root()`. Form controls are painted by
  `input.Controls.paint(canvas, box, style)`, called by the painter after the box's background and border with the
  style it paints the box with.
- geometry: where a box is on screen is `paint.Coordinates` (`toViewport`, `fromViewport`, `boundingRect`),
  the one mapping painting, hit testing, input, scripts (`getBoundingClientRect`) and hosts (slot positions, the
  inspector) share: box positions, the scroll offsets of the boxes whose content they are in
  (`Box.contentParent()`), and CSS transforms resolved as the painter resolves them.
- input ← paint: hit testing is `Painter.hitTest(x, y)`, which mirrors paint order, transforms, clipping,
  scrolling, `pointer-events` and `visibility`. The `HitResult` carries the point in the hit box's coordinates, the
  scrollbar hit (if any), and the caret offset in text (computed on request).
- text: `layout.TextMeasure` (one per document, `LayoutEngine.textMeasure()`) is how wide text is for layout,
  painting, hit testing and controls alike: the host's advances plus `letter-spacing` and `word-spacing`. It caches
  the host's string widths (bounded), so relayouts do not measure the same words again.
- scrolling: an element owns its scroll position and smooth-scroll destination (`Element.scrollTo/scrollBy/
  scrollIntoView` with a `ScrollBehavior`); input, scripts, focus and layout (re-clamping) all scroll through it,
  and `dom.Scrolling` eases smooth scrolls and fires `scroll` once per frame per element that moved.
- script ↔ dom: scripts wrap DOM nodes; `Node.scriptWrapper` caches the wrapper. Inline `on*` attributes are
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
| `<item id="minecraft:diamond_sword" count="1" components="{...}">` | Renders an item stack (with count, durability bar). 16×16 intrinsic; scaled by CSS size and kept square (`object-fit: contain`). `tooltip` attribute shows the vanilla item tooltip on hover, with the lines of the `title` that applies after it. |
| `<slot index="n">` | A real container slot of the open menu at this position (only in container screens). 18×18 with the vanilla slot look; the item, hover highlight, clicks, drags and tooltips are vanilla. |
| `<entity type="minecraft:pig">` / `<entity player>` / `<entity id="123">` | A live entity, standing on the bottom of its box and fitted to it, or cropped to its head and shoulders (`-mc-entity-focus: eyes`), placed by `object-position`. Turned, viewed and sized by `-mc-yaw`, `-mc-pitch`, `-mc-model-scale` (below); `rotatable`, `follow-mouse` (softened by `-mc-gaze-reach` and `-mc-gaze-limit`), `walk`; created entities also take `baby`, `variant`, `color`, `components` and equipment by slot. |
| `<model block="minecraft:oak_stairs[facing=east]">` / `<model item="minecraft:trident">` | A block state or item drawn in 3D, centred in its box (or placed by `object-position`): at yaw and pitch 0 items as in the inventory and blocks in the inventory's usual view, turned by the same properties; `rotatable`. |
| `<player-head name="..." uuid="...">` | A player's face from their skin, kept square like an item. |
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
- `calc()`, `min()`, `max()`, `clamp()` for lengths, percentages and numbers, nested freely. Lengths fold into
  `Length`'s `px + %` pair where they can (sums, products with numbers, comparisons of all-px or all-% arguments);
  a comparison that mixes px and % (`clamp(72px, 25%, 100px)`) stays an expression that layout resolves against
  the percentage's reference, and serialises as written.

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
corner as a single Length; elliptical radii use the horizontal value), `background` (colour + layers),
`background-*` longhands (`-image`, `-size`, `-position`, `-repeat`, `-clip`), `flex`, `flex-flow`, `gap`,
`place-items`, `place-content`, `place-self`, `grid-template`, `grid-area`, `grid-row`, `grid-column`, `overflow`,
`font` (simplified), `text-decoration` (line keywords), `transition`, `animation`, `outline`, `transform-origin`,
`object-position`, `-mc-gaze-limit`, `list-style` (ignored except `none`), `-webkit-line-clamp`.

`object-position` takes a `<position>` (one to four values: keywords, lengths, percentages) and is stored per axis
(`-vellum-object-position-x`/`-y`, as `transform-origin` is). An axis no rule set is `Length.AUTO`: it serialises as
`50%` and centres content like the initial value, but content can tell it apart, so `<entity>` keeps its own default
(below). It is paint-only and animates. An unset axis has no position to interpolate from, so a change to or from it
applies at once (halfway through in `@keyframes`).

Vellum extensions: `-mc-tint: <color>` (multiply images, sprites, entities and models; items cannot be tinted),
`-mc-tooltip-delay: <time>` (inherited, initial `500ms`, non-negative: how long the pointer rests before a `title`
shows; read by input only, so a change neither relayouts nor repaints),
`text-shadow: minecraft` (the game's native 1px shadow), `font-family: minecraft:default | minecraft:uniform | minecraft:alt | minecraft:illageralt |
<any font id>` (also the aliases `monospace` → uniform, `sans-serif`/`serif`/`system-ui` → default; names without a
namespace are `minecraft:` ids). `host.FontFamilies` is the one mapping, used by the style engine and every host.

3D content (`<entity>`, `<model>`) is turned by CSS, so transitions and `@keyframes` animate it. These are paint-only
and not inherited:
- `-mc-yaw: <angle>` (initial 0): turns it about the vertical axis; positive turns its front to the right. Unbounded,
  so `@keyframes spin { to { -mc-yaw: 360deg } }` is a full turn.
- `-mc-pitch: <angle>` (initial 0): views it from above (positive) or below.
- `-mc-model-scale: <number>` (initial 1): multiplies the size that fits the box.
- `-mc-entity-focus: body | eyes` (initial `body`, `<entity>` only): what fills the box. `body` fits the whole entity
  with room to turn and `object-position` places that room (unset: `50% 100%`, standing on the bottom edge). `eyes`
  crops it to its head and shoulders: the box's shorter side spans 0.7 of its eye height (at least 0.4 blocks), and
  `object-position` places the point at its eye height on its upright axis (unset: `50% 40%`).
- `-mc-gaze-reach: <length>` (initial 40px, `<entity follow-mouse>` only): `follow-mouse` turns the head
  `40° × atan(d / reach)` toward a pointer `d` px from the eyes, sideways and up or down (`ComputedStyle.gazeYaw`,
  `gazePitch`). At 40px that is vanilla's inventory, up to about 63°.
- `-mc-gaze-limit: [<angle> | none]{1,3}` (initial `none`): caps that turn to either side, up and down. An omitted
  value repeats the one before it, so it serialises in the shortest form that reads back. Stored per part
  (`-vellum-gaze-limit-yaw`, `-up`, `-down`) as non-negative degrees, NaN for `none`, so `none` flips rather than
  interpolates. The caps bound only the gaze, not `-mc-yaw`, `-mc-pitch` or what dragging adds. A conversation
  card's speaker, with the pointer on the replies below: `entity.speaker { -mc-gaze-reach: 80px; -mc-gaze-limit:
  30deg 9deg; }`.

```html
<entity id="…" follow-mouse style="width:32px; height:32px; -mc-entity-focus: eyes; object-position: 50% 40%"></entity>
```

The `rotatable` attribute lets the pointer turn it as well: dragging sideways turns it, dragging up or down tilts the
view (up to 60° either way), and a flick keeps spinning and eases out. The engine handles it as a control: a press on
a replaced element with `rotatable` that no `mousedown` listener cancelled drives the element's `input.Turntable`
(in viewport px, on the document's frame clock, with frames requested while it spins), and the content adds
`Turntable.yaw(element)` and `Turntable.pitch(element)` to the CSS angles. The UA stylesheet gives rotatable
`<entity>` and `<model>` `cursor: grab` (`grabbing` while held).

### User-agent stylesheet (`engine/src/main/resources/vellum/ua.css`)
- `*, ::before, ::after { box-sizing: border-box }` (deliberate deviation: border-box everywhere).
- `html { color: #fff; font: 8px minecraft:default; line-height: normal }`, `body { margin: 0 }`.
- Headings: `h1` 16px, `h2` 12px, `h3`–`h6` 8px bold; paragraphs get `margin: 0 0 8px`.
- Vanilla-looking controls via sprites (`minecraft:widget/button`, `_highlighted`, `_disabled`,
  `widget/text_field`, `widget/text_field_highlighted`, `widget/checkbox*`, `widget/slider*`), with
  `text-shadow: minecraft` on button text.
- Minecraft elements: `item` 16×16, `player-head` 8×8, `entity` 32×48 and `model` 32×32, all `inline-block`;
  `item, player-head { object-fit: contain }`, so they stay square in any box. `slot` is the vanilla grey well.
- Utility classes prefixed `mc-`: `.mc-panel` (the vanilla grey container panel with bevel border), `.mc-inset`
  (a sunken slot bevel), `.mc-tooltip` (tooltip background and frame), `.mc-dark` (translucent dark panel used by
  vanilla menus), `.mc-label` (`#404040`, no shadow: container labels).

## 6. Layout

All layout is in floats (GUI px). Painting snaps to device pixels.

- Box tree. One `Box` per rendered element (`display: none` → none; `contents` → children only). Text and inline
  elements inside a block container produce line boxes; block children of an inline are handled by splitting into
  anonymous blocks (simplified: an inline containing blocks is blockified). Loose text in flex/grid containers is
  wrapped in anonymous flex/grid items. `::before`/`::after` with `content` become `PSEUDO` boxes (inline or block
  per their display) containing their text.
- Block formatting: width from containing block minus margins; `auto` margins centre; `min/max` constraints;
  `box-sizing`; vertical margin collapsing between siblings and parent/first-child (no clearance, since there are no floats).
- Inline formatting: whitespace processing per `white-space`; greedy line breaking at spaces (and anywhere for
  `word-break: break-all` / overlong words with `overflow-wrap: anywhere`); `text-align` including `justify`;
  `text-indent`; `letter-spacing`; `text-transform`; `line-height` with half-leading; `vertical-align` (baseline,
  middle, top, bottom, text-top, text-bottom, sub, super); inline boxes with padding/border/margin (horizontal only
  affects layout); atomic inlines (inline-block/flex/grid, replaced) aligned on the baseline; `<br>`;
  `text-overflow: ellipsis` with `white-space: nowrap` and `overflow` not visible; `line-clamp` (with ellipsis).
- Flexbox: the full CSS Flexbox §9 algorithm: direction and wrap (incl. reverse), `order`, flex base size from
  `flex-basis`/content, hypothetical main size with min/max (`min-width:auto` = content-based minimum), resolving
  flexible lengths with freezing, cross sizes, `align-items/self` (stretch, start, end, center, baseline),
  `justify-content` (all values), `align-content`, `gap`, auto margins, multi-line.
- Grid: explicit tracks (`px`, `%`, `fr`, `auto`, `min-content`, `max-content`, `minmax()`, `fit-content()`,
  `repeat(n | auto-fill | auto-fit, ...)`), `grid-template-areas`, line-based placement (numbers, negative, `span`,
  area names), auto-placement (row/column, dense), implicit tracks (`grid-auto-rows/columns`), `gap`, alignment
  (`justify-items/self`, `align-items/self`, `justify-content`, `align-content`). Track sizing is the spec algorithm
  simplified: no baseline alignment in grid.
- Positioning: relative (offset after layout), absolute (containing block = the padding box of the nearest
  positioned or transformed ancestor, an inline one contributing its fragments' bounds; `auto` insets resolve to the
  static position), fixed (the viewport, or the nearest transformed ancestor), sticky (as relative). Layout records
  the containing block (`Box.containingBlock`); an out-of-flow box's `Box.contentParent()` is the box whose content
  it is in, and the scrollers between it and its containing block neither scroll nor clip it. Gaining or losing a
  transform is layout-affecting (it changes containing blocks); a transform's value is paint-only. z-index and
  stacking are paint concerns.
- Overflow: scroll containers record `scrollWidth/scrollHeight` (`Box.maxScrollLeft/Top()` is the range); their
  content is laid out normally and painted shifted by the element's scroll offset. An out-of-flow box extends its
  containing block's scrollable overflow, not the scrollers it escapes. After a layout, scroll offsets are
  re-clamped through the element (firing `scroll` if that moves them). Text controls' overflow is their text
  (`Controls.overflow`), so a textarea scrolls by its element's offsets like any scroll container. Scrollbars are
  overlay (they do not take layout space), drawn by the painter, styled by `scrollbar-width` and the scrollbar colour
  properties.
- Replaced elements: intrinsic size from `ReplacedContent` (or `width`/`height` attributes), `aspect-ratio`,
  `object-fit` and `object-position` (applied at paint). Form controls are atomic boxes sized by the UA stylesheet;
  their children (option elements) are not laid out.
- Intrinsic sizes: min-content / max-content measurement for every formatting context (needed by flex, grid,
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
6. Replaced content (`ReplacedContent.paint`), sized by `object-fit` and placed by `object-position`
   (`ComputedStyle.objectX/objectY`). Items and heads are `object-fit: contain` in the UA stylesheet, so the box they
   are given is already their square. Content that fits itself inside its box places itself with the same methods:
   models in a square (`ComputedStyle.objectSquare`), entities by `style.EntityFraming`. Minecraft content draws
   through the Minecraft canvas, which its paint finds in one documented place (`McReplaced`).
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

- Transitions: on restyle, for each property in `transition-property` whose base value changed (and interpolates),
  start a transition from the current animated value to the new value with the duration, delay and timing function.
  A shorthand names the longhands it sets in the shorthand registry (`StyleEngine.transitionProperties`), so
  `transition: outline` animates the outline's width and colour but not `outline-offset`, as in CSS.
  Retargeting mid-flight starts from the current value (with the spec's reversing shortening for reversed transitions).
  `transitionrun/start/end/cancel` events.
- Keyframes: `animation-name` maps to `@keyframes`; keyframes resolved per element via the style engine;
  per-keyframe timing functions; iterations, direction, fill mode, delay, play state; `animationstart/iteration/end`.
  Animated values override the base style; transitions apply under animations as in CSS.
- Interpolation by `Prop.Interp`: lengths (px and % parts separately; with `min()`/`max()`/`clamp()` terms, as
  `calc(a × (1 − t) + b × t)`; keyword ↔ length flips at 50%), floats, ints (rounded), colours (premultiplied), shadow
  lists (pairwise, padding with transparent zero shadows), transform lists (pairwise by function type when lists
  match; otherwise decompose both to matrices and interpolate translate/rotate/scale/skew), discrete for everything
  else.
- Pseudo-elements animate like elements: `::before` and `::after` have their own transitions and animations, whose
  events go to the element with `pseudoElement` set.
- Used values. A used style is the base style with the target's effects applied, computed at used-value time:
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
  during drags (range thumb, scrollbar, text selection, a rotatable element's turntable), and the cursor from `cursor`
  via `Host.setCursor`.
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
- Tooltips (`input.Tooltips`): the element whose `title` / `title-json` applies is the nearest one with either
  attribute from the hover target up (an empty one means none, as in HTML). Its tooltip is due once the pointer has
  rested on it (or inside it) for that element's used `-mc-tooltip-delay` (500 ms unless a rule sets it; read when
  asked, so `needsFrame`, `settled` and the tooltip all follow a style change), and is hidden by a button or key
  press until the pointer reaches another tooltip's element. Its lines wrap at the host's width unless the element has `title-nowrap`. When the hover target
  is replaced content that shows its own tooltip (`ReplacedContent.showsTooltip`: an `<item tooltip>`), that
  tooltip shows instead, at once and through presses, and the title that applies adds its lines after the content's,
  never wrapped. A titled row that holds an item usually means "this item, and this about it", so the composition
  is the default; an empty `title` on the item opts out (D-013). The engine only decides; hosts ask
  `InputHandler.tooltip()` each frame after painting (a `Tooltip` record: the title's element, text and JSON, the
  pointer position, the content element when there is one, and whether to wrap) and draw it, so scripts can change
  the attributes live. `needsFrame` covers the moment the delay ends; a content tooltip has no delay to wait out.
- Narration (`input.Narration`, `InputHandler.narration()`): what the page gives a screen reader, computed only
  when a host asks. An element reads as an `Accessible`: a role (the `role` attribute's first token when Vellum
  knows it, else the tag's: button, link, checkbox, radio, slider, text box, combo box, tab, image, `<item>`,
  `<slot>`), a name, a value, a checked state, a hint, and its place among the tab stops. The name is `aria-label`,
  else the text `aria-labelledby` names, else the element's own (`alt`, a button input's `value`, the content's
  `ReplacedContent.accessibleName()`, a control's `<label>`), else its content's text cut to about 100 characters
  (not for fields, images and replaced content), else its own title, else a placeholder (D-016). The hint is
  `aria-describedby`, else the title that applies when it is not the name. `aria-hidden="true"` hides an element and
  its subtree. Text is read as laid out (`input.Accessibility`): whitespace collapsed, hidden content left out, each
  block a sentence joined by ". ". `focused()` is the focused element; `hovered()` the nearest element from the
  hover target up that is interactive or has a title or `aria-label`, stopping at an empty title. Live regions
  (`input.LiveRegions`): `aria-live` polite or assertive, else `role` status or log (polite) or alert (assertive);
  `announcements()` compares each region's text, without nested regions, with the last call's, when the DOM changed
  since (`domVersion`); a log announces its new children only (after those it kept, or the overlap of its old end
  with its new start); a region new since the last call announces its text, a log its last entry. Hosts call it
  once a frame, which is the debounce.
- Pointer leave: `InputHandler.mouseLeave()` when the host stops giving the document the pointer (an interactive
  HUD overlay whose screen closed): hover ends with `mouseout`/`mouseleave`, a drag ends, no tooltip.
- Focus: `focus`/`blur`/`focusin`/`focusout`; `:focus-visible` after keyboard navigation; `autofocus`; elements
  without a box (also the content of a closed `<details>`, hidden by the UA stylesheet) are not tab stops.
- Default actions run only if the event was not cancelled: checkbox/radio toggle, `label` forwards to its control,
  `details` toggle, link navigation, `select` dropdown, button click sound (`Host.playSound`).

## 10. Scripting

JavaScript, sandboxed. Engine choice and its reasons are in DECISIONS.md. The runtime:
- Blocks all Java access (no `Packages`, no `java.*`, class shutter denies everything), enforces a CPU and memory
  budget per entry (instruction observer; runaway scripts throw and are reported, the UI keeps working), and caps
  recursion. The budget is instructions plus a wall clock for slow host calls; the clock leaves out one-off work a
  cold JVM makes slow: compiling (charged to instructions by source length instead) and loading (a larger allowance
  while the document loads: scripts, the template install and first render). Section 13 has the whole security
  model.
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
    `vellum.open(url)` (open another UI), `vellum.nextTick(fn)` (runs `fn` once templates have rendered).
- Lifecycle: `DOMContentLoaded` and `load` after the scripts run; `Document.close()` (screen closed, overlay hidden,
  navigation, reload) fires `pagehide` then `unload` at the document (where `window` listeners are) while the
  runtime is still alive, then disposes it.
- Templates (no build step, AngularJS-style dirty checking): `{{ expr }}` in text and attributes,
  `v-if="expr"`, `v-for="item in expr"` (with `v-key`), `v-show`, `v-bind:attr` / `:attr`, `v-class`, `v-style`,
  `v-on:event` / `@event`, `v-model` (two-way for inputs). Expressions are JS evaluated with the scope chain
  `loop variables → vellum.data → state → globals`, where `state` is a reactive object created with
  `vellum.state({...})`. Templates render when the document loads; after that, any event handler, timer, rAF
  callback or data update marks them dirty and bindings are re-evaluated once per frame, before restyle; the DOM is
  touched only when a value changed. So, as in Vue, a handler that changes state sees the old DOM until the next
  frame (`vellum.nextTick(fn)` runs after the update). This makes server-driven UIs a template plus JSON.

## 11. Minecraft integration (`common/`)

- `McCanvas` implements `Canvas` over `GuiGraphicsExtractor`: own affine matrix stack set into the pose (the pose
  stack is only 16 deep); clips → `enableScissor` through `paint.ScissorStack` (intersected with the area the
  renderer draws, the framebuffer at its GUI scale, which for a frame after `Window.setWindowed` is smaller than the
  GUI; empty clips are never pushed and hide their content); alpha stack multiplied into colours; `fillRect` → `fill`
  (sub-pixel via pose translate); `fillQuads` → a custom `GuiElementRenderState` with `RenderPipelines.GUI`
  (submitted into the widened `guiRenderState`, clipped to the widened `scissorStack`); `drawText` → `Font` with a
  `Style` (font, bold, italic, underline, strikethrough, colour) scaled by `size/8`, through Minecraft's bidi
  reordering only when the text has right-to-left characters; `drawImage` → a textured quad (identifiers cached in `McImages`, canvases
  are registered dynamic textures); `drawSprite` → `blitSprite`. Rectangles are one render state each, sharing a copy
  of the transform until it changes.
- `McFontMetrics`: `Font.getSplitter().stringWidth(...)` with the style (bold widens), scaled. Each `FontSpec` keeps
  its resolved styles in its host slot; the shared table is keyed by families, bold and italic (not the size).
- Host: resources from the resource manager (`assets/<ns>/...`; UIs conventionally in `assets/<ns>/vellum/`),
  `minecraft:`-style URLs, sounds, clipboard, cursor (`CursorTypes`), logging to the mod logger, translations.
- Replaced elements: `item`, `slot`, `entity`, `model`, `player-head` (`McReplaced.ELEMENTS`); canvases are
  `McSurface`s (NativeImage + DynamicTexture); `mc-text` JSON is formatted by `McText`.
- `DocumentDriver`: one per shown page (screen, container screen, HUD overlay): load, viewport, frame and paint,
  input, messages, reload. After painting it shows the engine's tooltip at the engine's pointer through
  `setTooltipForNextFrame`. A title alone gets lines from `Font.split` at 170 px, as vanilla widget tooltips, or
  split only at newlines with `title-nowrap` (shown as vanilla's `List<Component>` tooltip); `title-json` is parsed
  like `<mc-text json>` and cut into lines at its newlines by `McText.lines`. The driver keeps the last title's text
  and makes each form of its lines when first asked for. Over an `<item tooltip>` the item content
  (`McReplaced.showTooltip`, `ItemTooltips`) sets one tooltip: the item's lines (`Screen.getTooltipFromItem`), then
  those of the title that applies, if any, with the item's tooltip image, style and the gap after its name, as
  vanilla's item tooltip has them.
  NeoForge's client entry installs the overload that passes the stack on, so its tooltip events (gather components,
  colour, pre) see the item as for vanilla item tooltips.
  `onKey(Predicate<KeyEvent>)` handlers get key presses the page left alone (not cancelled, not used by a focused
  control, no text field focused), in order until one consumes it, before the screen's own keys: a mod's key
  mappings (close on the key that opened the screen, switch pages) work on a page that can't know them.
  `onClose(Runnable)` handlers run once when the owner closes the page for good (screen removed, overlay hidden),
  after the page's `unload`; not on navigation, reload or while suspended (link confirmation).
- 3D content: entities, blocks and items are `Scene`s drawn by `McCanvas.drawScene` as picture-in-picture renders
  (`GuiSceneRenderState`, `GuiSceneRenderer`, registered by both loaders, which pool renderers so any number draw in a
  frame). The picture is rendered at the GUI scale into the element's box and blitted with a colour, so 3D content
  is crisp at any size, fades with `opacity` and takes `-mc-tint`. Content asks `McCanvas.sceneVisible` first and
  resolves nothing for a box that is clipped away or transparent. Still models keep their picture between frames.
  - Entities are fitted (`EntityPortrait`) with a small margin to the room they need at any turn and at the
    current pitch, and that room is placed by `object-position`, unset on the bottom edge. `EntityReach` measures
    the room by submitting the entity through its renderer into a collector that reads the model cubes of every
    layer (whatever `order(n)` it is submitted in); never less than the bounding box, measured once per entity type,
    age and size (and apart for states a mod supplied). With `-mc-entity-focus: eyes` the scale comes from the eye
    height instead (the box's shorter side spans 0.7 of it) and the origin is placed so that the eye point, on the
    upright axis, lands where `object-position` says; the view tilt (pitch and the gaze lean) turns about the feet,
    so the origin is moved by the eye's projected height to keep the eyes still. The engine's `style.EntityFraming`
    holds these rules (where the room and the eye point go, the eyes scale), so the previewer's stand-in is framed by
    the same code. `follow-mouse` aims the gaze from
    that eye point (a third down the box with the body fit), turning the head as far as the element's
    `-mc-gaze-reach` and `-mc-gaze-limit` give; the body leans half of that turn and the head turns the rest on top,
    as in vanilla's inventory, so a capped gaze keeps the same split. Display entities are created client-side,
    never added to the world, and play their idle animations on the clock.
  - The render state comes from the function a mod registered for the type (`VellumEntities.registerPortraitState`,
    docs/API.md), else from the renderer, at the frame's partial tick. Either way the picture clears the shadow,
    outline, name tag, score, leashes and passenger offset, is lit full bright, and is posed: body turn, walk
    animation and scale. The head turns to the gaze; without one it looks ahead on Vellum's own states and keeps
    the mod's rotation on supplied ones (a slumped head stays slumped).
  - Blocks are resolved with one shared `BlockModelResolver` and drawn in vanilla's `block/block` GUI view (30° from
    above, turned 225°, 0.625 of the box) whatever the block: blocks whose item model uses another view (stairs are
    turned 135°) differ from their inventory icon at yaw 0.
  - Items are resolved in the `GUI` display context, so their own GUI transform applies and at yaw and pitch 0 the
    picture is the inventory icon, an item filling the box as it fills a slot. Yaw and pitch are applied in front of
    that transform: block-like items (`usesBlockLight`) are first tilted back by the 30° their transform adds, so they
    turn about their upright axis; flat items turn about the screen's vertical axis, and an item whose GUI transform
    has another tilt turns about a slanted axis.
- Page hooks: `VellumScreens.onPageLoad(url, hook)` runs when a page loads in any screen or overlay (opened,
  linked to, reloaded), before its scripts, so client-side pages get live data however they are reached;
  `VellumScreens.pages(url)` finds the drivers showing a page later, and `driver.merge(fields)` updates some fields
  of `vellum.data` and keeps the rest. The Mobdex showcase uses all three (`showcase.Mobdex`: every living entity
  type with its attributes and the player's kill statistics, which it asks the server for; while a Mobdex is open,
  `VellumClient.tick` compares the client's copy of the statistics every client tick and pushes them when they
  change).
- `VellumScreen` (`Screen`): owns a `Document`, forwards input (SDL key codes → DOM key names), sets the viewport
  to the GUI-scaled size, enables SDL text input while a text field is focused, `Escape` closes unless cancelled
  (Shift+Escape always closes, and so does the third cancelled Escape within 1.5 s: `DocumentDriver.keyPressed`),
  `isPauseScreen` set per screen with `pauses(boolean)` (default false), background: none (the page draws its own; `isInGameUi` true so the
  world shows). Minecraft tells a screen about the pointer only when it moves, and drops the first move after a
  screen opens, so every frame rendered with a pointer the driver compares the page's pointer
  (`InputHandler.pointer()`, where the last pointer event put it) with the mouse handler's exact one, and sends a
  move when they differ (`DocumentDriver.followPointer`, inside `extract`). Screens, container screens and
  interactive HUD overlays all follow the pointer this way; an overlay drawn in the HUD layer passes no pointer. A
  page opened under a resting cursor is hovered from its first frames, as vanilla widgets are. A move sent to a
  screen without moving the mouse handler lasts one frame, so `VellumAutomation` moves the mouse handler along with
  its events.
- Narration (`PageNarrator`, one per driver): a screen's title (`getTitle`, so also `getNarrationMessage`) is the
  page's `Document.title()`, else the screen's own; vanilla's `Screen` still schedules and collects narration (on
  opening, 750 ms after a mouse move, 200 ms after a press) and our `updateNarratedWidget` adds the page's element as
  vanilla adds a widget: the focused element unless it was the last one read, else the hovered one, with its place
  among the tab stops, then nested its title in vanilla's widget phrasing (`gui.narrate.button`,
  `gui.narrate.slider`, `gui.narrate.editBox`, `gui.narrate.tab`, `narration.checkbox`, `narration.item`; Vellum's
  own keys for links and radios), its usage (`narration.*.usage.*`) and its hint. With nothing to read, vanilla's
  screen usage. Every frame a page is drawn while the narrator reads system messages, the driver hands live regions'
  announcements to `GameNarrator` (`saySystemQueued`, or `saySystemNow` for assertive ones); an interactive HUD
  overlay over a screen also reads its hovered element itself, 750 ms after it changes. A navigation in a screen
  narrates the new page (`triggerImmediateNarration`). `VellumAutomation.narration()` collects a screen's narration
  with a fresh `ScreenNarrationCollector`, and `recordNarration()` records what the driver hands the narrator, also
  with it off.
- `VellumContainerScreen` (`AbstractContainerScreen`): same, plus `<slot index>` elements position the menu's
  slots where they are painted, every frame (`McCanvas.placeSlot`: after scrolling, transforms and clipping; mutable
  `Slot.x/y`, widened); vanilla slot/item/tooltip/carried-item rendering stays, and slots not painted this
  frame are moved off-screen. Its GUI area (`leftPos`, `topPos`, `imageWidth`, `imageHeight`, which recipe viewers
  read) is the page's content (`Coordinates.contentBounds`: the `data-vellum-bounds` elements, else body's in-flow
  children), set each frame after layout and before painting (`DocumentDriver.Owner.beforePaint`), so slot
  positions, relative to it as in vanilla, are placed against the same area. Slot data is sent to the page only when
  a stack changed.
  A registration's data function (`menu → JsonObject`) adds the mod's fields to that data; it is polled every client
  tick and the page is updated when its result or a stack changed. The page's tooltip (an `<item tooltip>`'s or a
  title) is shown after vanilla's slot tooltip (in `extractTooltip`), and only without one, so a hovered slot's item
  wins. Keys go to the page, then the driver's `onKey` handlers, then vanilla (the inventory key closes).
- HUD layers: `VellumHud.register(id, url)` shows a non-interactive document over the HUD (title cards, trackers).
  `register(id, url, Predicate<Screen> interactiveOver)` (or `Input.WHEN_CHAT_OPEN`, `Input.WHEN_CURSOR_FREE`: any
  screen) makes it interactive over the screens the predicate accepts, asked each frame and pointer event with the
  open screen. Over such a screen the loaders draw it after the screen (NeoForge `ScreenEvent.Render.Post` for the top screen, Fabric `ScreenEvents.afterExtract`) in a new
  stratum, flushing its own deferred tooltip (`extractDeferredElements`: the screen's pass is over), and route
  pointer events to it first (NeoForge `ScreenEvent.Mouse*.Pre`, cancelled when taken; Fabric
  `ScreenMouseEvents.allowMouse*`). Hover follows the mouse position each frame, as on a screen. A press goes to the topmost
  overlay with content under the pointer (`DocumentDriver.contentAt`: not `html`/`body`), which then gets its
  release and drags; otherwise the screen gets it. The wheel goes to the same overlay and falls through when unused.
  Without a screen, or under one the predicate rejects, the overlay is drawn in the HUD layer without a pointer
  (`mouseLeave` on the way). Keys stay with the screen.
- Networking: `vellum:open` (server → client: UI url or inline HTML, initial JSON data, session id),
  `vellum:data` (server → client: JSON for a session), `vellum:message` (client → server: session, channel, JSON),
  `vellum:close` and `vellum:closed`. Server API: `VellumServer.open(player, url, data)` returns a session handle
  with `push(data)`, `onMessage(channel, handler)`, `close()`; container screens open via a `MenuType` whose extra
  data carries the url. The codecs' string caps are the protocol's hard limits (a peer that writes past them fails
  to decode and is disconnected); the settings file can only lower them.
- Trust (docs/API.md, Security): a server's pages and a client's messages are both untrusted.
  - Server side, `VellumServer.handleMessage` drops a message unless its session belongs to the sender, it is under
    the session's token bucket (`TokenBucket`), `server.maxMessageChars` and `server.maxMessageDepth`, and it is one
    strict JSON value (`net.JsonLimits`: a linear depth scan before Gson reads anything, then a non-lenient read).
    Session ids stay positive and are checked against the sender's UUID; a player's sessions past
    `server.maxSessionsPerPlayer` end oldest first.
  - Client side, every server payload goes through `client.ServerPages`: the player's policy
    (`client.serverPages`), size and depth caps on pages and data, a token bucket on opens, and one waiting open
    (newest wins) that shows only over no screen, a container screen or a Vellum screen. A refused or replaced open
    is reported closed. The guard against reopen loops counts opens arriving within a second of the player closing
    a server page with Escape (`DocumentDriver.close` tells it), and blocks the server's pages after
    `client.reopenStrikes` in a row. State resets with the connection.
  - `DocumentDriver`: web links need a click or key press in the page in the last second and ask through
    `ConfirmLinkScreen`; a page's messages go through a token bucket that survives reloads and navigation;
    while a server page has a focused text field a notice is drawn in a new stratum over the page.
    `serverSession()` lets page hooks tell server pages apart.
  - `McHost.playSound` plays only ids the sound manager knows, through a per-driver token bucket, with volume capped
    and pitch clamped.
- Settings: `VellumConfig` reads `config/vellum.properties` on both sides in `VellumCommon.init`, typed settings
  with defaults, ranges and comments, written to the file when missing. A record of caps from another module (the
  engine's `Limits`) is mapped to `<prefix>.<component>` keys by `VellumConfig.section`, rebuilt on every load.
- Resources and hot reload: documents load through the resource manager and reload with resources (F3+T). In a
  dev environment pages are read from `src/main/resources`, and the files they were read from are polled
  (`FileStamps`) so saving one reloads open documents.
- Commands: `/vellum open <url>` (client), `/vellum demo`, `/vellum showcase [page]`, `/vellum inspect` (toggle
  inspector overlay).
- Inspector: F12 inside a Vellum screen toggles an overlay that highlights the hovered element's margin, border,
  padding and content boxes and shows its selector and size.
- Demo: `assets/vellum/vellum/demo/` has a gallery: a vanilla-styled settings page, a chest-style inventory made
  of `<slot>`s, a flexbox/grid showcase, an animated menu, a scripted counter/todo with templates, and a map-like
  canvas.

## 12. Testing

- `engine` tests (JUnit): parser, selectors, cascade, each layout mode (compared against hand-computed and
  browser-verified expectations), animation timing, events, scripting, and `EndToEndTest` for whole pages.
- Tests go through the real pipeline, the way hosts drive it. `testing/TestHost` is a deterministic host
  (Minecraft's ASCII glyph widths, the Rhino runtime, in-memory resources and canvases, recorded logs, errors,
  sounds and cursors); `TestHost.load(html)` parses, sets the viewport and runs the first frame, and returns a
  `testing/Page`: frames at chosen times, input at viewport points through the real hit test (`click(element)`
  aims where `Document.pointerTarget` does, at the centre of the part that shows, and fails when something covers
  it), and painting onto `testing/RecordingCanvas` (every call with its transform, alpha and clip, or as a string
  trace). Pages are styled by the real CSS engine; hand-built
  boxes, styles and hit testers are not used. The layout suite includes ~1250 Chrome-generated fixtures from Taffy,
  run as HTML pages with Taffy's Chrome setup as a stylesheet and Ahem metrics.
- `preview` snapshot tests render the canvas test sheet, `preview/src/test/resources/pages` and the demo UIs
  through the previewer's path (`ImageCanvas`, Java2D) with Minecraft's jar, ten frames 16 ms apart, and compare them
  with goldens in `preview/src/test/snapshots` (`-Dvellum.updateSnapshots=true` rewrites them). The item and
  `title-nowrap` tooltip snapshots render the autopilot's shop row page, which it also shows in game.
- GameTests (both loaders, headless): networking codecs, server API, container menus.
- Dev autopilot (`./gradlew :neoforge:runClient -Pautopilot`): opens each showcase page and demo UI in a real
  client (the 3D pages at GUI scales 2 and 3), screenshots it to `neoforge/runs/client/screenshots/`, and logs each
  page's frame rate, plus benchmark pages of 48 spinning entities, models and items, and a page of entity portraits
  (body and eyes fits at several sizes, `object-position`) whose armour stands come from a render state the autopilot
  registers (`VellumEntities`: arms, one raised, no base plate). It drives pages through `VellumAutomation`
  (docs/API.md), the public client API for dev automation: it waits for pages to settle, hovers the showcase title
  screen's first button for a burst of screenshots a tick apart, drags a turntable, opens a page under a resting
  cursor (hovered within its first frames), screenshots an item tooltip with a title's lines and plain and
  `title-nowrap` titles, closes a page with an `onKey` handler, fills in the templates demo, clicks a Mobdex row
  scrolled out of its list, checks on Chronicle's page that a map pin with `-mc-tooltip-delay: 0ms` shows its title
  on the first frame while a row with the default does not, and what the narrator is given there (the title, a
  hovered reply as "label button", the log's lines as they are added), and answers the demo toast overlay through
  chat, checking the overlay narrates its hovered button. Its own pages live in
  `assets/vellum/vellum/dev/`. Each step runs on a client tick; a wait is a step that queues itself again until its
  condition holds or it times out. Every check is logged, and the run ends with
  `Vellum autopilot finished: N checks, M failed` (an error when M is not 0).
- Previewer scripts (`--actions`, preview/README.md) drive a page headless with input and screenshots; `--narrate`
  prints what a narrator would be given.

## 13. Security

A multiplayer server can send a client any page: inline HTML, CSS and JS, or a bundled page with data it chooses
(D-010). The player's game must stay safe whatever the page does. Scripts must not run code outside the sandbox,
touch files or the network, or read other client state, and no page may crash the game or freeze it for more than a
moment. Every cap below is a field of `engine/Limits` (a record with a one-line javadoc per field and its default);
hosts pass their limits with `Host.limits()`, which defaults to `Limits.current()`, and the mod builds them from
`limits.<name>` keys in `config/vellum.properties`.

### No way out of the sandbox

- Globals come from `initSafeStandardObjects`: no LiveConnect (`Packages`, `java`, `JavaAdapter`, `JavaImporter`,
  `getClass`). `Builtins` deletes Rhino's other non-standard globals (`Continuation`, `Script`, `With`, `Call`,
  `JavaException`, `isXMLName`), and `Sandbox` turns E4X off.
- Every `Context` comes from `Sandbox`, the only `ContextFactory` in the code: interpreted mode, and a class shutter
  that denies every class. Rhino attaches `javaException` and `rhinoException` to error objects only for classes the
  shutter shows, so no error a script catches carries a Java object. No other thread runs scripts.
- Bindings never hand scripts a Java object. `Js.toJs` is the one conversion and throws for any type it does not
  know; collections become fresh JS arrays and maps plain objects. Host methods take `this` through `Js.unwrap`, which
  checks the Java type, so calling `Element.prototype.getAttribute` on a plain object or a style declaration is a
  TypeError. `SandboxEscapeTest` walks everything a script can reach, reading every property through its getter, and
  finds no Java wrapper.
- `eval`, `Function` and string timers are allowed: they compile into the same sandbox.

### CPU

- Each entry (a script, handler, listener, timer, frame callback, message, template update) has an instruction
  budget and a wall clock (`instructionBudget`, `timeBudgetMs`, `loadTimeBudgetMs`). Rhino's regular expressions
  count backtracking steps as instructions, so a catastrophic pattern stops like a loop.
- Built-ins that loop or allocate in Java are invisible to the instruction count, so `Builtins` wraps them with a
  size check: every `Array.prototype` method and `Array.from` check the length of `this` and of array arguments
  (`maxArrayLength`), `apply` and `Reflect.apply`/`construct` the argument list, typed arrays and `ArrayBuffer` their
  bytes (`maxBufferBytes`). `repeat`, `padStart`, `padEnd`, `replace`, `replaceAll` and `join` check the length of
  their result (`maxStringLength`), and `split`, `match`, `matchAll`, string iteration, `String.raw` and the RegExp
  symbol methods the number of pieces they would make. `JSON.stringify` checks each array through a replacer (so
  getters and `toJSON` cannot slip one past), and `JSON.parse` refuses text nested deeper than `maxDepth`.
- BigInt arithmetic runs in `java.math.BigInteger`, one operation at a time and out of the budget's sight, and
  operators cannot be wrapped. So the build patches Rhino (`rhino/build.gradle`, `patchRhino`): every BigInteger
  multiplication, power, shift and parse in `ScriptRuntime`, `NativeBigInt` and `TokenStream` goes through
  `VellumBigInts` first, which refuses results over `maxBigIntBits`. The task fails if a Rhino upgrade leaves it
  nothing to rewrite.
- Timers and frame callbacks run until they have taken `frameScriptTimeMs` in a frame; the rest wait for the next.
  An entry that runs out of budget is reported and the page goes on, but after `maxBudgetOverruns` of them the page
  is stopped. A page whose frames (scripts, style, layout and paint together) take longer than `slowFrameMs` for
  `maxSlowFrames` frames in a row is stopped too, whatever makes it slow.
- Engine work a page triggers without scripts is bounded by sizes: `maxNodes`, `maxDepth` (the HTML parser flattens
  deeper markup, as browsers do), `maxCssNesting` (the CSS tree builder empties deeper functions and blocks, so
  `calc()`, `:is()` and nested rules never recurse further), `maxSelectorParts`, `maxGridTracks`, `maxListItems`
  and `maxVarLength` (each `var()` that uses the previous one twice doubles the text). Selector matching gives up on
  an ancestor chain as WebKit and Servo do, so descendant combinators take linear time instead of exponential, and
  `:has()` cannot be nested. Parsed text is joined once per run, not once per comment.

### Memory

- An entry may allocate `entryAllocation` bytes (counted per thread by the JVM, where it can). Going over stops the
  entry as the CPU budget does, and a page stopped after memory overruns lets go of what it built.
- When an entry finds the heap fuller than `heapLimitPercent` after a collection, the page is stopped at once and
  released. This is what catches a page that keeps a little of each entry's work until the heap fills.
- Sizes cap what a page holds: nodes, canvases (`maxCanvasSize` a side, `maxCanvasPixels` for all of a page's
  canvases), markup set by scripts (`maxMarkupLength`), storage (`storageQuota`), pending timers and frame callbacks
  (`maxTimers`), v-for items (`maxForItems`).
- An `OutOfMemoryError` stops the page like any engine error, and the document drops its tree and scripts so the
  memory comes back while the error panel shows. This covers strings built by `+`, which Rhino keeps as ropes and
  flattens in one Java allocation; that allocation fails without taking the heap, since it never happens.

### Failures stay in the page

- `Document`'s boundary catches every `Throwable`, `StackOverflowError` and `OutOfMemoryError` included: the page
  stops and shows its error, the game goes on.
- Recursion through host calls (a listener that clicks its own element, `toString` calling `String()`) grows the
  Java stack, which the interpreter's depth limit does not count. `RhinoScriptRuntime` catches the overflow at the
  outermost entry and reports it as that entry's error; it counts as an overrun.
- Console messages and script errors reach the log at `logRate` lines a second, each cut to `maxLogLength`
  characters. `vellum.send` and `vellum.playSound` are the host's to limit (`Host.send` may refuse): the mod counts
  them per screen, so a page can't reset its allowance by reloading (section 11, Trust).

### What is left

- A page can still allocate up to its limits each frame and make the game collect garbage more often.
- Strings built by concatenation have no cap of their own (Rhino joins them lazily, in Java): the entry allocation
  budget, the heap check and the error boundary catch them after the fact. Other built-ins not listed above may
  still make large results from a long string in one call; the same three catch those.
- The heap check looks at the whole heap. When the game itself has nearly filled it, pages stop until a collection
  frees room.
- The per-entry allocation budget needs a JVM that counts allocation per thread (HotSpot and its builds do).
- A page can navigate or reload itself over and over, each load within its budgets. What a page does to the player
  outside the engine is the mod's job (section 11, Trust): Shift+Escape and three quick Escapes close a page that
  keeps Escape, web links need a click or key press and ask first, and a server that reopens its page as the player
  closes it is stopped.

`engine/src/test/java/dev/vellum/engine/security/` holds a test for each attack: escape attempts, built-ins that loop
or allocate, timer and frame storms, memory exhaustion, deep markup and CSS, selector backtracking, recursion
through host calls.
