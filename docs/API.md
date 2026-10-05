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
- `screen.pauses(true)` pauses a singleplayer world while the screen is open, like a vanilla book. It is off by
  default, because pages that talk to the server (shops, conversations) need the world running:
  `VellumScreens.open("mymod:vellum/book.html", data).pauses(true)`.
- `<a href="other.html">` loads another page in the same screen; `https://` links ask for confirmation first.
- `screen.driver().onKey(handler)` gives your mod the key presses the page leaves alone, before the screen's own keys
  (Escape, a container screen's inventory key). Use it for your own key mappings; the page doesn't know about them. A
  key the page uses never reaches the handler: one a `keydown` listener cancelled with `preventDefault()`, one a
  focused control acted on (Enter on a button), and every key but Escape while a text field has focus, so typing "j"
  in an `<input>` stays text. Return true to consume the key. Handlers run in the order they were added until one
  returns true, and stay through navigation and reloads.
- A page opened under a resting cursor shows `:hover` there from its first frame, as vanilla screens do, and its
  `title` tooltip after its delay, without the mouse moving. Every frame the page's pointer follows Minecraft's
  mouse handler, so code that sends a screen `mouseMoved` must move the mouse handler there as well, or the next
  frame moves the page's pointer back. `VellumAutomation` does both.
- `screen.driver().merge(jsonObject)` sets only the top-level fields it has and keeps the rest of `vellum.data`.
- `VellumScreens.onPageLoad(url, driver -> ...)` runs whenever that page loads, however it was reached (opened, a link,
  a reload), before its scripts run: give it live data with `driver.push(json)`, or `driver.merge(fields)` to keep
  what the opener passed, and `onMessage` to handle its messages. `VellumScreens.pages(url)` returns the drivers
  showing that page now, to push updates to.

```java
VellumScreens.open("mymod:vellum/notes.html", notesJson).driver()
        .onMessage("save", value -> Notes.save(value.getAsJsonObject()))   // the page saves in its unload listener
        .onClose(Notes::flush);

// The book closes on the mod's own key, as E closes the inventory, and M opens the map:
VellumScreen book = VellumScreens.open("chronicle:vellum/book.html");
book.driver().onKey(e -> {
    if (ChronicleKeys.OPEN.matches(e)) {   // a KeyMapping
        book.onClose();
        return true;
    }
    if (ChronicleKeys.MAP.matches(e)) {
        VellumScreens.open("chronicle:vellum/map.html");
        return true;
    }
    return false;
});
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
| `<item>` | `id`, `count`, `components`, `tooltip` | An item stack with its count and durability bar; 16×16 by default, scaled to the box. `components` is SNBT, as in `/give`: `components='{"minecraft:enchantments":{"minecraft:sharpness":5}}'`. With `tooltip`, hovering shows the vanilla item tooltip at once, with the lines of any `title` that applies after the item's own (below). Items can't be faded: under 50% opacity they are hidden. |
| `<slot>` | `index` | A container slot (container screens only), 18×18. The look comes from CSS; vanilla draws the item. |
| `<entity>` | `type`, `player`, `id`, `rotatable`, `follow-mouse`, `walk`, `baby`, `variant`, `color`, `components`, `mainhand`, `offhand`, `head`, `chest`, `legs`, `feet`, `body`, `saddle` | A live entity: `type="minecraft:pig"` (a client-side copy), `player` (you), or `id` (a world entity). It stands on the bottom of its box, centred and fitted to the room it needs to turn, or with `-mc-entity-focus: eyes` its head and shoulders fill the box (below). CSS turns it (`-mc-yaw`, `-mc-pitch`, `-mc-model-scale`); `rotatable` lets the player drag it round; `follow-mouse` turns its head toward the pointer (`-mc-gaze-reach` and `-mc-gaze-limit` soften it, below); `walk` (or `walk="0.4"`, a speed) swings its legs. Created entities play their idle animations and take `baby`, `variant` and `color` (`variant="minecraft:black"` on a cat, `color="pink"` on a sheep: the `<type>/variant` and `<type>/color` components), `components` (SNBT of entity components, e.g. `{"minecraft:wolf/collar":"red"}`) and items by equipment slot (`mainhand="minecraft:iron_sword"`). 32×48 by default. |
| `<model>` | `block` or `item`, `count`, `components`, `rotatable` | A block state (`block="minecraft:oak_stairs[facing=east]"`, as in `/setblock`) or an item (`item="minecraft:trident"`, with `count` and `components` as on `<item>`) in 3D, centred in its box, at the size an item fills its slot. At yaw and pitch 0 an item looks as in the inventory and a block is seen as most blocks are there (30° from above, turned 225°); CSS turns it as it does entities. Blocks without a model (fluids, air) draw nothing. 32×32 by default. |
| `<player-head>` | `name`, `uuid` | A player's face with the hat layer. No attributes: your own face. 16×16 by default. |
| `<sprite>` | `src` | A GUI-atlas sprite such as `minecraft:widget/button`, at its natural size. Nine-slice and tiled sprites keep their borders when resized. |
| `<img>` | `src` | A texture (`ns:textures/....png`, or relative to the page), a sprite (`sprite:ns:path`) or a canvas (`canvas:<id>`, the `<canvas>` with that id). The natural size comes from the PNG, sprite or canvas. `sprite:` and `canvas:` URLs work in CSS `url()` too. |
| `<canvas>` | `width`, `height` | A pixel surface for scripts, 300×150 by default (at most 2048 a side). `getContext('2d')` supports `fillStyle`/`strokeStyle` (CSS colours), `lineWidth`, `globalAlpha`, `save`/`restore`, `fillRect`, `strokeRect`, `clearRect`, `getImageData`/`putImageData`/`createImageData` and `drawImage` of another canvas; coordinates are whole pixels (no antialiasing), and there is no text, paths or transforms. Show it elsewhere with `canvas:<id>`. |
| `<mc-text>` | `key` + `args`, or `json` | Minecraft text as ordinary inline text: a translation (`key="block.minecraft.stone"`, comma-separated `args`) or a chat component (`json='{"text":"Gold","color":"gold","bold":true}'`). Styled parts become spans. Expanded when the element is added to the page and whenever these attributes change, so it works in templates. |

Any element can have a title, which shows as a vanilla tooltip (below).

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
| `-mc-gaze-reach` | length, `40px` | `<entity follow-mouse>` only. How gently the head turns toward the pointer: larger is gentler. |
| `-mc-gaze-limit` | one to three angles or `none`, `none` | `<entity follow-mouse>` only. The most the head turns to either side, tilts up and tilts down. |

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
lengths and percentages (`right 4px bottom`, `25% 75%`). Items and heads have `object-fit: contain` from the default
stylesheet, so they take a square as wide as the box's shorter side. Models take that square too, times
`-mc-model-scale`.

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

### Following the pointer

`follow-mouse` turns an entity's head toward the pointer as the inventory turns the player's: by
`40° × atan(d / 40px)` for a pointer `d` px from its eyes, sideways and up or down, so at most about 63°. The body
leans half of that turn and the head turns the rest. Two properties change it:

- `-mc-gaze-reach: <length>` replaces the 40px. At twice the reach, the pointer has to be twice as far away for the
  same turn.
- `-mc-gaze-limit: <yaw> [<up> [<down>]]` caps the head's turn to either side, its tilt up and its tilt down. Each
  value is an angle or `none`, and an omitted one repeats the one before it: `30deg 9deg` is 30° either side and 9°
  up or down, `30deg 12deg 4deg` lets the head look up further than down. The body still leans half of the capped
  turn.

On a conversation card the pointer usually rests on the replies, well below the speaker's portrait, and with the
defaults the speaker stares at their feet. Chronicle's cards keep the head nearly level and turn it gently:

```css
entity.speaker { -mc-gaze-reach: 80px; -mc-gaze-limit: 30deg 9deg; }
```

Both are paint-only and animate (`transition: -mc-gaze-limit 300ms`). `none` doesn't interpolate, so a change to or
from it applies at once, or halfway through in `@keyframes`. Both act only on what the pointer adds: `-mc-yaw`,
`-mc-pitch` and dragging a `rotatable` entity turn it as before, and the gaze turns it further from there. The
pointer's distance is measured from the eye point with `-mc-entity-focus: eyes`, and from a third of the way down the
box with `body`, as in the inventory.

### Tooltips

Any element can have a `title` (plain text; a newline breaks the line) or a `title-json` (a chat component, for
coloured text). Once the pointer has rested on the element for its `-mc-tooltip-delay`, half a second unless a rule
sets it, the vanilla tooltip shows at the pointer, wrapped at 170 px like a widget tooltip. Add `title-nowrap` to the
element to keep its lines whole; they then break only at newlines. The nearest title from the hovered element up wins,
and a container slot's item tooltip wins over it. See SCRIPTING.md.

`-mc-tooltip-delay: <time>` is inherited, so a map or a shop list can show its tooltips at once, as vanilla shows a
slot's item, by setting it once. The delay is the one of the element whose title shows, not of the element under the
pointer. It is read by input alone, so changing it restyles nothing that is laid out or painted.

```css
.map { -mc-tooltip-delay: 0ms; }           /* every pin on the map */
.map .legend { -mc-tooltip-delay: 1s; }    /* except the legend, which waits */
```

Over an `<item tooltip>`, the item's own tooltip shows at once, and the title that applies (the nearest one from the
item up) adds its lines after the item's, in the same box. Neither the item's lines nor the title's wrap. This gives a
shop row the vanilla merchant layout: the item, then the price and a note.

```html
<div class="row" title-json='{"text":"","extra":[{"text":"Buy for 6 emeralds","color":"green"},
     {"text":"\nIron comes a long way to get here","color":"gray","italic":true}]}'>
  <item id="minecraft:iron_sword" tooltip></item> Iron Sword
