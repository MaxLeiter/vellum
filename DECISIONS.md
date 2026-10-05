# Vellum — Decisions

Numbered decisions with the reasons and the alternatives considered. Newest last.

## D-001 Name: Vellum
**Choice.** The mod is called Vellum: mod id `vellum`, packages `dev.vellum.*`.
**Why.** The working name "Cobweb" is taken. Crystal Nest's Cobweb library uses mod id `cobweb` and has over 30M downloads, so two mods with that id could not load together. Vellum (fine parchment, a surface you write and draw on) is free on Modrinth and CurseForge and fits a document engine.
**Alternatives.** Silk, Weave and Loom are all taken or ambiguous. Lattice is usable, but its slug is taken by a small mod.

## D-002 A pure-Java engine module, compiled into each loader jar
**Choice.** `engine/` has no Minecraft or loader dependencies and is compiled with `--release 21`. The loaders compile its sources into their own jars, the same way Chronicle handles sim-core and claudemons handles core.
**Why.** The engine can be unit-tested and previewed without the game, it can be ported to 1.21.1 (Java 21) cheaply, and there is no nested jar to version-resolve.

## D-003 Own HTML and CSS parsers
**Choice.** We write a forgiving HTML parser and a CSS Syntax 3 tokenizer, parser and selector engine.
**Why.** Together they come to about 2k lines, which is smaller than jsoup alone. We need custom value grammars anyway (shorthands, `var()`, `calc()`, `@keyframes`, Minecraft colours). No library computes a cascade, and our own parsers give authors line-level diagnostics.
**Alternatives.** jsoup (525 KB, full HTML5) and htmlunit-cssparser. Either can be revisited if HTML5 error recovery becomes a need.

## D-004 Own layout engine (block, inline, flex, grid, positioning)
**Choice.** We implement layout ourselves, following the CSS specs, and verify it against Taffy's Chrome-generated fixtures and hand-computed browser results.
**Why.**
- We want one box model in which inline text layout (line boxes, baselines, text inside flex items) is first-class. Layout libraries treat text as an opaque measure function.
- No dependency gets version-unified across a modpack. LDLib2 already jar-in-jars `dev.vfyjxf:taffy` and AE2's yoga.
- We keep full control over performance and over the `Box` output that paint, hit testing and slots rely on.
**Alternatives.**
- Yoga: its Java binding is JNI-only and ships only as Android AARs.
- AE2's pure-Java Yoga port: flex only, no block flow or grid.
- taffy-java: MIT, flex, grid and block. It is the fallback if our flex or grid falls short: vendor it as source, relocate it, and apply the CrystalGraphics `flex-wrap` measure fix.

## D-005 JavaScript: Rhino 1.9.1, relocated and sandboxed
**Choice.** We use Mozilla Rhino 1.9.1, relocated to `dev.vellum.shadow.rhino`, with its classes copied into the mod itself. It runs in interpreted mode with `initSafeStandardObjects`, a class shutter that denies everything, an instruction-count budget per call, and a stack depth limit. The runtime sits behind the `ScriptRuntime` interface.
**Why.** Rhino has no natives, is about 1.6 MB, and supports ES2015-ish code (let/const, arrows, template literals, destructuring, generators, Map/Set, Promise, optional chaining). Its sandbox hooks were verified. Interpreted mode does not define classes at runtime, which avoids classloader trouble.
**Known dialect limits (documented for authors).** No `class`, no `async`/`await`, no spread in calls, no `for (const x of ...)` (use `let`), no per-iteration `let` closures in `for` loops, and no modules.
**Alternatives.**
- GraalJS: about 60 MB, ships natives, and on a stock JDK runs in interpreter-only mode.
- Nashorn: needs ASM, which risks clashing, and supports fewer ES features.
- quickjs4j: QuickJS compiled through WASM to bytecode. It supports full modern JS but has RPC-style interop and is at version 0.x. It is plan B, and is why the engine is behind an interface.
- KubeJS's Rhino fork: a separate mod with no 26.3 build.

## D-006 Paint through Minecraft's own GUI renderer
**Choice.** The painter targets an abstract `Canvas`. In-game it is implemented over `GuiGraphicsExtractor`:
- Fills, text, sprites, items and textures map to vanilla calls.
- Custom geometry (rounded corners, gradients, borders) is tessellated into quads and submitted as a `GuiElementRenderState` on `RenderPipelines.GUI`.
- An SDF shader can come later behind the same `Canvas` methods.

**Why.**
- Text is the game's font, including resource-pack fonts.
- Sprites and nine-slices come from the active resource pack.
- Items and slots are vanilla.
- The GUI scale behaves as players expect.
- It works on both the OpenGL and Vulkan backends without our own GL code.

**Trade-offs.**
- No offscreen groups: opacity multiplies into each child, so overlapping translucent children blend individually.
- Clipping is rectangular, so there is no rounded `overflow: hidden`.
- The pose stack is only 16 deep, so the canvas keeps its own matrix stack.

## D-007 Units and defaults
**Choice.**
- `px` is a GUI pixel and scales with Minecraft's GUI scale. `dp` is one device pixel.
- The default `font-size` is 8px, the native em of Minecraft's font, so `1rem` = 8px.
- The UA stylesheet sets `box-sizing: border-box` on everything and `color: #fff` on the root.

**Why.** Text at multiples of 8px is pixel-perfect. Border-box is what almost every author resets to anyway. White text on the translucent world background matches vanilla menus.

## D-008 Document-wide dirty tracking
**Choice.** A change marks the whole document for restyle and/or relayout on the next frame: one flag each, set only when the change can affect that stage (attribute and state changes restyle, and the restyle decides whether layout must run; tree and text changes relayout).
**Why.** UI documents are small (hundreds of elements), and a full pass costs well under a millisecond, but typing or hovering should not relayout. The API leaves room for subtree invalidation later.

## D-009 Templates by dirty checking
**Choice.** We provide `{{ }}` interpolation and `v-if`/`v-for`/`:attr`/`@event`/`v-model` directives. Any handler, timer or data update marks the bindings dirty; they are re-evaluated once per frame, before restyle, and the DOM is touched only when a value changed.
**Why.** Server-driven UIs become a template plus JSON, with no build step and no virtual DOM. Dirty checking is simple, predictable and fast enough at this scale.

## D-010 Server-deliverable UIs are sandboxed by construction
**Choice.** A server can open bundled UIs or send inline HTML, CSS and JS. Scripts get no Java access, no network and no file system. Their CPU time is bounded, and their only channels are `vellum.send` / `vellum.on` to the server that opened them.
**Why.** This makes Vellum useful to server-side mods and plugins without trusting them with the client.
