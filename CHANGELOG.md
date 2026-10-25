# Changelog

## Unreleased

- `<scene>`: a small 3D world in a box. Blocks come from a structure file (`structure="ns:path/file.nbt"`) and `<block at state>` children; `<block-group x y z>` claims a box of them (`x="3..7" y="2"`, an axis left out being all of it) to show, fade (`opacity`), move (`-mc-translate`) and turn (`-mc-rotate`) with ordinary CSS transitions and animations; `<entity at>` children stand in it; and `<scene-anchor at>` pins HTML captions to points in it. The camera is `-mc-yaw`, `-mc-pitch`, `-mc-model-scale` and the new `-mc-scene-center`, plus dragging with `rotatable`. Hovering a block shows its name and fires `blockhover`, clicking fires `blockclick`. Each group is meshed once, with neighbors culled, fluids, connected blocks and block entities, and a scene that doesn't change is one blit a frame. Scripts change scenes by changing their children. 26.x only: on 1.21.1 a scene lays out and fires its events but draws nothing. The previewer draws scenes from the blocks' models. See "Scenes" in `docs/api/elements.md`, and the Cottage showcase (`/vellum showcase scene`), which builds itself step by step.
- `Host.loadBytes`: hosts give the engine binary resources (structure files). In game it reads resources, dev source trees and page folders as `loadText` does.
- Style changes cost much less when selectors can't see them: a script writing `el.style` or text every frame no longer rematches every selector in the page (about 1 ms a frame down to 0.2 ms on a 500-element page). Selectors are matched again only after tree changes, state changes (`:hover`, `:checked`...), changes to attributes some selector reads, `id` and `class`, and text that empties or fills an element.
- Changing a `<link>`'s `href`, `rel` or `media`, or text inside an element in a `<style>`, restyles the page at once, not with the next change that happens to.
- `DocumentDriver.patch(key, value)` sets one top-level field of `vellum.data` without sending the rest. Only the value is serialized and parsed, the page's other objects stay as they are, and the page gets `vellum.on('patch', ({key, value}) => ...)`, then its `'data'` listeners with the key as a second argument. A null value removes the field. Patches are kept across reloads, as pushes are. `VellumSession.patch` and `VellumHud.patch` do the same from a server and for HUD overlays; a server's patches count toward `client.maxDataChars` and `client.maxDataDepth`.
- `merge` no longer parses and re-serializes the whole data: the driver keeps each top-level field's JSON and only serializes the fields you pass. The page still gets the whole data.

## 0.7.0

- `VellumPages.folder(namespace, dir)` serves a namespace from a folder players can edit, such as one in `config/`. Relative stylesheets, scripts and images resolve inside it, saving any of them reloads the page, and nothing outside the folder can be read. See "Pages players edit" in `docs/api/pages.md`.
- If the shape shader fails to compile, Vellum draws shapes the old way until the next resource reload, instead of retrying the compile every 5 seconds (a hitch each time).
- Less work per frame: animations are only advanced a second time when an event handler changed them, outer box-shadows skip work the shader threw away, and the previewer no longer allocates per pixel.
- Less work per restyle, relayout and event: color transitions no longer copy the whole style twice a frame, grid track sizing measures each item once, flex and grid containers skip the anonymous boxes whitespace between items used to make, and event listeners only build error messages when something fails.
- The build no longer pulls in Mixin, whose annotation processor made every change recompile all of the mod's code.
- `&amp`, `&lt` and the like without a semicolon decode at the end of text and right before another `&` (`a &amp` was `a &&`).
- Deselecting an option that wasn't selected, when that leaves a select with no selection, now updates the page (`:checked`, `v-model`).
- A grid row's minimum counts a scroll container item's percentage margins.

## 0.6.2

- The jar is about 12% smaller (2.8 MB to 2.5 MB): classes keep line numbers for stack traces but not local variable tables, and Rhino's French and Chinese error messages are left out.

## 0.6.1

- On Minecraft 26.2 and 26.3, rounded boxes, rounded borders and box-shadows are drawn by a shader: smooth antialiased edges at any GUI scale or rotation, and a real Gaussian blur. `shape-rendering: crispEdges` keeps a page or element pixel-sharp, and `client.shapeShader=false` in `vellum.properties` turns the shader off for everything. 1.21.1 is unchanged.
- The previewer draws rounded boxes, borders and shadows the way the shader does, so pages look the same there as in game.
- `Canvas.fillBoxShadow`: a canvas backend can draw box-shadows itself.

## 0.6.0

