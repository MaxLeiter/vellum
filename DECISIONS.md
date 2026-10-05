# Vellum decisions

Each decision has its reasons and the alternatives we looked at. Newest last.

## D-001 Name: Vellum
**Choice.** The mod is called Vellum: mod id `vellum`, packages `dev.vellum.*`.
**Why.** The working name "Cobweb" is taken. Crystal Nest's Cobweb library uses mod id `cobweb` and has over 30M downloads, so two mods with that id could not load together. Vellum (fine parchment you write and draw on) is free on Modrinth and CurseForge, and it suits a document engine.
**Alternatives.** Silk, Weave and Loom are all taken or ambiguous. Lattice is usable, but its slug is taken by a small mod.

## D-002 A pure-Java engine module, compiled into each loader jar
**Choice.** `engine/` has no Minecraft or loader dependencies and is compiled with `--release 21`. The loaders compile its sources into their own jars, the same way Chronicle handles sim-core and claudemons handles core.
**Why.** We can unit-test and preview the engine without the game, port it to 1.21.1 (Java 21) cheaply, and skip nested jars with their version resolution.

## D-003 Own HTML and CSS parsers
**Choice.** We write a forgiving HTML parser and a CSS Syntax 3 tokenizer, parser and selector engine.
**Why.** Together they come to about 2k lines, smaller than jsoup alone. We need custom value grammars anyway (shorthands, `var()`, `calc()`, `@keyframes`, Minecraft colours), no library computes a cascade, and our own parsers can report errors by line.
**Alternatives.** jsoup (525 KB, full HTML5) and htmlunit-cssparser. Either can be revisited if HTML5 error recovery becomes a need.

## D-004 Own layout engine (block, inline, flex, grid, positioning)
**Choice.** We implement layout ourselves, following the CSS specs, and verify it against Taffy's Chrome-generated fixtures and hand-computed browser results.
**Why.**
- We want one box model where inline text layout (line boxes, baselines, text inside flex items) is a first-class citizen. Layout libraries treat text as an opaque measure function.
- A layout dependency would have to agree on a version across a whole modpack. LDLib2 already jar-in-jars `dev.vfyjxf:taffy`, and AE2 bundles its own yoga.
- Performance and the `Box` output that paint, hit testing and slots rely on stay in our hands.
**Alternatives.**
- Yoga: its Java binding is JNI-only and ships only as Android AARs.
- AE2's pure-Java Yoga port: flex only, no block flow or grid.
- taffy-java: MIT, flex, grid and block. It is the fallback if our flex or grid falls short: vendor it as source, relocate it, and apply the CrystalGraphics `flex-wrap` measure fix.

## D-005 JavaScript: Rhino 1.9.1, relocated and sandboxed
**Choice.** We use Mozilla Rhino 1.9.1, relocated to `dev.vellum.shadow.rhino`, with its classes copied into the mod itself. It runs in interpreted mode with `initSafeStandardObjects`, a class shutter that denies everything, an instruction-count budget per call, and a stack depth limit. The runtime sits behind the `ScriptRuntime` interface.
**Why.** Rhino has no natives, weighs about 1.6 MB, and supports ES2015-ish code (let/const, arrows, template literals, destructuring, generators, Map/Set, Promise, optional chaining). We checked its sandbox hooks. Interpreted mode defines no classes at runtime, so there is no classloader trouble.
**Known dialect limits (documented for authors).** No `class`, no `async`/`await`, no spread in calls, no `for (const x of ...)` (use `let`), no per-iteration `let` closures in `for` loops, a `const` declared in a loop body keeps its first value (use `let`), and no modules.
**Alternatives.**
- GraalJS: about 60 MB, ships natives, and on a stock JDK runs in interpreter-only mode.
- Nashorn: needs ASM, which risks clashing, and supports fewer ES features.
- quickjs4j: QuickJS compiled through WASM to bytecode. It supports full modern JS, but interop is RPC-style and it is at version 0.x. It is plan B, and the reason the runtime sits behind an interface.
- KubeJS's Rhino fork: a separate mod with no 26.3 build.