</div>
```

This is the default because a title on a row that holds an item usually describes that item. To show the item's
tooltip alone, give the item an empty title: `<item id="minecraft:iron_sword" tooltip title="">`. Elsewhere in the row
the title shows alone after its delay, wrapped unless the row has `title-nowrap`. On NeoForge the item's stack goes
along to NeoForge's tooltip events, as for any item tooltip.

## Narration

With Minecraft's narrator on (Ctrl+B), a Vellum screen narrates as a vanilla screen does. When it opens, the narrator
reads the screen's title. Then, after a key or button press (200 ms) or once the pointer has rested (750 ms), it
reads the focused element, or else the one under the pointer, in the words vanilla uses for its widgets:
"Reply 1: About the letter button. Left click to activate". It reads only what changed since it last spoke.

The title is the page's `<title>`. A script that sets `document.title` changes it, and `Screen.getTitle()` returns
it too. A page without one is "Vellum" (a container screen keeps its menu's title). A link to another page in the same
screen reads the new page's title.

The element under the pointer is read when it is a control, a link, a slot, a tab stop or an `<item tooltip>`, or
when it has a `title`, `title-json` or `aria-label`. Pointing at text inside such an element reads the element.
Plain text and decoration are not read, and neither is anything past an element with an empty `title`.

What an element is called, its name, is the first of these that says something:

1. `aria-label`;
2. the text of the elements `aria-labelledby` names (ids, separated by spaces);
3. an image's `alt`, a button input's `value`, the item of an `<item>` or `<slot>`, a control's `<label>`;
4. its text content, cut to about 100 characters (not for fields, images and slots);
5. its own `title`, or the text of its `title-json`;
6. a text field's `placeholder`.

After the name the narrator reads the text of the elements `aria-describedby` names, else the title that applies to
the element when it is not the name. A button with a title reads its label, then its tooltip, as vanilla reads a
widget's tooltip. Anything inside `aria-hidden="true"` is never read.

| Element | Read as |
|---|---|
| `<button>`, `<summary>`, button inputs, `role="button"` | "Done button" |
| `<a href>`, `role="link"` | "Map link" |
| `<input type=checkbox>`, `role="checkbox"` or `"switch"` with `aria-checked` | "Checkbox: Show hints: ON" |
| `<input type=radio>`, `role="radio"` with `aria-checked` | "Radio button: Easy: OFF" |
| `<input type=range>`, `role="slider"` with `aria-valuetext` or `aria-valuenow` | "Volume: 40 slider" |
| text inputs, `<textarea>`, `role="textbox"` | "Name edit box: Steve" (a password's text is not read) |
| `<select>` | "Difficulty: Hard button" |
| `role="tab"` | "Selected tab 2 out of 3. Quests tab": its place among the tabs of its `role="tablist"` (or its parent), or `aria-posinset` and `aria-setsize` |
| `<item>`, `<slot>` | "Item: Iron Sword"; a labelled slot "Fuel. Item: Coal". An empty slot nothing labels is not read. |
| anything else | its name |

Controls add how to use them, as vanilla's do ("Left click to activate", "Press Enter to activate", "Drag the slider
to change its value"); a checkbox says "Press Space to check" when focused, since Space toggles it. A disabled control
says nothing about its use. When the page has more than one tab stop, the narrator says where the element is among
them ("Screen element 2 out of 5").

Text is read as it is laid out: whitespace collapsed, hidden text left out (`aria-hidden`, `display: none`,
`visibility: hidden`, scripts), an image read as its `alt` and an item as its name. Each block (a block, flex or grid
box, or a line ended by `<br>`) is a sentence of its own, so a speaker's name above their words reads as "speaker.
what they say".

### Live regions

An element with `aria-live="polite"`, `role="status"` or `role="log"` reads its text when it changes, after whatever
the narrator is saying. One with `aria-live="assertive"` or `role="alert"` cuts the narrator off; when several
announce in the same frame, the first cuts in and the rest follow it, assertive ones first. `aria-live="off"`
silences a role. A log reads only what it gained: its new children, so a conversation where each line is one element
holding the speaker and what they say reads each new line as "speaker. what they say". A region inside another speaks
for itself and is left out of the outer one's text. Changes made in the same frame are read once, as the text they
end with.

Unlike a browser, a page also reads its regions when it opens, after its title (a log, its last entry), and so does a
region added later. A conversation then opens with what was last said.

```html
<head>
  <title>Emperor Cualius</title>
  <style>.speaker { display: block }</style>
