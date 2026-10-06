# Moving a mod's GUI to Vellum

How to port a hand-drawn screen (a custom `Screen` subclass with layout math, manual text wrapping, hit testing and
scroll state) to a Vellum page. Read the [Java API docs](index.md) first, then `docs/SCRIPTING.md` (the JavaScript dialect and templates).

## 1. Set up the dependency

Publish Vellum locally from a Vellum checkout, then depend on it (see [Setting up](api/setup.md)):

```bash
./gradlew publishToMavenLocal
```

```groovy
repositories { mavenLocal() }
// common/
dependencies { compileOnly("dev.vellum:vellum-common-26.3:0.3.0") }   // or vellum-common-1.21.1
// neoforge/ and fabric/
dependencies { implementation("dev.vellum:vellum-neoforge-26.3:0.3.0") }   // or vellum-fabric-26.3, and the 1.21.1 ones
```

If most of your UI moves to Vellum, make it a required dependency (`type="required"` in `neoforge.mods.toml`,
`"depends"` in `fabric.mod.json`) rather than keeping a vanilla fallback for every screen. Two UIs for one feature is the
duplication you are porting to get rid of.

## 2. The shape of a Vellum screen

Most hand-drawn screens already split three ways. The server (or the client's model) builds a view model, the screen
draws it, and clicks become actions. Keep that and replace only the drawing:

| Before | After |
|---|---|
| `ViewModel` record, drawn by `MyScreen.render(...)` | the same data as JSON in `vellum.data`; templates render it |
| click hit tests calling `sendAction("x", arg)` | `@click="vellum.send('x', arg)"`, received by `onMessage("x", …)` (client) or `session.onMessage("x", …)` (server) |
| "refresh every N ticks", rebuild widgets, restore focus/scroll | `driver.push(json)` / `session.push(json)`; the DOM updates in place, focus, scroll and drafts survive |

Pages live in your jar at `assets/<modid>/vellum/<name>.html` (plus `.css`/`.js` next to them) and are opened by id
`<modid>:vellum/<name>.html`. Put shared styles (palette as CSS custom properties, panels, typography) in one
stylesheet linked from every page.

## 3. What replaces what

| Hand-written today | In Vellum |
|---|---|
| `width - 8`, `Mth.clamp(pw * 27 / 100, 108, 138)`, two-column vs one-column branches | flexbox / grid, `min()`/`max()`/`clamp()`, `@media (max-width: …)` |
| `font.split`, ellipsize, clamp to N lines, justified text | normal text flow, `text-overflow: ellipsis`, `line-clamp`, `text-align: justify` |
| scroll offsets, scrollbars, "follow bottom", drag thumbs | `overflow: auto` (overlay scrollbars, wheel, drag, smooth), `el.scrollTo` / `scrollIntoView` |
| per-frame hover rectangles, link hit tests | elements with `:hover`, `@click`, `<a href>` |
| fade/slide/typewriter timers | `transition`, `@keyframes`, `el.animate()`, or a small timer in the page script |
| palette constants | CSS custom properties (`--panel: #1b1b22`) |
| `EditBox`, `Button`, sliders, cycle buttons | `<input>`, `<button>`, `<input type=range>`, `<select>`, checkboxes (vanilla look by default, restyle freely) |
| `g.entity(...)` portraits | `<entity type="…" follow-mouse>`, `<entity id="<network id>">`, `<entity player>`; `rotatable`, and CSS `-mc-yaw`/`-mc-pitch`/`-mc-model-scale`; a gentler or capped gaze than the inventory's with `-mc-gaze-reach` and `-mc-gaze-limit`; head-and-shoulders crops with `-mc-entity-focus: eyes` and `object-position`; your own render state (no bubbles or labels) with `VellumEntities.registerPortraitState`; `<model block="…">` or `<model item="…">` for blocks and items |
| `g.item(...)` icons, item tooltips | `<item id="…" count="…" tooltip>` |
| `NativeImage` + `DynamicTexture` maps and procedural art | register the texture under an `Identifier` and use `<img src="mymod:dynamic/map">` or `background: url(mymod:dynamic/map)`; or draw with `<canvas>` `getContext('2d')` |
| `blitSprite(...)` nine-slices | `background: sprite(mymod:widget/panel)` (honours the sprite's `.mcmeta` scaling) |
| HUD layers with hand-coded fades | `VellumHud.register(id, url)`, `show`/`hide`/`push`; CSS animations for the fades |
| chest-style menus | `VellumScreens.registerContainer(menuType, url)` and `<slot index="n">` wherever the slots go |
| English literals | `<mc-text key="…">` or `vellum.t(key, …args)` (Minecraft language files) |

## 4. How to port a screen

1. List what the screen shows and every action it sends. Write the view-model JSON shape down (often it
   already exists as a payload or record).
2. Sketch the page in the previewer first: `./gradlew :preview:run --args="path/to/page.html --data sample.json"`
   (live reload on save; F12 inspector). Use real sample data.
3. Wire actions with `vellum.send(channel, value)` and handle them where the old screen's actions were handled.
4. Open it from wherever the old screen opened (`VellumScreens.open(url, data)` on the client,
   `VellumServer.open(player, url, data)` on the server) and push updates instead of rebuilding.
5. Check it in game with hot reload (edit the page under `src/main/resources` while the dev client runs) and with
   your dev autopilot's screenshots at GUI scales 2 and 3.
6. Delete the old screen and its layout helpers once the page covers it.

## 5. Gotchas

- JavaScript is Rhino (ES2015-ish): no `class`, no `async`/`await`, no spread in calls, no `for (const x of …)`;
  a `const` inside a loop body keeps its first value, so use `let` in loops. Details in `docs/SCRIPTING.md`.
- Templates update on the next frame. Code that reads the DOM right after changing state uses
  `vellum.nextTick(fn)`.
- `vellum.data` keys shadow `vellum.state` and globals in template expressions. Nest page options under one key
  (`{start: {...}}`) rather than spreading them at the top level.
- Pixel sizes. `px` is a GUI pixel; the default font is 8px and stays crisp at multiples of 8 (12 and 16 are
  fine too). Design for ~427×240 GUI px (GUI scale 2 on 1080p) and check scale 3.
- Live entities cost frame time. A handful per screen is fine. In long lists use spawn eggs or `<item>` icons.
- Unsupported CSS is dropped and logged at debug level, with no visible error: floats, tables (use
  grid), 3D transforms, rounded `overflow: hidden` clipping. When something looks wrong, check the previewer's
  inspector.

## 6. When Vellum is the problem

If a port needs something Vellum doesn't do (a missing CSS feature, an element, an API hook) or hits a bug, report it
to the Vellum maintainers with a minimal page that reproduces it. Please don't work around it in your mod or patch a
copy of Vellum. The fix then lands in Vellum for every mod.
