# Vellum

[CurseForge](https://www.curseforge.com/minecraft/mc-mods/vellum-gui) | [Modrinth](https://modrinth.com/mod/vellum-gui) | [Docs](docs/index.md)

Vellum is a small web engine inside Minecraft. Screens, inventories, HUD and map can be expressed as an HTML page, and Vellum lays it out, animates it, runs its scripts and draws it with the normal GUI renderer. It runs on Minecraft 26.3 and 1.21.1, on NeoForge and Fabric.

You don't need to install this on its own. It's a library: mods that use it list it as a dependency, and launchers install it with them.

The inspiration for Vellum was two-fold:

- I'm a web developer and my Minecraft GUIs have always looked bad
- LLMs are better at web development than Minecraft GUI development

Vellum helps in both these cases.

<table>
  <tr>
    <td width="50%"><img src="docs/images/title-screen.jpg" alt="A title screen"></td>
    <td width="50%"><img src="docs/images/turntable.jpg" alt="A 3D entity turntable"></td>
  </tr>
  <tr>
    <td width="50%"><img src="docs/images/mobdex.jpg" alt="A mobdex"></td>
    <td width="50%"><img src="docs/images/trader.jpg" alt="A trader's shop"></td>
  </tr>
</table>

### What you get

- Most of CSS: block, inline, flexbox, grid and positioning, transitions and `@keyframes`. Its goal is to be a 90-10 solution compared to a complete browser.
- The game's own look by default. Buttons, text fields, panels, slots and tooltips use vanilla sprites and the game font, so an unstyled page already fits in.
- Put `<slot index="0">` wherever you want a slot and vanilla mechanics will Just Work(tm): clicking, dragging, shift-clicking and tooltips.
- Minecraft-specific elements: `<item>`, `<slot>`, 3D `<entity>` and `<model>`, sprites, translations and player heads.
- JavaScript with Vue-style templates (`v-for`, `v-if`, `v-model`, `@click`).
- HUD overlays, narration/accessibility support, and a standalone previewer with hot reload, so you (or your agent) can build a page without launching the game.
- Pure Java with the JS engine relocated, so it won't clash with anything in a big modpack.
- Speed. A few hundred elements restyle and relayout in well under a millisecond.

Where Vellum behaves differently from a browser, the docs say so.

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

### For mod developers

Vellum is in development. Other mods (Chronicle, claudemons) use it as a library. It's on [maven.maxleiter.com](https://maven.maxleiter.com):

```groovy
repositories { maven { url = "https://maven.maxleiter.com"; content { includeGroup("dev.vellum") } } }
dependencies { implementation("dev.vellum:vellum-neoforge-26.3:0.4.1") }   // or vellum-fabric-26.3, and the 1.21.1 ones
```

Make it a required dependency of your mod, here and on Modrinth and CurseForge, rather than bundling it. [The docs](docs/index.md) cover the Java side, [`docs/SCRIPTING.md`](docs/SCRIPTING.md) the JavaScript dialect and templates, and [`docs/MIGRATING.md`](docs/MIGRATING.md) porting an existing screen.

### Servers

A server mod can open a page on a player's client, push data to it and get messages back. Since a server might be hostile, scripts run in a Rhino sandbox with no Java access, no network access, no file access, and sensible resource caps. Players can always close a page with Shift+Esc, and `config/vellum.properties` lets them block server pages entirely.

## Examples

Full pages, opened in game with `/vellum showcase <name>`:

- [Title screen](common/src/main/resources/assets/vellum/vellum/showcase/title.html): the main menu with a parallax landscape
- [Turntable](common/src/main/resources/assets/vellum/vellum/showcase/models.html): blocks, items and mobs in 3D, turned by CSS
- [Mobdex](common/src/main/resources/assets/vellum/vellum/showcase/mobdex.html): a Pokédex for mobs, with live entities ([Java side](common/src/main/java/dev/vellum/mod/client/showcase/Mobdex.java))
- [Trader's market](common/src/main/resources/assets/vellum/vellum/showcase/shop.html): a shop with a satchel and checkout
- [Journal](common/src/main/resources/assets/vellum/vellum/showcase/journal.html): a quest book
- [HUD kit](common/src/main/resources/assets/vellum/vellum/showcase/hud.html): quest tracker, compass, boss bar and toasts
- [Console](common/src/main/resources/assets/vellum/vellum/showcase/console.html): a chat console for a robot

Smaller demos of one feature each, opened with `/vellum demo <name>`: [settings](common/src/main/resources/assets/vellum/vellum/demo/settings.html), [layout](common/src/main/resources/assets/vellum/vellum/demo/layout.html), [animation](common/src/main/resources/assets/vellum/vellum/demo/animation.html), [templates](common/src/main/resources/assets/vellum/vellum/demo/templates.html), [map](common/src/main/resources/assets/vellum/vellum/demo/map.html), [HUD overlay](common/src/main/resources/assets/vellum/vellum/demo/hud.html), [toast over chat](common/src/main/resources/assets/vellum/vellum/demo/toast.html) and a [vanilla chest](common/src/main/resources/assets/vellum/vellum/demo/chest.html) built from `<slot>`s ([server side](common/src/main/java/dev/vellum/mod/server/VellumDemos.java)).

`/vellum open <resource id>` opens any page and `/vellum reload` reloads them. In a dev client, saving a page under `src/main/resources` reloads it in the open screen.

## Previewer

You can also build pages without launching Minecraft. The previewer renders a page in a Swing window with the engine and the game's real font and sprites (read from your Minecraft jar), reloads when you save, and has an F12 inspector that outlines the hovered element's box model.

```bash
./gradlew :preview:run --args="path/to/page.html --scale 3 --size 427x240"
./gradlew :preview:run --args="page.html --snapshot out.png --frames 30"   # headless PNG
```

Flags, keys and how it finds the jar are in [`preview/README.md`](preview/README.md).

## Modules

| Module | What |
|---|---|
| `engine/` | No Minecraft dependencies. Contains the DOM, HTML and CSS parsers, cascade, layout, animation, painting to an abstract canvas, input and forms, sandboxed scripting |
| `rhino/` | Mozilla Rhino 1.9.1 with Vellum's patches, relocated to `dev.vellum.shadow.rhino` |
| `common/` | Vanilla-only Minecraft code: the canvas over Minecraft's GUI drawing, screens, container screens, HUD overlays, networking, the public API, demos |
| `neoforge/`, `fabric/` | Loader entrypoints |
| `<loader>/versions/<minecraft>/` | Where Stonecutter builds each Minecraft version, and the code of one version only (see Versions) |
| `preview/` | The standalone previewer |

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

Minecraft 26.3 and 1.21.1, on NeoForge and Fabric (Fabric needs Fabric API). A few things differ on 1.21.1; [docs/versions.md](docs/versions.md) lists them and how the two versions are built.

## Releases

[`CHANGELOG.md`](CHANGELOG.md) has what changed in each version. To release one:

1. Set `version` in `gradle.properties` and add a `## <version>` section to `CHANGELOG.md`.
2. Commit, then tag and push: `git tag v0.4.0 && git push origin v0.4.0`.

The tag runs `.github/workflows/release.yml`. It builds, makes a GitHub release, publishes the Maven artifacts to
[maven.maxleiter.com](https://github.com/MaxLeiter/maven), and uploads a NeoForge and a Fabric jar per Minecraft
version to Modrinth and CurseForge. The secrets are `MAVEN_DEPLOY_KEY`, `MODRINTH_TOKEN` and `CURSEFORGE_TOKEN`, and
the store project ids are `modrinth_id` and `curseforge_id` in `gradle.properties`. `release_type` there picks
release, beta or alpha. Running the workflow by hand is a dry run, and so is `./gradlew publishMods -PpublishDryRun`
locally: both show what would be uploaded and upload nothing.

## License

LLMs were used in the development of Vellum.

Vellum is MIT (`LICENSE`). The jars bundle Mozilla Rhino, which is MPL-2.0: it is built from the upstream commit
pinned in `gradle.properties` (Rhino 1.9.1) with the patches in [`rhino/patches`](rhino/patches), and its license,
notices and a note on where that source is ship in each jar under `META-INF/licenses/rhino/`.
