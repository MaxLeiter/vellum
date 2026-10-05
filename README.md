# Vellum

*Minecraft GUIs in HTML, CSS and JavaScript.* A Minecraft 26.3 mod (NeoForge and Fabric) with a small web engine inside. You write a screen, inventory, HUD or map as a web page, and Vellum lays it out, animates it, runs its scripts and paints it with the regular GUI renderer.

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
| `rhino/` | Mozilla Rhino 1.9.1, relocated to `dev.vellum.shadow.rhino` |
| `common/` | Vanilla-only Minecraft code: the canvas over `GuiGraphicsExtractor`, screens, container screens, HUD overlays, networking, the public API, demos |
| `neoforge/`, `fabric/` | Loader entrypoints |
| `preview/` | The standalone previewer |

[`DESIGN.md`](DESIGN.md) covers the architecture and which HTML, CSS and JS work. [`DECISIONS.md`](DECISIONS.md) has the reasons behind the choices. For mod authors, [`docs/API.md`](docs/API.md) is the Java side, [`docs/SCRIPTING.md`](docs/SCRIPTING.md) the JavaScript dialect and templates, and [`docs/MIGRATING.md`](docs/MIGRATING.md) a guide to porting hand-drawn screens.

## Building
You need JDK 25 or newer; Gradle provisions the toolchain.

```bash
./gradlew :engine:test        # engine tests
./gradlew build               # everything, including Fabric's headless GameTests
./gradlew :neoforge:runClient # dev client
./gradlew :preview:installDist   # previewer, at preview/build/install/preview/bin/preview
```
