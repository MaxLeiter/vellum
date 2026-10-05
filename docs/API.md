# Vellum for mod authors

Vellum shows HTML, CSS and JavaScript pages as Minecraft screens, inventories and HUD overlays. This page covers the
Java side: depending on Vellum, opening pages from the client or the server, exchanging data and messages, container
screens and HUD overlays, and the Minecraft elements pages can use. The engine's HTML and CSS support is described in
`DESIGN.md`.

The API is loader-independent: the same calls work on NeoForge and Fabric.

## Depending on Vellum

Gradle (until Vellum is on a public maven, publish it locally with `./gradlew publishToMavenLocal` from a Vellum
checkout):

```groovy
repositories { mavenLocal() }

// common/ (compiles against vanilla): the API, plus the engine types it exposes
dependencies { compileOnly("dev.vellum:vellum-common-26.3:0.1.0") }

// neoforge/ and fabric/: the loader jar, so dev runs load Vellum as a mod (it bundles the engine and Rhino)
dependencies { implementation("dev.vellum:vellum-neoforge-26.3:0.1.0") }   // or vellum-fabric-26.3
```

Declare the dependency in your mod metadata: `[[dependencies.<modid>]] modId="vellum" type="optional"` (or
`"required"`) in `neoforge.mods.toml`, and `"suggests": {"vellum": "*"}` (or `"depends"`) in `fabric.mod.json`.
Players install Vellum like any other mod; don't nest its jar in yours.

- Treat Vellum as an **optional** dependency unless your mod is built around it. Check that it is loaded before
  touching `dev.vellum` classes:
  - NeoForge: `ModList.get().isLoaded("vellum")`
  - Fabric: `FabricLoader.getInstance().isModLoaded("vellum")`
- Put your Vellum-facing code in a separate class. It is then only class-loaded when Vellum is present, and you can
  fall back to a vanilla screen otherwise.
- **Threads.** Server calls run on the server thread; client calls run on the render thread.
- **Where pages live.** A page URL is a resource id: `mymod:vellum/shop.html` is
  `assets/mymod/vellum/shop.html` in your jar (or in a resource pack, which can restyle your UI). Stylesheets,
  scripts and images referenced from a page resolve relative to it, so `<link rel="stylesheet" href="shop.css">`
  loads `assets/mymod/vellum/shop.css`.

Vellum's payloads are optional on both loaders: a server with Vellum accepts clients without it, and the other way
round. `VellumServer.open` then returns a session that is already closed.

## Opening a page on the client

```java
VellumScreen screen = VellumScreens.open("mymod:vellum/settings.html");
screen.driver().onMessage("save", value -> MyConfig.save(value.getAsJsonObject()));

// With initial data (the page's vellum.data):
VellumScreens.open("mymod:vellum/journal.html", journalJson);

// Inline HTML, e.g. generated at runtime:
VellumScreens.openInline("<h1>Hello</h1><p>{{ name }}</p>", data);
```

- `vellum.close()` (or Escape, unless the page handles it) closes the screen.
- `vellum.send(channel, value)` in the page calls every `onMessage(channel, ...)` handler with the value as a
  `JsonElement`.
- `screen.driver().push(json)` replaces `vellum.data`; template bindings update and `vellum.on('data', fn)`
  listeners run.
- `screen.driver().onClose(() -> ...)` runs once when the page closes for good: the screen is closed or replaced by
  another screen. Not when the page navigates or reloads, or while a link confirmation is open over it. The page's
  own `pagehide` and `unload` listeners run just before, with scripts still alive, so a last `vellum.send` from
  them reaches your `onMessage` handlers first.
- `<a href="other.html">` loads another page in the same screen; `https://` links ask for confirmation first.

```java
VellumScreens.open("mymod:vellum/notes.html", notesJson).driver()
        .onMessage("save", value -> Notes.save(value.getAsJsonObject()))   // the page saves in its unload listener
        .onClose(Notes::flush);
```

## Opening a page from the server

```java
VellumSession session = VellumServer.open(player, "mymod:vellum/shop.html", stockJson)
        .onMessage("buy", (p, value) -> Shop.buy(p, value.getAsString()))
        .onClose(() -> Shop.forget(player));

session.push(updatedStockJson);   // the page's vellum.data changes; bindings re-render
session.close();                  // closes the player's screen
```

- The page must exist on the client (your mod is installed there, or a server resource pack ships it). A server-only
  mod can send the page itself: `VellumServer.openInline(player, html, data)` with inline `<style>` and `<script>`.
- Messages from the page (`vellum.send(channel, value)`) arrive on the server thread with the sending player. They
  are rate-limited (a burst of 40, then 20 per second); malformed JSON and messages from other players are dropped.
