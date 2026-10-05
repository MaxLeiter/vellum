# Vellum for mod authors

Vellum shows HTML, CSS and JavaScript pages as Minecraft screens, inventories and HUD overlays. This page covers the
Java side: depending on Vellum, opening pages from the client or the server, exchanging data and messages, container
screens and HUD overlays, the Minecraft elements pages can use, and how your entities are drawn in them. The
engine's HTML and CSS support is described in `DESIGN.md`.

The API is loader-independent: the same calls work on NeoForge and Fabric.

## Depending on Vellum

Gradle (until Vellum is on a public maven, publish it locally with `./gradlew publishToMavenLocal` from a Vellum
checkout):

```groovy
repositories { mavenLocal() }

// common/ (compiles against vanilla): the API, plus the engine types it exposes
dependencies { compileOnly("dev.vellum:vellum-common-26.3:0.2.0") }

// neoforge/ and fabric/: the loader jar, so dev runs load Vellum as a mod (it bundles the engine and Rhino)
dependencies { implementation("dev.vellum:vellum-neoforge-26.3:0.2.0") }   // or vellum-fabric-26.3
```

Declare the dependency in your mod metadata: `[[dependencies.<modid>]] modId="vellum" type="optional"` (or
`"required"`) in `neoforge.mods.toml`, and `"suggests": {"vellum": "*"}` (or `"depends"`) in `fabric.mod.json`.
Players install Vellum like any other mod; don't nest its jar in yours.

- Treat Vellum as an optional dependency unless your mod is built around it. Check that it is loaded before
  touching `dev.vellum` classes:
  - NeoForge: `ModList.get().isLoaded("vellum")`
  - Fabric: `FabricLoader.getInstance().isModLoaded("vellum")`
- Put your Vellum-facing code in a separate class. It is then only class-loaded when Vellum is present, and you can
  fall back to a vanilla screen otherwise.
- Server calls run on the server thread; client calls run on the render thread.
- A page URL is a resource id: `mymod:vellum/shop.html` is
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
- `screen.driver().merge(jsonObject)` sets only the top-level fields it has and keeps the rest of `vellum.data`.
- `VellumScreens.onPageLoad(url, driver -> ...)` runs whenever that page loads, however it was reached (opened, a link,
  a reload), before its scripts run: give it live data with `driver.push(json)`, or `driver.merge(fields)` to keep
  what the opener passed, and `onMessage` to handle its messages. `VellumScreens.pages(url)` returns the drivers
  showing that page now, to push updates to.

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

Recipe viewers (JEI, REI, EMI) lay out around a container screen's GUI area. For a Vellum container that area is
the page's content, measured every frame as painted: `<body>`'s in-flow child elements (a centred panel), or, when the
page marks any, the elements with a `data-vellum-bounds` attribute. Mark them when the panel is not a direct child
of `<body>`, when positioned parts (a side tab, a floating inventory) belong to it, or when a full-screen wrapper
would claim the whole screen:

```html
<body>
  <div class="backdrop">            <!-- covers the screen: not the GUI -->
    <main class="panel" data-vellum-bounds>...</main>
    <aside class="tabs" data-vellum-bounds>...</aside>
  </div>
</body>
```

The area is the union of the marked border boxes. A page with neither has the whole screen as its GUI area, so
recipe viewers keep clear of it.

A container page's `vellum.data` is `{title, inventory, slots}`, where `slots[n]` is `{id, count, name}` for menu slot `n`.
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

By default overlays take no input. Say over which screens the player can use one with the mouse, and it is drawn
above those screens and gets their pointer input first:

```java
VellumHud.register(id("ask"), "mymod:vellum/ask.html", VellumHud.Input.WHEN_CHAT_OPEN);

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

| Registration | Interactive over |
|---|---|
| `register(id, url)` or `Input.NONE` | nothing |
| `Input.WHEN_CHAT_OPEN` | chat (`ChatScreen`, also chat in bed) |
| `Input.WHEN_CURSOR_FREE` | every screen, full-screen ones (inventories, menus, Vellum screens) included |
| `register(id, url, Predicate<Screen> interactiveOver)` | the screens the predicate accepts, e.g. `screen -> screen instanceof ChatScreen \|\| screen instanceof InventoryScreen` |

- Over a screen it is interactive over, the overlay is drawn above the screen, and pointer input goes to the overlay
  first: hover (and `title` tooltips), clicks and the wheel.
- Input falls through to the screen wherever only the page's background is under the pointer (`<html>`, `<body>`,
  whatever their styles) or nothing is. Content that should let clicks through can say `pointer-events: none`.
  A wheel the page does not use (nothing scrolls, no listener cancels it) reaches the screen too.
- A press that went to the overlay keeps its release and drags.
- With no screen open, or under any other screen, the overlay is drawn in the HUD layer as a plain overlay: under
  the screen, without the pointer. When the screen it was over closes (or another replaces it), hover ends
  (`mouseleave` fires).
- Keyboard input stays with the screen: typing in chat still types in chat.
- The predicate is asked every frame and for every pointer event, with the open screen; keep it cheap.

Try it: `/vellum demo toast`, then press T and click a button.

## Minecraft elements

These elements are drawn by Minecraft. They lay out like images: an intrinsic size, scaled by CSS `width`/`height`
and `object-fit`, and placed in their box by `object-position`.

| Element | Attributes | Notes |
|---|---|---|
| `<item>` | `id`, `count`, `components`, `tooltip` | An item stack with its count and durability bar; 16×16 by default, scaled to the box. `components` is SNBT, as in `/give`: `components='{"minecraft:enchantments":{"minecraft:sharpness":5}}'`. With `tooltip`, hovering shows the vanilla tooltip. Items can't be faded: under 50% opacity they are hidden. |
| `<slot>` | `index` | A container slot (container screens only), 18×18. The look comes from CSS; vanilla draws the item. |
| `<entity>` | `type`, `player`, `id`, `rotatable`, `follow-mouse`, `walk`, `baby`, `variant`, `color`, `components`, `mainhand`, `offhand`, `head`, `chest`, `legs`, `feet`, `body`, `saddle` | A live entity: `type="minecraft:pig"` (a client-side copy), `player` (you), or `id` (a world entity). It stands on the bottom of its box, centred and fitted to the room it needs to turn, or with `-mc-entity-focus: eyes` its head and shoulders fill the box (below). CSS turns it (`-mc-yaw`, `-mc-pitch`, `-mc-model-scale`); `rotatable` lets the player drag it round; `follow-mouse` turns its head toward the pointer; `walk` (or `walk="0.4"`, a speed) swings its legs. Created entities play their idle animations and take `baby`, `variant` and `color` (`variant="minecraft:black"` on a cat, `color="pink"` on a sheep: the `<type>/variant` and `<type>/color` components), `components` (SNBT of entity components, e.g. `{"minecraft:wolf/collar":"red"}`) and items by equipment slot (`mainhand="minecraft:iron_sword"`). 32×48 by default. |
| `<model>` | `block` or `item`, `count`, `components`, `rotatable` | A block state (`block="minecraft:oak_stairs[facing=east]"`, as in `/setblock`) or an item (`item="minecraft:trident"`, with `count` and `components` as on `<item>`) in 3D, centred in its box, at the size an item fills its slot. At yaw and pitch 0 an item looks as in the inventory and a block is seen as most blocks are there (30° from above, turned 225°); CSS turns it as it does entities. Blocks without a model (fluids, air) draw nothing. 32×32 by default. |
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
`-mc-tint` (multiplies images, sprites, entities and models; items cannot be tinted), `sprite(ns:path)` backgrounds,
and the chat colours as names (`mc-gold`, `mc-gray`...). Lengths are GUI pixels: `1px` scales with the GUI scale,
`1dp` is one device pixel.

`<entity>` and `<model>` are turned by CSS, so transitions and animations work on them:

| Property | Value | |
|---|---|---|
| `-mc-yaw` | angle, `0` | Turns it about the vertical axis; positive turns its front to the right. |
| `-mc-pitch` | angle, `0` | Views it from above (positive) or below. |
| `-mc-model-scale` | number, `1` | Multiplies the size that fits the box. |
| `-mc-entity-focus` | `body` or `eyes`, `body` | `<entity>` only. What fills the box: the whole entity, or its head and shoulders. |

```css
.stage entity { animation: spin 8s linear infinite; }
.stage:hover entity { animation-play-state: paused; }
@keyframes spin { to { -mc-yaw: 360deg; } }