</head>
<body>
  <div class="log" role="log">
    <p class="line"><b class="speaker">Emperor Cualius</b>Greetings, stranger! Have you done what I asked?</p>
  </div>
  <button aria-label="Reply 1: About the letter">1 About “Word to Candacona”…</button>
  <div class="flourish" aria-hidden="true">~ ~ ~</div>
</body>
```

The narrator says "Emperor Cualius" as the screen opens, then "Emperor Cualius. Greetings, stranger! Have you done
what I asked?". With the pointer resting on the reply it says "Reply 1: About the letter button. Left click to
activate". When a script appends `<p class="line"><b class="speaker">Emperor Cualius</b>Then take it to Candacona,
and quickly.</p>` to the log, it says "Emperor Cualius. Then take it to Candacona, and quickly.". The flourish is never
read.

A HUD overlay reads its live regions too, also in the HUD layer. Over a screen it is interactive over, it reads the
element under the pointer itself once the pointer has rested for 750 ms, since the screen under it knows nothing of
the overlay. It never reads a focused element: keys stay with the screen. The previewer prints what the narrator would
be given with `--narrate` (preview/README.md).

Text a script reveals a few letters at a time is read a few letters at a time; put the whole line in at once and
reveal it with CSS. A slot whose item changes is read again when the pointer next rests on it, but a live region
holding it does not announce the change.

## Entity render states

Vellum draws an entity from the render state its renderer makes. If your renderer adds things for the world (speech
bubbles, labels, effects), register a function that makes a state for screens instead:

```java
// Client setup:
VellumEntities.registerPortraitState(MyEntities.AUTOMATON.get(), AutomatonRenderer::portraitState);
```

```java
public static <T extends Entity> void registerPortraitState(EntityType<T> type, PortraitState<? super T> state)

