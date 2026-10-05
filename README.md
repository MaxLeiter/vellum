# Vellum

*A small web engine for Minecraft GUIs (26.3, NeoForge and Fabric).*

Write screens, inventories, HUDs and maps in HTML, CSS and a little JavaScript. Vellum lays them out with real CSS (block, inline, flexbox, grid, positioning), animates them (transitions, `@keyframes`), runs their scripts in a sandbox, and paints them with Minecraft's own GUI renderer. Text uses the game's font, `<item>`s are real items, and `<slot>`s are real container slots.

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

Status: in development. See [DESIGN.md](DESIGN.md) for the architecture and the supported HTML/CSS/JS, and [DECISIONS.md](DECISIONS.md) for why it is built this way.

## Layout
| Module | What |
|---|---|
| `engine/` | Pure-Java engine (no Minecraft dependencies, Java 21): DOM, HTML and CSS parsers, cascade, layout, animation, paint to an abstract canvas, input and forms, sandboxed scripting |
| `rhino/` | Mozilla Rhino 1.9.1, relocated to `dev.vellum.shadow.rhino` |
| `common/` | Minecraft integration: the canvas over `GuiGraphicsExtractor`, screens, container screens, HUD overlays, networking, demos |
| `neoforge/`, `fabric/` | Loader entrypoints |
| `preview/` | Standalone previewer: renders a page with Minecraft's real font and sprites, reloads on save |

## Building
JDK 25+; Gradle provisions the toolchain.
```bash
./gradlew :engine:test        # engine tests
./gradlew build               # everything, including Fabric GameTests
./gradlew :neoforge:runClient # dev client; /vellum demo opens the gallery
```