- `onClose` runs once, when the player closes the screen, opens another session, leaves, or you call `close()`.
- Limits: inline pages 200,000 characters, data 100,000 characters (as JSON), messages 8,192 characters. `open` and
  `push` throw `IllegalArgumentException` above them.
- Pages sent by servers run sandboxed: no Java access, no network, no files, a CPU budget per script call.

## Container screens

Any mod can give its own menu type a Vellum screen. The page places slots with `<slot index="n">`: menu slot `n`
goes where the element is painted, every frame, so slots follow scrolling, transforms and animations. Vanilla still
draws items, highlights, tooltips and the carried stack, and handles clicks, drags and shift-clicks. Slots that are
not painted (no element, hidden, under half opacity, or scrolled out of their container) are hidden.

```java
// Client setup (both loaders; before the game finishes loading):
VellumScreens.registerContainer(MyMenus.FORGE, "mymod:vellum/forge.html");   // a MenuType or a registry holder
```

```html
<section class="grid">
  <slot v-for="i in range(0, 27)" :index="i"></slot>
</section>
```

The page's `vellum.data` is `{title, inventory, slots}`, where `slots[n]` is `{id, count, name}` for menu slot `n`.
It updates when the menu's contents change (and only then), so a page can show totals or highlight search results. Clicking
outside the page's content (where only `<html>`/`<body>` is under the pointer) drops the carried stack, as clicking
outside a vanilla container does.

To give the page data of your own (the bot's entity id, its tier), pass a function of the menu. Its fields are
added to `vellum.data` next to `title`, `inventory` and `slots` (a field with one of those names replaces it):

```java
VellumScreens.registerContainer(MyMenus.BOT, "mymod:vellum/bot.html", menu -> {
    JsonObject data = new JsonObject();
    data.addProperty("entity", menu.botId());
    data.addProperty("tier", menu.tier());
    return data;   // or null for none
});
```

The function runs when the screen opens and every client tick after, and the page gets new data whenever its result
or the slots changed, so it can read from the menu, synced `ContainerData`, or client state your packets update.
(`driver().push` would replace all of `vellum.data`, slots included, until the next change; use the function
instead.)

Try it: `/vellum demo chest` opens a chest whose screen is `assets/vellum/vellum/demo/inventory.html`.

## HUD overlays

```java
// Client setup:
VellumHud.register(id("tracker"), "mymod:vellum/tracker.html");

VellumHud.show(id("tracker"));
VellumHud.push(id("tracker"), questJson);   // vellum.data
VellumHud.hide(id("tracker"));
```

Overlays are pages sized to the GUI-scaled window, drawn above the title layer and hidden with the HUD (F1). A page
can hide itself with `vellum.close()`, for example when its animation ends. Data pushed while the overlay is hidden
is kept for the next `show`. `show` returns the page's driver, for `onMessage` and `onClose` handlers; they last
until the overlay is hidden (showing it again makes a new driver).

By default overlays take no input. Register one with `VellumHud.Input.WHEN_CURSOR_FREE` and the player can use it
with the mouse whenever a screen frees the cursor (chat, an inventory, a Vellum screen, any screen):

```java
VellumHud.register(id("ask"), "mymod:vellum/ask.html", VellumHud.Input.WHEN_CURSOR_FREE);

VellumHud.show(id("ask"))
        .onMessage("answer", value -> Approvals.answer(value.getAsString()));
```
```html
<div class="toast">
  Rivet wants to take 12 cobblestone.
  <button onclick="vellum.send('answer', 'once'); vellum.close()">Allow</button>
  <button onclick="vellum.send('answer', 'deny'); vellum.close()">Deny</button>
</div>
```

- While a screen is open the overlay is drawn above it, and pointer input goes to the overlay first: hover (and
  `title` tooltips), clicks and the wheel.
- Input falls through to the screen wherever only the page's background is under the pointer (`<html>`, `<body>`,
  whatever their styles) or nothing is. Content that should let clicks through can say `pointer-events: none`.
  A wheel the page does not use (nothing scrolls, no listener cancels it) reaches the screen too.
- A press that went to the overlay keeps its release and drags. When the screen closes, hover ends (`mouseleave`
  fires) and the overlay is drawn in the HUD again, without the pointer.
- Keyboard input stays with the screen: typing in chat still types in chat.

Try it: `/vellum demo toast`, then press T and click a button.

## Minecraft elements

These elements are drawn by Minecraft. They lay out like images: an intrinsic size, scaled by CSS `width`/`height`
and `object-fit`.