@FunctionalInterface
public interface PortraitState<T extends Entity> {   // VellumEntities.PortraitState
    @Nullable EntityRenderState create(T entity, float partialTick);
}
```

The function gets the entity and the partial tick and runs every frame for every `<entity>` of that type that is on
screen. Return null to fall back to the renderer's state for that frame. Registering the type again replaces the
function. A method reference like the one above, or a lambda, fits it.

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
| `Optional<float[]> pointerTarget(String selector)` | `{x, y}` where `hover` would put the pointer, without moving it; empty when `hover` would return false. For pointer paths of your own, like a glide toward the element. |
| `boolean click(String selector)`, `click(String selector, int button)` | Hovers it, then presses and releases `button`: 0 left, 1 middle, 2 right. False, sending nothing, when `hover` is. The pointer stays on the element. |
| `boolean wheel(String selector, double notches)` | Hovers it, then turns the wheel; positive notches scroll down. False when `hover` is. The pointer stays on the element. |
| `boolean drag(String selector, float dx, float dy)` | Hovers it, presses the left button, moves `(dx, dy)` GUI px in a few steps and releases, all at once (see below). False, sending nothing, when `hover` is. The pointer stays where the drag ended. |
| `void leave()` | Moves the pointer outside the window, where it hovers nothing: no `:hover` style, `title` tooltip, item tooltip or slot highlight is left in the next frame, on any Vellum page or vanilla screen. Does nothing while no screen is open. |
| `boolean scrollIntoView(String selector)` | Scrolls the scroll containers the first match is in so it shows, instantly and by the least scroll. True when some of it shows afterwards. |
| `boolean key(String domKey)` | Presses and releases the key that gives a DOM key name (`"Enter"`, `"ArrowDown"`, `"a"`, `"A"`) on the current layout. False when no key does, or the page takes no input. |
| `void type(String text)` | Types text. Characters no key gives are sent as text only. |
| `Optional<JsonElement> eval(String js)` | Runs `js` in the page's script sandbox; its completion value as JSON. Empty for undefined, functions and errors (which are reported like any script error). |
| `boolean tooltipShown()` | Whether the page's last frame showed a tooltip: a `title` past its delay, or an `<item tooltip>`'s. False while a container screen's slot shows its own item tooltip. |
| `Optional<String> narration()` | What the narrator says for the page's screen now, all of it, as vanilla puts it together when a screen opens: the title, then the focused or hovered element (`"Emperor Cualius. Reply 1: About the letter button. Left click to activate"`). Works with the narrator off. Empty for a HUD overlay. |
| `static List<String> recordNarration()` | Starts recording what Vellum hands the narrator besides a screen's own narration: live regions' announcements and what HUD overlays read under the pointer. While it records, pages work this out with the narrator off too. Returns the list it records into, the same one until `stopRecordingNarration()`. |
| `static void stopRecordingNarration()` | Stops recording. |
| `boolean settled()` | Whether the page has stopped changing by itself (see below). |

`hover`, `click` and `wheel` aim at the centre of the part of the element that shows: its border box cut to the
window and to every scroll container (or other `overflow` clip) it is in. When none of it shows, its scroll
containers first scroll it into view, as `scrollIntoView` does. Then they hit test that point. If the topmost thing
painted there is not the element or something inside it (a positioned sibling on top, a HUD overlay over the screen,
or the element has `pointer-events: none`), they return false and send nothing, since a real click would land
elsewhere.

After `click` or `wheel` the pointer stays where it is, as a real mouse would: the element keeps `:hover`, and after
its delay its `title` tooltip shows. Call `leave()` before a screenshot. Pages follow the pointer from their first
frame, so the next page you open is hovered wherever the pointer was left, too.

`drag` sends a press, a few moves with the button held and a release through the mouse handler, all within one call.
The screen gets the `mouseMoved` and `mouseDragged` calls a real drag brings, so a `rotatable` element turns and
tilts by the distance and a range slider or a scrollbar thumb follows. No time passes during the drag, so a
turntable has no speed to keep spinning with when it is let go.

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
- a `title` tooltip is waiting out its delay;
- replaced content is still loading something in the background (`ReplacedContent.loading()`).

Infinite animations don't count, or a title screen would never settle. Timers (`setTimeout`, `setInterval`) and
`requestAnimationFrame` callbacks don't count either: pages use them for clocks, polling and loops that never end.
When a timer changes the page, wait for that change with `text` or `eval`. A text field's blinking caret doesn't
count. A page whose timers keep starting transitions or animations may never settle (the showcase HUD's combat loop
is one), so cap the wait. A HUD overlay is not settled until its page has loaded, at its first draw; a page showing
its error panel is settled.

Vellum's own autopilot (`./gradlew :neoforge:runClient -Pautopilot`, or `:fabric:runClient`) uses all of this: it
waits for each page to settle, hovers the showcase title screen, drags a turntable, opens a page under a resting
cursor and checks it is hovered within its first frames, hovers items and titles for their tooltips, checks that a
map pin with `-mc-tooltip-delay: 0ms` shows its title on the first frame and what the narrator is given on a
conversation (its title, a hovered reply, each new line of its log), closes a page with a mod's key through `onKey`,
fills in the templates demo, clicks a row scrolled out of the Mobdex's list, and answers the demo toast through chat,
whose hovered button the overlay narrates. It ends by logging how many of its checks failed.

## Stability

The API is `dev.vellum.mod.server.VellumServer`, `VellumSession`, and `dev.vellum.mod.client.VellumScreens`,
`VellumScreen`, `VellumContainerScreen`, `VellumHud`, `VellumEntities`, `VellumAutomation` and `DocumentDriver`'s
public methods. Other classes are
internal. Vellum is at 0.x: expect changes, which will be listed in the changelog.
