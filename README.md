# Vellum

*Minecraft GUIs in HTML, CSS and JavaScript.* A Minecraft mod for 26.3 and 1.21.1 (NeoForge and Fabric) with a small web engine inside. You write a screen, inventory, HUD or map as a web page, and Vellum lays it out, animates it, runs its scripts and paints it with the regular GUI renderer.

The layout is _most_ CSS: block, inline, flexbox, grid and positioning, plus transitions and `@keyframes`. This is a 90-10 solution.

Scripts run in a Rhino sandbox with no Java access and no network.

LLMs were used extensively in the development of Vellum.

```html
<div class="mc-panel chest">
  <h2 class="mc-label">{{ title }}</h2>
  <div class="grid">
    <slot v-for="i in 27" :index="i - 1"></slot>
  </div>
  <button @click="vellum.send('sort')">Sort</button>
</div>
<style>
  .chest { width: 176px; margin: auto; }
  .grid { display: grid; grid-template-columns: repeat(9, 18px); }
  button { transition: transform .15s ease-out; }
  button:hover { transform: scale(1.05); }
</style>
```

Vellum is in development. Other mods (Chronicle, claudemons) use it as a library, and a server can send a page to its players.

## Goals

- Write Minecraft UIs the way you'd write a web page, and lean on what you already know about the web. Where Vellum behaves differently from a browser, the docs say so.
- Look vanilla by default. Buttons, inputs, panels, slots and tooltips use the game's own sprites, so an unstyled page already fits in.
- Make inventories easy. Put `<slot index="0">` where you want a slot and vanilla's clicking, dragging, shift-clicking and tooltips keep working. Items, entities, sprites, translations and player heads have their own elements too.
- Let servers open pages, push JSON to them and get messages back. That's why scripts are sandboxed.
- Stay pure Java with the JS engine relocated, so it won't clash with anything in a big modpack.
- Be fast enough that you never think about it. A few hundred elements restyle and relayout in well under a millisecond.
- Build and check UIs without launching the game, using the previewer and snapshot tests.

It isn't trying to be a browser.

## Try it

Start a dev client (see Building) and run these in a world:

| Command | What |
|---|---|
| `/vellum showcase` | A gallery of six full pages: a title screen, a HUD kit, a trader's market, a mobdex, a journal and a chat console for a robot. `/vellum showcase <name>` opens one directly. |
| `/vellum demo` | The feature demos: settings, layout, animation, templates, a map and a HUD overlay. `/vellum demo <name>` opens one. |
| `/vellum demo chest` | A chest-style inventory made of `<slot>`s (operators only) |
| `/vellum demo live` | A page the server pushes live data to (operators only) |
| `/vellum open <url>` | Any page, by resource id, for example `vellum:vellum/demo/map.html` |
| `/vellum reload` | Reload pages without restarting the client |

In a dev environment pages are read from `src/main/resources`, so saving one reloads it in the open screen.

## Previewer

You can also build pages without launching Minecraft. The previewer renders a page in a Swing window with the engine and the game's real font and sprites (read from your Minecraft jar), reloads when you save, and has an F12 inspector that outlines the hovered element's box model.

```bash
./gradlew :preview:run --args="path/to/page.html --scale 3 --size 427x240"
./gradlew :preview:run --args="page.html --snapshot out.png --frames 30"   # headless PNG
```

Flags, keys and how it finds the jar are in [`preview/README.md`](preview/README.md).

## Layout
| Module | What |
|---|---|
| `engine/` | No Minecraft dependencies. Contains the DOM, HTML and CSS parsers, cascade, layout, animation, painting to an abstract canvas, input and forms, sandboxed scripting |
| `rhino/` | Mozilla Rhino 1.9.1 with Vellum's patches, relocated to `dev.vellum.shadow.rhino` |
| `common/` | Vanilla-only Minecraft code: the canvas over Minecraft's GUI drawing, screens, container screens, HUD overlays, networking, the public API, demos |
| `neoforge/`, `fabric/` | Loader entrypoints |
| `<loader>/versions/<minecraft>/` | Where Stonecutter builds each Minecraft version, and the code of one version only (see Versions) |
| `preview/` | The standalone previewer |

For mod authors, [`docs/API.md`](docs/API.md) is the Java side, [`docs/SCRIPTING.md`](docs/SCRIPTING.md) the JavaScript dialect and templates, and [`docs/MIGRATING.md`](docs/MIGRATING.md) a guide to porting hand-drawn screens.

## Building
You need JDK 25 or newer and git; Gradle provisions the toolchains (25 for 26.3, 21 for 1.21.1).

Vellum uses a patched Rhino. The build downloads the upstream source pinned in `gradle.properties` (once, checked
against its SHA-256), applies the patches in `rhino/patches` and compiles it, so there is nothing to install first.
[`rhino/README.md`](rhino/README.md) says what the patches do and how to change them.

```bash
./gradlew :engine:test                 # engine tests
./gradlew build                        # everything, both Minecraft versions, including Fabric's headless GameTests
./gradlew :neoforge:26.3:runClient     # dev client (or :fabric:26.3, :neoforge:1.21.1, :fabric:1.21.1)
./gradlew :neoforge:26.3:runGameTestServer   # NeoForge's GameTests
./gradlew :preview:installDist         # previewer, at preview/build/install/preview/bin/preview
```

## Versions

Vellum builds for two Minecraft versions from one source tree, with [Stonecutter](https://stonecutter.kikugie.dev):

| Minecraft | NeoForge | Fabric | Java |
|---|---|---|---|
| 26.3 | 26.3.0.26-beta | Loader 0.19.5, Fabric API 0.161.0+26.3 | 25 |
| 1.21.1 | 21.1.255 | Loader 0.19.5, Fabric API 0.116.17+1.21.1 | 21 |

Each version has its own Gradle projects, named after it: `:common:1.21.1`, `:neoforge:1.21.1`, `:fabric:1.21.1`.
Their settings are in `stonecutter.properties.toml`. The engine, Rhino and the previewer are built once and shared.
The jars are `vellum-<loader>-<minecraft>-<version>.jar` (`vellum-neoforge-1.21.1-0.3.0.jar`) in
`<loader>/versions/<minecraft>/build/libs/`, and `./gradlew publishToMavenLocal` publishes
`dev.vellum:vellum-{common,neoforge,fabric}-<minecraft>` for both versions at the same Vellum version.

The sources are written for 26.3. Where 1.21.1 differs, Stonecutter comments (`//? if >=26 {`) pick the code, and
renames such as `Identifier`/`ResourceLocation` are replacements in `stonecutter.gradle`. Code that differs a lot
lives in one file per version under `common/versions/<minecraft>/src`: `McGui` (drawing), `McClient` (screens and
input), `KeyNames`, `Scene` and `EntityPortrait` (3D), `GameTests`. Keep committed sources on 26.3: if you switch
Stonecutter's active version to work on 1.21.1, switch back before committing.

Everything works on 1.21.1, with these differences:

- `<entity>` and `<model>` can't fade: under half opacity they aren't drawn, as items aren't on either version. `-mc-tint` works.
- `VellumEntities.registerPortraitState` doesn't exist, since 1.21.1 has no entity render states. Entity `components`
  are ignored, and `variant` and `color` are set through the entity's NBT.
- The CSS `cursor` shows over screens only, not over HUD overlays.
- Fabric draws HUD overlays after the whole HUD, since 1.21.1 has no HUD layers.
- Key events are GLFW's: `DocumentDriver.onKey` handlers get `dev.vellum.mod.client.input.KeyEvent` with GLFW key codes.
- The dev tour (`-Ptour`) is 26.3 only.
