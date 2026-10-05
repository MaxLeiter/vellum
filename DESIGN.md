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
| `host` | `Host` (what the engine needs from its environment), `FontMetrics`, `FontSpec`, `ReplacedContent`, `Urls` |
| `style` | `ComputedStyle`, `Prop` (property registry), value types (`Length`, colours, enums, `Image`, `Shadow`...) |
| `css` | Tokenizer, parser, selectors, cascade (`StyleEngine`), the user-agent stylesheet |
| `html` | `HtmlParser`, `HtmlSerializer` |
| `layout` | `LayoutEngine`, `Box`, `LineBox`, `Fragment`; block, inline, flex, grid, positioning |
| `paint` | `Painter` (paint order + hit testing), `Canvas` (backend contract), `Shapes` (tessellation) |
| `anim` | `AnimationEngine`: transitions, @keyframes animations, `element.animate()` |
| `input` | `InputHandler` (pointer, wheel, keyboard, focus), form controls, smooth scrolling |
| `script` | `ScriptRuntime` contract, the Rhino-based runtime, DOM bindings, `vellum.*` API, template bindings |

## 3. The pipeline

`Document` drives everything on one thread (the render thread in Minecraft):

```
host: setViewport(w, h, guiScale)    on resize
host: input.mouseMove/mouseDown/...  on input  → DOM events, hover/active/focus flags, default actions
host: frame(nowMs)                   every frame:
        scheduler.run      timers, requestAnimationFrame
        input.tick         smooth scroll, caret blink
        styleEngine.restyle    if style dirty: cascade → element.baseStyle; animations.styleChanged → element.style
        animations.tick    advance transitions/animations → element.style; invalidates layout if needed
        layoutEngine.layout    if layout dirty: box tree → element.box
host: paint(canvas)                  every frame: painter walks boxes → canvas calls
```

Dirty tracking is document-wide (any DOM, attribute, state or text change marks style and layout dirty). Full
restyle and relayout of a few hundred elements is cheap; per-subtree invalidation can come later without API changes.

Per-element results live on `Element`: `baseStyle` (cascade), `style` (after animations, used by layout and paint),
`beforeStyle`/`afterStyle`, `box`, `replaced`, scroll offsets, and opaque slots for subsystem state
(`animationState`, `controlState`, `parsedInlineStyle`, `scriptWrapper`).

### Contracts between subsystems
- **css → anim**: after computing an element's base style the style engine calls
  `document.animations().styleChanged(el, oldBase, newBase)`; the animation engine sets `el.style`.
  For keyframes the animation engine calls `StyleEngine.resolveKeyframes(el, name, base)` which returns
  `List<ResolvedKeyframe(offset, timing, style, props)>`, each keyframe's declarations computed for that element.
- **layout ← style**: layout reads only `element.style` / `beforeStyle` / `afterStyle` and `Host.fonts()`.
  Boxes use the coordinate rules in `Box`'s javadoc.
- **paint ← layout**: the painter reads the box tree from `LayoutEngine.root()`. Form controls are painted by
  `input.Controls.paint(canvas, box)`, called by the painter after the box's background and border.
- **input ← paint**: hit testing is `Painter.hitTest(x, y)`, which mirrors paint order, transforms, clipping,
  scrolling, `pointer-events` and `visibility`.
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
| `details` / `summary` | Toggle `open`. |
| `dialog` | Hidden unless `open`; `showModal()` puts it in the top layer with a backdrop. |
| `img src` | Texture (`ns:textures/...png`), sprite (`sprite:ns:path`), or canvas. |
| `canvas width height` | 2D drawing surface (subset of CanvasRenderingContext2D: fillRect, clearRect, strokeRect, drawImage of sprites/textures/items, fillText, getImageData/putImageData, paths of lines and rects). Backed by a texture. |
| `template` | Inert content for scripts. |
| `script`, `style`, `link rel=stylesheet` | As in HTML. Scripts run in document order after parsing (like `defer`). |

Minecraft elements (provided by the Minecraft host as replaced content):