- Timers a page sets while it loads wait their full delay. In game, the first frame jumped the clock from 0 to the game's own time, so a load-time `setTimeout` fired at once and `performance.now()` jumped. `performance.now()` and `requestAnimationFrame` now count from the page's first frame, as in browsers.
- The jar is about 150 KB smaller: Rhino's bytecode compiler, serialization and CommonJS loader, which Vellum never runs, are left out.
- Showing a HUD overlay again from its own `onClose` handler works (it was undone right after), so a queue of toasts is a few lines; `docs/api/hud.md` has the recipe.
- `-mc-entity-focus: center` fits an `<entity>` like `body` but zooms around its middle with `-mc-model-scale`, so zooming in no longer shows only the feet.
- `:key` works on any element, not only in a `v-for`: when the key changes the element is re-created, so its entrance animation replays. A keyed `v-if` branch does the same. The journal and Mobdex use it instead of one-item loops.
- What an animation's `finished` promise or event changes applies before the next frame is drawn, so the journal no longer flickers after a page turn.
- The templates and the Vellum build start from any JDK 17 or newer: Gradle downloads Java 25 for itself, which Fabric Loom needs.
- The showcase and demo pages are about 12% smaller and share `showcase/showcase.css`, with the same look.

## 0.5.1

- `list-style` and `list-style-type` work: `none` removes a list's bullets, and `disc`, `circle` and `square` pick them.
- Enter in a text field submits its form, as in browsers.
- NeoForge 26.3 no longer warns about Vellum's deprecated `logoFile` at startup.
- `template/` has example mods for NeoForge and Fabric that depend on Vellum from the maven.

## 0.5.0

- Vellum is on maven.maxleiter.com. Mods should make it a required dependency instead of bundling it.
- `.mc-dim` and `.mc-center` give a page vanilla's dimmed background and centered layout.
- Vellum now runs on Minecraft 26.2 as well as 26.3 and 1.21.1, on NeoForge (26.2.0.88) and Fabric (Fabric API 0.161.0+26.2). Everything works on 26.2 except the dev tour (`-Ptour`), which stays 26.3 only; docs/versions.md lists the differences.

## 0.4.1

- First release on CurseForge and Modrinth.
- Vellum has a mod icon.
- The jars include Rhino's license and notice, and say where the patched Rhino source is.

## 0.4.0

- Vellum now runs on Minecraft 1.21.1 as well as 26.3, on NeoForge and Fabric. Everything works on 1.21.1; the README lists the few differences.
- Scripts can use classes, async functions and `await`, spread arguments in calls, and `let` and `const` with a fresh binding in each loop iteration.
- A client `onMessage` handler that throws is logged and the other handlers still run.
- `/vellum demo chest` is the vanilla-looking chest. The old dark chest demo is gone.

## 0.3.0

- Pages from servers are treated as untrusted. Scripts get budgets for CPU time, memory, timers and frames, and the engine caps DOM size, CSS size and nesting depth.
- Every cap is a setting in `config/vellum.properties`, including the engine limits (`limits.*` keys).
- Messages from servers are parsed strictly, with size and depth limits and a cap on open sessions per player.
- Script errors caused by a bug in Vellum read "Internal error in <name>" on the page and put the details in the log.
- The demo chest looks like vanilla's, and `.mc-panel` has vanilla's black outline.
- Mobdex mobs call out when picked, and the trader chimes on a purchase.

## 0.2.0 to 0.2.6

- Narration: a page's `<title>` is the screen title, focused and hovered elements read like vanilla widgets, and `aria-live` regions speak.
- `-mc-tooltip-delay` sets how long a `title` tooltip waits.
- Entity portraits: `object-position`, `-mc-entity-focus`, `-mc-gaze-reach` and `-mc-gaze-limit`, and mods can supply render states.
- `VellumAutomation`, a client API for driving pages in tests (hover, click, drag, type, wait for a page to settle).
- `VellumScreen.pauses(boolean)` lets a screen pause a singleplayer world.
- `DocumentDriver.onKey` gives a mod the keys its page doesn't use.
- Pages are hovered from their first frame, under a resting pointer.
- The previewer can run a script of input and screenshots (`--actions`) and print what the narrator would read (`--narrate`).
- CSS: radial-gradient sizes, animated pseudo-elements, and `min()`, `max()` and `clamp()` mixing px and %.

## 0.1.x

- The first releases: the engine (HTML, CSS with block, inline, flex and grid layout, transitions and animations, sandboxed scripting and templates), screens, container screens with `<slot>`, HUD overlays, server pages, the previewer, and 3D `<model>` and `<entity>` content.