## D-006 Paint through Minecraft's own GUI renderer
**Choice.** The painter targets an abstract `Canvas`. In-game it is implemented over `GuiGraphicsExtractor`:
- Fills, text, sprites, items and textures map to vanilla calls.
- Custom geometry (rounded corners, gradients, borders) is tessellated into quads and submitted as a `GuiElementRenderState` on `RenderPipelines.GUI`.
- An SDF shader can come later behind the same `Canvas` methods.

**Why.**
- Text is the game's font, including resource-pack fonts.
- Sprites and nine-slices come from the active resource pack.
- Items and slots are vanilla, and the GUI scale behaves the way players expect.
- It works on the OpenGL and Vulkan backends without any GL code of our own.

**Trade-offs.**
- No offscreen groups: opacity multiplies into each child, so overlapping translucent children blend individually.
- Clipping is rectangular, so there is no rounded `overflow: hidden`.
- The pose stack is only 16 deep, so the canvas keeps its own matrix stack.

## D-007 Units and defaults
**Choice.**
- `px` is a GUI pixel and scales with Minecraft's GUI scale. `dp` is one device pixel.
- The default `font-size` is 8px, the native em of Minecraft's font, so `1rem` = 8px.
- The UA stylesheet sets `box-sizing: border-box` on everything and `color: #fff` on the root.

**Why.** Text at multiples of 8px is pixel-perfect. Almost every author resets to border-box anyway. White text on the translucent world background matches vanilla menus.

## D-008 Document-wide dirty tracking
**Choice.** A change marks the whole document for restyle and/or relayout on the next frame: one flag each, set only when the change can affect that stage (attribute and state changes restyle, and the restyle decides whether layout must run; tree and text changes relayout).
**Why.** UI documents are small (hundreds of elements) and a full pass costs well under a millisecond. Typing or hovering still should not relayout, hence the separate flags. The API leaves room for subtree invalidation later.

## D-009 Templates by dirty checking
**Choice.** We provide `{{ }}` interpolation and `v-if`/`v-for`/`:attr`/`@event`/`v-model` directives. Any handler, timer or data update marks the bindings dirty; they are re-evaluated once per frame, before restyle, and the DOM is touched only when a value changed.
**Why.** A server-driven UI is a template plus JSON, with no build step and no virtual DOM. Dirty checking is simple and fast enough at this scale.

## D-010 Server-deliverable UIs are sandboxed by construction
**Choice.** A server can open bundled UIs or send inline HTML, CSS and JS. Scripts get no Java access, no network and no file system. Their CPU time is bounded, and their only channels are `vellum.send` / `vellum.on` to the server that opened them.
**Why.** Server-side mods and plugins can use Vellum without being trusted with the client.

## D-011 3D content is turned by CSS and drawn as tinted pictures
**Choice.** `<entity>` and `<model>` read three paint-only properties, `-mc-yaw`, `-mc-pitch` and `-mc-model-scale`, instead of attributes. Dragging (`rotatable`) adds to them: the engine handles presses on rotatable replaced elements like a form control (`input.Turntable` does the turning and easing), and the content reads the offsets. Entities, blocks and items are all drawn by one picture-in-picture renderer of our own (`GuiSceneRenderer`), which blits its picture with a colour.
**Why.**
- CSS gives transitions, `@keyframes`, `:hover` and `animation-play-state` for free, at no script cost. A script turning an attribute every frame restyled the document every frame.
- Dragging could be done in each page's script with pointer events and the properties. But then every page would repeat the inertia code, and turning would restyle the document every frame. Handled in the engine, it works in every host, takes the same viewport coordinates and frame clock as other drags, and keeps frames coming while a flick spins.
- Vanilla's entity renderer blits white: entities could not fade or be tinted. With our own renderer, opacity and `-mc-tint` work (the Mobdex shows unseen mobs as silhouettes), one fit and pose model covers entities and models, and still models keep their picture between frames. The cost is one widened field (the picture's texture view) and a dozen lines that mirror vanilla's entity picture.

