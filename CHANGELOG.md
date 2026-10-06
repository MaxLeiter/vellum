# Changelog

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