model { transition: -mc-yaw 600ms ease-out; }
model:hover { -mc-yaw: 180deg; -mc-pitch: 20deg; }
.locked entity { -mc-tint: #000; opacity: 0.6; }   /* a silhouette */
```

With `rotatable`, dragging adds to these: sideways turns it, up and down tilts the view (up to 60°), and a flick
keeps it spinning for a moment. A `mousedown` listener that calls `preventDefault()` stops the drag.

### Placing entities and models

`object-position` places every Minecraft element in its box as it places an image: one to four values, keywords,
lengths and percentages (`right 4px bottom`, `25% 75%`). Items, heads and models take a square as wide as the box's
shorter side; models times `-mc-model-scale`.

An `<entity>` is fitted one of two ways:

- `-mc-entity-focus: body` (the default) fits the whole entity, with room to turn it, and `object-position` places
  that room. Without `object-position` it stands on the bottom edge, centred (`50% 100%`), not in the middle.
- `-mc-entity-focus: eyes` crops it to its head and shoulders: the box's shorter side spans 0.7 of the entity's eye
  height (`Entity.getEyeHeight()`, so babies are framed closer), and `object-position` says where the eyes go. Unset,
  they sit at `50% 40%`. `-mc-model-scale` zooms around the eyes, and turning, tilting and `follow-mouse` keep the
  eyes where they are.

```html
<entity id="…" follow-mouse style="width:32px; height:32px; -mc-entity-focus: eyes; object-position: 50% 40%"></entity>
```

The eye point is on the entity's upright axis at its eye height. That suits mobs whose heads sit above their bodies
(players, villagers, golems, creepers). A pig's or a fox's head sits in front of that axis, so it moves out of the
frame when the mob is turned sideways. The Turntable showcase (`/vellum showcase models`) has a row of portraits that
follow the pointer.

## Entity render states

Vellum draws an entity from the render state its renderer makes. If your renderer adds things for the world (speech
bubbles, labels, effects), register a function that makes a state for screens instead:

```java
// Client setup:
VellumEntities.registerPortraitState(MyEntities.AUTOMATON.get(), AutomatonRenderer::portraitState);
```

```java
public static <T extends Entity> void registerPortraitState(EntityType<T> type,
        BiFunction<? super T, Float, ? extends @Nullable EntityRenderState> state)
```

The function gets the entity and the partial tick and runs every frame for every `<entity>` of that type that is on
screen. Return null to fall back to the renderer's state for that frame. Registering the type again replaces the
function.

Vellum then sets these fields on your state, as it does on its own:

| Field | Set to |
|---|---|
| `shadowPieces` | cleared |
| `outlineColor`, `nameTag`, `scoreText`, `leashStates`, `passengerOffset` | none |
| `lightCoords` | full bright |
| `ageInTicks` | the clock, for entities a page created (`type="…"`) |
| `bodyRot` | from `-mc-yaw`, dragging and the `follow-mouse` lean |
| `walkAnimationPos`, `walkAnimationSpeed` | from the `walk` attribute (standing still without it) |
| `scale` | 1, with `boundingBoxWidth`, `boundingBoxHeight` and `eyeHeight` divided by the old scale |
| `yRot`, `xRot` (the head) | toward the pointer with `follow-mouse`; otherwise left as your state has them |

The fields from `bodyRot` down are set on living entities' states only. Without a registered function the head looks
straight ahead unless `follow-mouse` turns it. The body fit measures the state you return, so whatever it leaves out
takes no room in the box.

## Commands and development

| Command | Side | |
|---|---|---|
| `/vellum open <url>` | client | Opens any page, e.g. `/vellum open mymod:vellum/shop.html`. |
| `/vellum demo [name]` | client | The demo gallery, or one demo: `settings`, `layout`, `animation`, `templates`, `map`, `hud` (toggles the HUD overlay), `toast` (toggles an interactive HUD overlay: press T and click it). |
| `/vellum demo chest` | server | The inventory demo on a real chest menu (needs cheats). |
| `/vellum demo live` | server | The templates demo as a server session with live data. |
| `/vellum showcase [page]` | client | The showcase gallery, or one page: `title`, `hud`, `shop`, `mobdex` (every mob in the game, with your kill statistics), `journal`, `console`, `models` (blocks, items and mobs in 3D, and head-and-shoulders portraits). |
| `/vellum reload` | client | Reloads every open page. Resource reloads (F3+T) do too. |
| `/vellum canvastest` | client | Draws every Minecraft canvas primitive without the engine, to check the renderer. |

- When a page fails to load or the engine throws, the screen shows the error and its stack instead of crashing;
  `/vellum reload` tries again.
- In a development environment Vellum reads pages from `common/src/main/resources/assets` directly and reloads open
  pages when a file they were read from is saved.
- `config/vellum-client.properties`: `reducedMotion=true` makes pages match `@media (prefers-reduced-motion: reduce)`.

## Dev automation

`dev.vellum.mod.client.VellumAutomation` drives a page from code, for dev autopilots and in-game checks. It finds
elements by CSS selector and sends input through Minecraft's own mouse and keyboard handlers, the path real input
takes, so vanilla slot highlights, clicks and shift-clicks work in container screens as well. Render thread only;
positions are GUI pixels.

```java
VellumAutomation.screen().ifPresent(page -> {      // the open VellumScreen or VellumContainerScreen
    page.click("#amount");                         // pointer onto the element, press, release
    page.type("64");                               // key down, character, key up per character
    page.key("Enter");
    page.leave();                                  // pointer off the page: no hover or tooltip left behind
});

// Then once a tick, until the page has settled:
VellumAutomation.screen().filter(VellumAutomation::settled).ifPresent(page -> {
    boolean ok = page.eval("state.amount").map(v -> v.getAsInt() == 64).orElse(false);
    // ...take the screenshot, go on to the next step
});
```

| Method | |
|---|---|
| `static Optional<VellumAutomation> screen()` | The open Vellum screen, if its page is showing. |
| `static Optional<VellumAutomation> hud(Identifier id)` | A shown HUD overlay. Its page can always be read; it takes input only while it is interactive (see below). |
| `boolean exists(String selector)` | Whether an element matches. |
| `Optional<float[]> rect(String selector)` | `{x, y, width, height}` of the first match's border box as painted (after scrolling and transforms, like `getBoundingClientRect()`). |
| `Optional<String> text(String selector)` | Its `textContent`. |
| `boolean hover(String selector)` | Moves the pointer onto the first match (see below). False when a real pointer could not reach it; the pointer stays put then. |
| `boolean click(String selector)`, `click(String selector, int button)` | Hovers it, then presses and releases `button`: 0 left, 1 middle, 2 right. False, sending nothing, when `hover` is. The pointer stays on the element. |
| `boolean wheel(String selector, double notches)` | Hovers it, then turns the wheel; positive notches scroll down. False when `hover` is. The pointer stays on the element. |
| `void leave()` | Moves the pointer outside the window, where it hovers nothing: no `:hover` style, `title` tooltip, item tooltip or slot highlight is left in the next frame, on any Vellum page or vanilla screen. Does nothing while no screen is open. |
| `boolean scrollIntoView(String selector)` | Scrolls the scroll containers the first match is in so it shows, instantly and by the least scroll. True when some of it shows afterwards. |
| `boolean key(String domKey)` | Presses and releases the key that gives a DOM key name (`"Enter"`, `"ArrowDown"`, `"a"`, `"A"`) on the current layout. False when no key does, or the page takes no input. |
| `void type(String text)` | Types text. Characters no key gives are sent as text only. |
| `Optional<JsonElement> eval(String js)` | Runs `js` in the page's script sandbox; its completion value as JSON. Empty for undefined, functions and errors (which are reported like any script error). |
| `boolean settled()` | Whether the page has stopped changing by itself (see below). |

`hover`, `click` and `wheel` aim at the centre of the part of the element that shows: its border box cut to the
window and to every scroll container (or other `overflow` clip) it is in. When none of it shows, its scroll
containers first scroll it into view, as `scrollIntoView` does. Then they hit test that point. If the topmost thing
painted there is not the element or something inside it (a positioned sibling on top, a HUD overlay over the screen,
or the element has `pointer-events: none`), they return false and send nothing, since a real click would land
elsewhere.

After `click` or `wheel` the pointer stays where it is, as a real mouse would: the element keeps `:hover`, and half a
second later its `title` tooltip shows. Call `leave()` before a screenshot.

A HUD overlay takes input while it is interactive over the open screen: a screen its `Input` or predicate accepts,
once a frame has drawn the overlay above that screen. `hover`, `click` and `wheel` then take the same path as on a
screen, and the overlay sees the pointer move at its next frame, as with a real mouse. The rest of the time they
return false: the overlay is in the HUD layer without the pointer, and a real click would reach the screen or the
game. Keys go to whatever has keyboard focus, and an overlay never has it. Through `hud(id)`, `key` and `type` reach
the screen the overlay is interactive over (the chat box, for `WHEN_CHAT_OPEN`), and do nothing when it is not
interactive.

```java
mc.gui.setScreen(new ChatScreen("", false));
// From the next tick, until it returns true:
VellumAutomation.hud(id("ask")).map(toast -> toast.click(".allow")).orElse(false);
```

`settled()` is true when nothing the page is doing will change it soon. Poll it once a tick instead of waiting a
fixed number of ticks. It is false while:

- a restyle, relayout or repaint is pending, as after input, `eval` or opening the page, until the next frame paints;
- a smooth scroll is moving, or a `scroll` event is waiting;
- a template update or a `vellum.nextTick` callback is waiting;
- a transition or a finite animation (CSS or `element.animate()`) runs, or its events are waiting;
- a drag is held, or a `rotatable` element is still spinning;
- a `title` tooltip is waiting out its delay.

Infinite animations don't count, or a title screen would never settle. Timers (`setTimeout`, `setInterval`) and
`requestAnimationFrame` callbacks don't count either: pages use them for clocks, polling and loops that never end.
When a timer changes the page, wait for that change with `text` or `eval`. A text field's blinking caret doesn't
count. A page whose timers keep starting transitions or animations may never settle (the showcase HUD's combat loop
is one), so cap the wait. A HUD overlay is not settled until its page has loaded, at its first draw; a page showing
its error panel is settled.

Vellum's own autopilot (`./gradlew :neoforge:runClient -Pautopilot`, or `:fabric:runClient`) uses all of this: it
waits for each page to settle, hovers the showcase title screen, fills in the templates demo, clicks a row scrolled
out of the Mobdex's list, and answers the demo toast through chat.

## Stability

The API is `dev.vellum.mod.server.VellumServer`, `VellumSession`, and `dev.vellum.mod.client.VellumScreens`,
`VellumScreen`, `VellumContainerScreen`, `VellumHud`, `VellumEntities`, `VellumAutomation` and `DocumentDriver`'s
public methods. Other classes are
internal. Vellum is at 0.x: expect changes, which will be listed in the changelog.