## D-012 Entity framing is object-position plus one focus property
**Choice.** `object-position` places every replaced element, Minecraft ones included. An axis no rule set is stored as `auto` and serialises as `50%`, so content is centred as before while `<entity>` keeps standing on the bottom edge until a page sets a position. `auto` is no position, so it doesn't interpolate: a change to or from it applies at once. `-mc-entity-focus: body | eyes` picks what fills the box; with `eyes` the scale comes from the eye height (the box's shorter side spans 0.7 of it) and `object-position` places the eye point. The engine holds these rules (`style.EntityFraming`), so the previewer frames its stand-in with the game's code. Items and player heads are `object-fit: contain` in the UA stylesheet: the painter fits and places their square, as it does an image's box. Mods can supply the render state GUI renders start from (`VellumEntities.registerPortraitState`).
**Why.**
- A head-and-shoulders crop used to need a taller box raised inside an `overflow: hidden` frame, with offsets that depended on the fit's internals. Eye height is known for every entity, scales with babies, and stays put while the entity turns.
- Standard `object-position` keeps pages portable and works the same on images. A UA rule (`entity { object-position: 50% 100% }`) would have given entities their bottom default in plain CSS, but then the eyes focus could not have a default of its own.
- Vellum cannot know about a mod's overlays (speech bubbles, task labels). The mod already knows how to make a clean state; Vellum keeps posing it so `-mc-yaw`, `rotatable` and `follow-mouse` work, and leaves the head alone without `follow-mouse` so a mod can pose it (a slumped, powered-down robot).
**Alternatives.** Several `-mc-` properties for crop height, anchor and zoom; an `auto` keyword for `object-position`; a list of render state fields to keep. One property plus the standard one covers the portraits we have.

## D-013 An item's tooltip takes the title's lines by default
**Choice.** Over an `<item tooltip>`, the item's vanilla tooltip shows at once with the lines of the `title` / `title-json` that applies (the nearest from the item up) after its own, in one box, unwrapped. An empty `title` on the item opts out. A plain title wraps at 170 px unless its element has `title-nowrap`.
**Why.**
- Before this, the item's tooltip won and the title was dropped silently. A title on a row that holds an item says something about that item, as a merchant's price does under the item in vanilla.
- The workaround, rebuilding the item's lines in Java as one `title-json`, lost the tooltip image, the item's tooltip style, other mods' lines and NeoForge's tooltip events, and wrapped every line at 170 px.
- Item tooltips never wrap in vanilla, so appended lines don't either; authors break lines with `\n`.
- `title-nowrap` follows the `title` / `title-json` attribute family and HTML's old `<td nowrap>`. A CSS property would have been the first one about UA chrome, and would need the element's style where the host only reads attributes.
**Alternatives.** An opt-in attribute (`tooltip="merge"`) keeps the old behaviour, which dropped the title. A title width attribute (`title-width="250"`) adds a setting no page has needed yet.

## D-014 The gaze is softened and capped by two paint-only properties
**Choice.** `-mc-gaze-reach: <length>` replaces the inventory's 40px in `40° × atan(d / reach)`, and `-mc-gaze-limit: <yaw> [<up> [<down>]]` caps the head's whole turn (what the viewer sees), with `none` for no cap. The body leans half of the capped turn and the head turns the rest, as at the defaults, which reproduce vanilla exactly.
**Why.**
- Conversation cards put the replies far below the portrait, so a vanilla gaze bowed the speaker's head whenever the pointer was on a reply. Chronicle drew its own card to avoid that; a page should only need two declarations.
- Capping the whole turn makes `9deg` mean 9° on screen. Capping the lean, which is half of it, would have read as twice the number written.
- Keeping the split proportional keeps a capped pose a smaller copy of vanilla's.
- One shorthand whose optional second and third values give the tilt up and down costs one more longhand than a symmetric pair, and saves a third property for a portrait that looks up readily but barely nods.
**Alternatives.** A smooth cap (scaling the atan so it approaches the limit) would also change the turn near the eyes, so reach and limit would no longer be independent; a separate `-mc-gaze-pitch` for asymmetric limits; attributes on `<entity>`, which could not transition.
