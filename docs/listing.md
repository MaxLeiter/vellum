# Vellum on Modrinth and CurseForge

The text for both project pages, ready to paste. The images and the tour video are in `media/listing/`, which isn't
in the repo. Releases are uploaded by `.github/workflows/release.yml`; this file is only for the project pages.

## Name

Vellum

Slug: `vellum` (both sites)

## Summary (under 100 characters)

Pick one:

1. `Write Minecraft GUIs in HTML, CSS and JavaScript. Flexbox, grid, animations and real slots.` (91)
2. `A small web engine for Minecraft GUIs: HTML, CSS and JS with real inventory slots.` (82)
3. `Minecraft screens, HUDs and inventories written as web pages.` (61)

## Categories and settings

Modrinth
- Project type: Mod
- Categories: Library (primary), Utility
- Loaders: NeoForge, Fabric
- Game versions: 26.3, 1.21.1
- Client side: Required
- Server side: Optional (only needed for pages a server opens)
- License: MIT
- Source: https://github.com/MaxLeiter/vellum
- Issues: https://github.com/MaxLeiter/vellum/issues
- Wiki: https://github.com/MaxLeiter/vellum/blob/main/docs/index.md

CurseForge
- Class: Mods
- Category: API and Library (main), also Utility & QoL if you want a second one
- Environment: Client and Server
- Same license, source and issues links

Release channel: Beta while Vellum is 0.x.

## Description

Paste this as the long description on both sites. It's Markdown; CurseForge takes Markdown if you pick it in the editor.

---

Vellum is a small web engine inside Minecraft. You write a screen, inventory, HUD or map as an HTML page, and Vellum lays it out, animates it, runs its scripts and draws it with the game's own GUI renderer.

You don't need to install this on its own. It's a library: mods that use it list it as a dependency, and launchers install it with them.

### What you get

- Most of CSS: block, inline, flexbox, grid and positioning, plus transitions and `@keyframes`. It's a 90-10 solution, not a browser.
- The game's own look by default. Buttons, text fields, panels, slots and tooltips use vanilla sprites and the game font, so an unstyled page already fits in.
- Real inventories. Put `<slot index="0">` wherever you want a slot and vanilla's clicking, dragging, shift-clicking and tooltips keep working. Recipe viewers like JEI see the page's real bounds.
- Minecraft elements: `<item>`, `<slot>`, live 3D `<entity>` and `<model>`, sprites, translations and player heads.
- JavaScript with Vue-style templates (`v-for`, `v-if`, `v-model`, `@click`). Classes, async/await and the rest of the modern syntax you'd reach for work.
- HUD overlays, narration support, and a standalone previewer with hot reload, so you can build a page without launching the game.

### For mod developers

```html
<div class="mc-panel chest">
  <div class="mc-label">{{ title }}</div>
  <div class="grid"><slot v-for="i in 27" :index="i - 1"></slot></div>
</div>
<style>
  .grid { display: grid; grid-template-columns: repeat(9, 18px); }
</style>
```

```java
VellumScreens.registerContainer(MyMenus.CHEST, "mymod:vellum/chest.html");
VellumScreens.open("mymod:vellum/journal.html", data).driver()
        .onMessage("save", value -> Journal.save(value.getAsJsonObject()));
```

Depend on it from [maven.maxleiter.com](https://maven.maxleiter.com) (`dev.vellum:vellum-neoforge-26.3:0.4.1`, and the same for Fabric and 1.21.1), and make it a required dependency of your mod rather than bundling it.

Docs: [API](https://github.com/MaxLeiter/vellum/blob/main/docs/index.md), [scripting](https://github.com/MaxLeiter/vellum/blob/main/docs/SCRIPTING.md), [porting an existing screen](https://github.com/MaxLeiter/vellum/blob/main/docs/MIGRATING.md).

### Servers

A server mod can open a page on a player's client, push data to it and get messages back. Since a server might be hostile, scripts run in a sandbox with no Java access, no network and no file access, and every page has caps on CPU, memory and size. Players can always close a page with Shift+Esc, and `config/vellum.properties` lets them block server pages entirely.

### Versions

Minecraft 26.3 and 1.21.1, on NeoForge and Fabric (Fabric needs Fabric API). 3D models can't fade on 1.21.1, and a couple of entity hooks are 26.3 only.

LLMs were used extensively in the development of Vellum.

Vellum is MIT licensed. It bundles Mozilla Rhino (MPL-2.0), built from upstream Rhino 1.9.1 with a few patches. The patches are in the repo under [`rhino/patches/`](https://github.com/MaxLeiter/vellum/tree/main/rhino/patches), and Rhino's license and notices ship in the jar under `META-INF/licenses/rhino/`.

---

## Gallery captions

Upload in this order. The first image is the featured one on Modrinth. 06 and 07 are the weakest; drop them if you want a tighter gallery.

| File | Title | Caption |
|---|---|---|
| gallery-01-title-screen.png | Title screen | The main menu, rebuilt as a web page with an animated parallax dusk. |
| gallery-03-turntable.png | Turntable | Blocks, items and mobs in 3D, turned by CSS. The portraits follow your cursor. |
| gallery-02-mobdex.png | Mobdex | A Pokédex for mobs: search, filters, stats and a live 3D mob. |
| gallery-04-trader.png | Trader's market | Item tooltips with the price and rarity added under them, and a satchel to buy from. |
| gallery-05-journal-1.21.1.png | Quest journal (1.21.1) | A two-page quest book with checkboxes, progress and rewards. |
| gallery-08-end-card.png | HTML, CSS and JS | Every screen in this gallery is a web page. |
| gallery-06-hud-1.21.1.png | HUD kit (1.21.1) | Quest tracker, compass, boss bar, toasts and effects, drawn over the game. |
| gallery-07-chest-1.21.1.png | Vanilla chest (1.21.1) | A chest screen in about 30 lines of HTML. The slots are real. |

## Video

`vellum-tour.mp4` (48 s, 1080p60, with sound). Modrinth takes video links in the description rather than uploads, so put it on YouTube and embed or link it at the top of the description. CurseForge can link a YouTube video in the project's media.

## Versions and changelogs

The release workflow uploads each version, one file per loader and Minecraft version, named like
`Vellum 0.4.0 (NeoForge 1.21.1)`. Each one's changelog is that version's section of `CHANGELOG.md`.