| Element | Behaviour |
|---|---|
| `<item id="minecraft:diamond_sword" count="1" components="{...}">` | Renders an item stack (with count, durability bar). 16×16 intrinsic; scaled by CSS size. `tooltip` attribute shows the vanilla item tooltip on hover. |
| `<slot index="n">` | A real container slot of the open menu at this position (only in container screens). 18×18 with the vanilla slot look; the item, hover highlight, clicks, drags and tooltips are vanilla. |
| `<entity type="minecraft:pig">` / `<entity player>` / `<entity id="123">` | A live entity render, optional `follow-mouse`, `scale`, `rotate`. |
| `<player-head name="..." uuid="...">` | A player's face from their skin. |
| `<sprite src="ns:path">` | Shorthand for a GUI sprite at its natural size. |
| `<mc-text>` with `key="..."` and optional `args`, or `json='...'` | Translated or component text, as a normal inline element. |

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
- Images: `url(...)`, `sprite(ns:path)`, `linear-gradient()`, `repeating-linear-gradient()`, `radial-gradient()`.
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
<any font id>` (also the aliases `monospace` → uniform, `sans-serif`/`serif`/`system-ui` → default).

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
- **Positioning**: relative (offset after layout), absolute (containing block = nearest positioned ancestor's
  padding box; `auto` insets resolve to the static position), fixed (viewport), sticky (as relative). z-index and
  stacking are paint concerns.
- **Overflow**: scroll containers record `scrollWidth/scrollHeight`; their content is laid out normally and painted
  shifted by the element's scroll offset. Scrollbars are overlay (they do not take layout space), drawn by the
  painter, styled by `scrollbar-width` and the scrollbar colour properties.
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
6. Replaced content (`Canvas.drawReplaced`), with `object-fit`.
7. Children: clip to the padding box if `overflow` is not visible (rectangular clip; rounded clip is not supported),
   translate by `-scroll`, paint children and line fragments.
8. Scrollbars (overlay), outline (`outline`, `outline-offset`; focus rings), and `::after` order handled by the box
   tree.

Text runs: `drawText` per fragment with colour, decorations, and `text-shadow` layers (drawn first, offset, in the
shadow colour; `text-shadow: minecraft` uses the host's native shadow). Ellipsis is already in the fragment text.

Snapping: rectangle edges round to device pixels (`Canvas.devicePixel()`) so borders stay crisp at every GUI scale.

Hit testing walks the same order in reverse, applies inverse transforms, honours clips and scroll offsets, skips
`pointer-events: none` and `visibility: hidden`, and returns the deepest element (text hits resolve to the parent
element, with the character offset for caret placement).

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
- Layout-affecting animated properties invalidate layout each frame; paint-only ones (opacity, transform, colours)
  do not.
- `element.animate(keyframes, options)` from scripts creates the same animation objects (Web Animations subset:
  `finished` promise-like callback, `cancel()`, `pause()`, `play()`, `reverse()`).
- `prefers-reduced-motion` media query reflects a host setting.

## 9. Input and forms

- Pointer: hover chain (`:hover` on target and ancestors), `mouseover/out/enter/leave/move`, `mousedown/up`, `click`
  (same element down and up), `dblclick`, `contextmenu` (right button), `:active` while pressed, pointer capture
  during drags (range thumb, scrollbar, text selection), and the cursor from `cursor` via `Host.setCursor`.
- Wheel: `wheel` event; if not cancelled, scrolls the nearest scrollable ancestor that can move in that direction
  (smooth when `scroll-behavior: smooth`, default on), with scroll chaining.
- Scrollbars: overlay thumbs appear when a container is scrollable; hover widens them; drag to scroll; click track
  to page.
- Keyboard: `keydown`/`keyup` to the focused element (or body); Tab / Shift+Tab focus navigation by tabindex order;
  Enter/Space activate buttons, checkboxes, links; arrow keys on range, radio groups and selects; Escape bubbles to the
  host (closes the screen unless a script calls preventDefault or a dropdown/dialog is open).
- Text fields: caret, selection (shift+arrows, mouse drag, double-click word, ctrl/cmd+A), clipboard (copy, cut,
  paste via `Host`), word-wise movement (ctrl/alt), Home/End, undo/redo (simple stack), `maxlength`, `placeholder`,
  `readonly`, horizontal scroll to keep the caret visible; `beforeinput`, `input`, `change` (on blur/Enter) events;
  textarea with line navigation.
- Focus: `focus`/`blur`/`focusin`/`focusout`; `:focus-visible` after keyboard navigation; `autofocus`.
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
    `vellum.open(url, data)` (open another UI).
- **Templates** (no build step, AngularJS-style dirty checking): `{{ expr }}` in text and attributes,
  `v-if="expr"`, `v-for="item in expr"` (with `v-key`), `v-show`, `v-bind:attr` / `:attr`, `v-class`, `v-style`,
  `v-on:event` / `@event`, `v-model` (two-way for inputs). Expressions are JS evaluated with the scope chain
  `loop variables → vellum.data → state → globals`, where `state` is a reactive object created with
  `vellum.state({...})`. After every event handler, timer, rAF callback and data update, bindings are re-evaluated;
  the DOM is touched only when a value changed. This makes server-driven UIs a template plus JSON.

## 11. Minecraft integration (`common/`)

- **McCanvas** implements `Canvas` over `GuiGraphicsExtractor`: own affine matrix stack set into the pose (the pose
  stack is only 16 deep); clip stack → `enableScissor`; alpha stack multiplied into colours; `fillRect` → `fill`
  (sub-pixel via pose translate); `fillQuads` → a custom `GuiElementRenderState` with `RenderPipelines.GUI`
  (submitted through a mixin accessor for `guiRenderState`/scissor); `drawText` → `Font` with a `Style` (font,
  bold, italic, underline, strikethrough, colour) scaled by `size/8`; `drawImage` → `blit` (textures by id,
  `canvas:` dynamic textures); `drawSprite` → `blitSprite`; `drawReplaced` → the element's own renderer.
- **McFontMetrics**: `Font.getSplitter().stringWidth(FormattedText)` with the style (bold widens), scaled.
- **Host**: resources from the resource manager (`assets/<ns>/...`; UIs conventionally in `assets/<ns>/vellum/`),
  `minecraft:`-style URLs, sounds, clipboard, cursor (`CursorTypes`), logging to the mod logger, translations.
- **Replaced elements**: `item`, `slot`, `entity`, `player-head`, `sprite`, `img`, `canvas` (NativeImage +
  DynamicTexture), `mc-text`.
- **VellumScreen** (`Screen`): owns a `Document`, forwards input (SDL key codes → DOM key names), sets the viewport
  to the GUI-scaled size, enables SDL text input while a text field is focused, `Escape` closes unless cancelled,
  `isPauseScreen` configurable (default false), background: none (the page draws its own; `isInGameUi` true so the
  world shows).
- **VellumContainerScreen** (`AbstractContainerScreen`): same, plus `<slot index>` elements position the menu's
  slots after each layout (mutable `Slot.x/y` via mixin accessor), vanilla slot/item/tooltip/carried-item rendering
  stays, and slots not present in the document are moved off-screen.
- **HUD layers**: `VellumHud.register(id, url)` shows a non-interactive document over the HUD (title cards, trackers).
- **Networking**: `vellum:open` (server → client: UI url or inline HTML, initial JSON data, session id),
  `vellum:data` (server → client: JSON for a session), `vellum:message` (client → server: session, channel, JSON),
  `vellum:close`. Server API: `VellumServer.open(player, url, data)` returns a session handle with `push(data)`,
  `onMessage(channel, handler)`, `close()`; container screens open via a `MenuType` whose extra data carries the url.
- **Resources and hot reload**: documents load through the resource manager and reload with resources (F3+T). In a
  dev environment a file watcher on `src/main/resources` reloads open documents on save.
- **Commands**: `/vellum open <url>` (client), `/vellum demo`, `/vellum inspect` (toggle inspector overlay).
- **Inspector**: F12 inside a Vellum screen toggles an overlay that highlights the hovered element's margin, border,
  padding and content boxes and shows its selector and size.
- **Demo**: `assets/vellum/vellum/demo/` has a gallery: a vanilla-styled settings page, a chest-style inventory made
  of `<slot>`s, a flexbox/grid showcase, an animated menu, a scripted counter/todo with templates, and a map-like
  canvas.

## 12. Testing

- `engine` unit tests (JUnit): parser, selectors, cascade, each layout mode (compared against hand-computed and
  browser-verified expectations), animation timing, events, scripting.
- `engine/src/test/java/dev/vellum/engine/testing/TestHost`: a deterministic host with Minecraft's ASCII glyph widths,
  used by all tests. `ImageCanvas` (Java2D) renders documents to PNGs for snapshot tests and the previewer.
- GameTests (both loaders, headless): networking codecs, server API, container menus.
- Dev autopilot (`./gradlew :neoforge:runClient -Pautopilot`): opens each demo UI in a real client, at GUI scales 2
  and 3, and screenshots it to `neoforge/runs/client/screenshots/`.