| Element | Attributes | Notes |
|---|---|---|
| `<item>` | `id`, `count`, `components`, `tooltip` | An item stack with its count and durability bar; 16×16 by default, scaled to the box. `components` is SNBT, as in `/give`: `components='{"minecraft:enchantments":{"minecraft:sharpness":5}}'`. With `tooltip`, hovering shows the vanilla tooltip. Items can't be faded: under 50% opacity they are hidden. |
| `<slot>` | `index` | A container slot (container screens only), 18×18. The look comes from CSS; vanilla draws the item. |
| `<entity>` | `type`, `player`, `id`, `follow-mouse`, `rotate`, `scale` | A live entity: `type="minecraft:pig"` (a client-side copy), `player` (you), or `id` (a world entity). `follow-mouse` turns its head toward the pointer; `rotate` turns it by degrees; `scale` multiplies the fitted size. 48×48 by default. |
| `<player-head>` | `name`, `uuid` | A player's face with the hat layer. No attributes: your own face. 16×16 by default. |
| `<sprite>` | `src` | A GUI-atlas sprite such as `minecraft:widget/button`, at its natural size. Nine-slice and tiled sprites keep their borders when resized. |
| `<img>` | `src` | A texture (`ns:textures/....png`, or relative to the page), a sprite (`sprite:ns:path`) or a canvas (`canvas:<id>`, the `<canvas>` with that id). The natural size comes from the PNG, sprite or canvas. `sprite:` and `canvas:` URLs work in CSS `url()` too. |
| `<canvas>` | `width`, `height` | A pixel surface for scripts, 300×150 by default (at most 2048 a side). `getContext('2d')` supports `fillStyle`/`strokeStyle` (CSS colours), `lineWidth`, `globalAlpha`, `save`/`restore`, `fillRect`, `strokeRect`, `clearRect`, `getImageData`/`putImageData`/`createImageData` and `drawImage` of another canvas; coordinates are whole pixels (no antialiasing), and there is no text, paths or transforms. Show it elsewhere with `canvas:<id>`. |
| `<mc-text>` | `key` + `args`, or `json` | Minecraft text as ordinary inline text: a translation (`key="block.minecraft.stone"`, comma-separated `args`) or a chat component (`json='{"text":"Gold","color":"gold","bold":true}'`). Styled parts become spans. Expanded when the element is added to the page and whenever these attributes change, so it works in templates. |

Any element can have a `title` (plain text; a newline breaks the line) or a `title-json` (a chat component, for
coloured text): after half a second of hover the vanilla tooltip shows at the pointer, wrapped like a widget
tooltip. The nearest one from the hovered element up wins, and an `<item tooltip>` or a container slot's item
tooltip wins over it. See SCRIPTING.md.

CSS extras for Minecraft: `font-family: minecraft:default | minecraft:uniform | minecraft:alt |
minecraft:illageralt | <any font id>` (`monospace` is uniform), `text-shadow: minecraft` (the game's own shadow),
`-mc-tint` (multiplies images and sprites; items cannot be tinted), `sprite(ns:path)` backgrounds, and the chat
colours as names (`mc-gold`, `mc-gray`...). Lengths are GUI pixels: `1px` scales with the GUI scale, `1dp` is one
device pixel.

## Commands and development

| Command | Side | |
|---|---|---|
| `/vellum open <url>` | client | Opens any page, e.g. `/vellum open mymod:vellum/shop.html`. |
| `/vellum demo [name]` | client | The demo gallery, or one demo: `settings`, `layout`, `animation`, `templates`, `map`, `hud` (toggles the HUD overlay), `toast` (toggles an interactive HUD overlay: press T and click it). |
| `/vellum demo chest` | server | The inventory demo on a real chest menu (needs cheats). |
| `/vellum demo live` | server | The templates demo as a server session with live data. |
| `/vellum reload` | client | Reloads every open page. Resource reloads (F3+T) do too. |
| `/vellum canvastest` | client | Draws every Minecraft canvas primitive without the engine, to check the renderer. |

- When a page fails to load or the engine throws, the screen shows the error and its stack instead of crashing;
  `/vellum reload` tries again.
- In a development environment Vellum reads pages from `common/src/main/resources/assets` directly and reloads open
  pages when a file they were read from is saved.
- `config/vellum-client.properties`: `reducedMotion=true` makes pages match `@media (prefers-reduced-motion: reduce)`.

## Stability

The API is `dev.vellum.mod.server.VellumServer`, `VellumSession`, and `dev.vellum.mod.client.VellumScreens`,
`VellumScreen`, `VellumContainerScreen`, `VellumHud` and `DocumentDriver`'s public methods. Other classes are
internal. Vellum is at 0.x: expect changes, which will be listed in the changelog.
