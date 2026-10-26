# Minecraft elements

Items, slots, entities, models, scenes, player heads and sprites on a page, and how your entities are drawn in them. [All docs](../index.md)


These elements are drawn by Minecraft. They lay out like images: an intrinsic size, scaled by CSS `width`/`height`
and `object-fit`, and placed in their box by `object-position`.

| Element | Attributes | Notes |
|---|---|---|
| `<item>` | `id`, `count`, `components`, `tooltip` | An item stack with its count and durability bar; 16×16 by default, scaled to the box. `components` is SNBT, as in `/give`: `components='{"minecraft:enchantments":{"minecraft:sharpness":5}}'`. With `tooltip`, hovering shows the vanilla item tooltip at once, with the lines of any `title` that applies after the item's own (below). Items can't be faded: under 50% opacity they are hidden. |
| `<slot>` | `index` | A container slot (container screens only), 18×18. The look comes from CSS; vanilla draws the item. |
| `<entity>` | `type`, `player`, `id`, `rotatable`, `follow-mouse`, `walk`, `baby`, `variant`, `color`, `components`, `mainhand`, `offhand`, `head`, `chest`, `legs`, `feet`, `body`, `saddle` | A live entity: `type="minecraft:pig"` (a client-side copy), `player` (you), or `id` (a world entity). It stands on the bottom of its box, centered and fitted to the room it needs to turn, or with `-mc-entity-focus: eyes` its head and shoulders fill the box (below). CSS turns it (`-mc-yaw`, `-mc-pitch`, `-mc-model-scale`); `rotatable` lets the player drag it round; `follow-mouse` turns its head toward the pointer (`-mc-gaze-reach` and `-mc-gaze-limit` soften it, below); `walk` (or `walk="0.4"`, a speed) swings its legs. Created entities play their idle animations and take `baby`, `variant` and `color` (`variant="minecraft:black"` on a cat, `color="pink"` on a sheep: the `<type>/variant` and `<type>/color` components), `components` (SNBT of entity components, e.g. `{"minecraft:wolf/collar":"red"}`) and items by equipment slot (`mainhand="minecraft:iron_sword"`). 32×48 by default. |
| `<model>` | `block` or `item`, `count`, `components`, `rotatable` | A block state (`block="minecraft:oak_stairs[facing=east]"`, as in `/setblock`) or an item (`item="minecraft:trident"`, with `count` and `components` as on `<item>`) in 3D, centered in its box, at the size an item fills its slot. At yaw and pitch 0 an item looks as in the inventory and a block is seen as most blocks are there (30° from above, turned 225°); CSS turns it as it does entities. Blocks without a model (fluids, air) draw nothing. 32×32 by default. |
| `<scene>` | `structure`, `rotatable`, `notooltips` | A small world in a box: blocks from a structure file and `<block>` children, grouped by `<block-group>`s that show, fade and move with CSS, `<entity>` children standing in it, and `<scene-anchor>` HTML pinned to points in it. Seen as the inventory sees blocks, fitted to its box. Hovering a block shows its name and fires `blockhover`. 160×120 by default. See [Scenes](#scenes). |
| `<player-head>` | `name`, `uuid` | A player's face with the hat layer. No attributes: your own face. 16×16 by default. |
| `<sprite>` | `src` | A GUI-atlas sprite such as `minecraft:widget/button`, at its natural size. Nine-slice and tiled sprites keep their borders when resized. |
| `<img>` | `src` | A texture (`ns:textures/....png`, or relative to the page), a sprite (`sprite:ns:path`) or a canvas (`canvas:<id>`, the `<canvas>` with that id). The natural size comes from the PNG, sprite or canvas. `sprite:` and `canvas:` URLs work in CSS `url()` too. |
| `<canvas>` | `width`, `height` | A pixel surface for scripts, 300×150 by default (at most 2048 a side). `getContext('2d')` supports `fillStyle`/`strokeStyle` (CSS colors), `lineWidth`, `globalAlpha`, `save`/`restore`, `fillRect`, `strokeRect`, `clearRect`, `getImageData`/`putImageData`/`createImageData` and `drawImage` of another canvas; coordinates are whole pixels (no antialiasing), and there is no text, paths or transforms. Show it elsewhere with `canvas:<id>`. |
| `<mc-text>` | `key` + `args`, or `json` | Minecraft text as ordinary inline text: a translation (`key="block.minecraft.stone"`, comma-separated `args`) or a chat component (`json='{"text":"Gold","color":"gold","bold":true}'`). Styled parts become spans. Expanded when the element is added to the page and whenever these attributes change, so it works in templates. |

Any element can have a title, which shows as a vanilla tooltip (below).

CSS extras for Minecraft: `font-family: minecraft:default | minecraft:uniform | minecraft:alt |
minecraft:illageralt | <any font id>` (`monospace` is uniform), `text-shadow: minecraft` (the game's own shadow),
`-mc-tint` (multiplies images, sprites, entities and models; items cannot be tinted), `sprite(ns:path)` backgrounds,
and the chat colors as names (`mc-gold`, `mc-gray`...). Lengths are GUI pixels: `1px` scales with the GUI scale,
`1dp` is one device pixel.

`<entity>` and `<model>` are turned by CSS, so transitions and animations work on them:

| Property | Value | |
|---|---|---|
| `-mc-yaw` | angle, `0` | Turns it about the vertical axis; positive turns its front to the right. |
| `-mc-pitch` | angle, `0` | Views it from above (positive) or below. |
| `-mc-model-scale` | number, `1` | Multiplies the size that fits the box. |
| `-mc-entity-focus` | `body`, `center` or `eyes`, `body` | `<entity>` only. What fills the box: the whole entity, the whole entity zoomed around its middle, or its head and shoulders. |
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


## Vanilla look

Built-in classes that match vanilla's GUI. Buttons, text fields, sliders, checkboxes and `<slot>` already look vanilla
without a class.

| Class | What it gives you |
|---|---|
| `mc-panel` | The light gray container panel with its raised bevel and black outline, as in a chest |
| `mc-label` | Container label text: dark gray, no shadow ("Inventory") |
| `mc-inset` | The sunken bevel around slots |
| `mc-tooltip` | The tooltip box: near-black with the purple frame |
| `mc-dark` | The translucent dark panel behind lists in menus |
| `mc-dim` | The dim over the world behind in-game menus (put it on `html`) |
| `mc-center` | Centers the page's content in the screen, as vanilla centers container GUIs (put it on `body`) |

A vanilla chest is a few lines with these. See [demo/chest.html](../../common/src/main/resources/assets/vellum/vellum/demo/chest.html).

## Text in the Minecraft font

Vellum measures text exactly as vanilla does, so two of the font's habits show up around small boxes of text:

- Every glyph's advance includes the 1 px gap that follows it, the last one too. A one-letter badge with
  `padding: 0 2px` has 2 px of background left of the glyph and 3 px right of it, and centered text sits half a pixel
  left of center, as it does on vanilla buttons. For an exactly centered badge, pad one pixel less on the right:
  `padding: 0 1px 0 2px`.
- A line is 9 px tall at the default size, with the glyphs at its top: capitals fill the first 7 px, and the last
  2 px are for descenders and the gap between lines. One line of capitals in a box therefore has no space above it and
  2 px below, so pad the top 2 px more than the bottom to balance it. A tight, centered one-letter badge is
  `padding: 2px 1px 0 2px`.

## Smooth or pixel-sharp shapes

On Minecraft 26.2 and 26.3, rounded corners, rounded borders and box-shadows are drawn by a shader, so curves have
smooth edges at any GUI scale or rotation and blurs are a real Gaussian. Square corners stay pixel-exact either way.

For hard pixel edges instead, the look of 1.21.1, set `shape-rendering: crispEdges` on an element. It is
inherited, so `html { shape-rendering: crispEdges; }` covers a whole page. `auto` (the default) and
`geometricPrecision` allow smooth shapes, and `optimizeSpeed` is the same as `crispEdges`. Players can also turn the
shader off for everything with `client.shapeShader=false` ([Settings](security.md#settings)). The previewer draws
shapes the way the shader does, and honors `crispEdges` too.

## Placing entities and models

`object-position` places every Minecraft element in its box as it places an image: one to four values, keywords,
lengths and percentages (`right 4px bottom`, `25% 75%`). Items and heads have `object-fit: contain` from the default
stylesheet, so they take a square as wide as the box's shorter side. Models take that square too, times
`-mc-model-scale`.

An `<entity>` is fitted one of three ways:

- `-mc-entity-focus: body` (the default) fits the whole entity, with room to turn it, and `object-position` places
  that room. Without `object-position` it stands on the bottom edge, centered (`50% 100%`), not in the middle.
  `-mc-model-scale` grows it from there, so zooming in shows its feet.
- `-mc-entity-focus: center` fits it as `body` does, but `-mc-model-scale` zooms around the middle of the entity,
  which stays where it was. Use it for a zoomable viewer.
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

## Following the pointer

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

## Scenes

`<scene>` is a small world in a box: a build, a machine, a room. It draws as one picture, and a scene that doesn't
change costs a blit a frame.

```html
<scene structure="mymod:structures/hut.nbt" rotatable style="-mc-yaw: -90deg; -mc-pitch: 10deg">
  <block x="1" y="1" z="1" state="minecraft:furnace[lit=true]"></block>   <!-- add or replace a block -->
  <block x="2" y="0" z="0" state="minecraft:air"></block>                  <!-- erase one -->
  <block-group id="roof" x="0..4" y="3..4" z="0..4" class="hidden"></block-group>
  <entity type="minecraft:villager" x="2.5" y="1" z="2.5"></entity>
  <scene-anchor x="1.5" y="2" z="0.5" class="caption">Put the furnace here</scene-anchor>
</scene>
```

What it shows:

| Child or attribute | |
|---|---|
| `structure` | A structure file, as a structure block saves one (`ns:path/file.nbt` is `assets/ns/path/file.nbt`, or a file in a [page folder](pages.md)). Its blocks keep the states and block entity data they were saved with. Its entities are left out. |
| `<block x="1" y="2" z="3" state="...">` | Adds or replaces a block (whole numbers), on top of the structure, in document order. `state` is a block state as in `/setblock` (`minecraft:oak_stairs[facing=east]`), and `minecraft:air` erases. `nbt` gives a block entity's data as SNBT. |
| `<block-group x="3..7" y="2" z="3..6">` | Claims the blocks in that box, and its own `<block>` children. Each axis is a whole number (one layer) or an inclusive range like `2..5`; an axis left out is all of it, so `<block-group y="2">` is a whole layer. Each block belongs to the first group in document order that claims it; the rest are the scene's base. |
| `<entity x="2.5" y="1" z="3.5">` | An `<entity>` standing at that point, with its usual attributes. `-mc-yaw` faces it as Minecraft yaws go (0 south, 90 west), and `walk` swings its legs. |
| `<scene-anchor x="2.5" y="4" z="3">` | Ordinary HTML, laid out as an absolutely positioned box and moved every frame so its top-left corner is where the point is seen. `transform: translate(-50%, -100%)` puts its bottom middle there instead. |
| `rotatable` | Dragging turns and tilts it, as `<model rotatable>`, adding to the CSS. |
| `notooltips` | No block name tooltip. |

Coordinates are block coordinates, x east, y up and z south; a block at `0 0 0` fills the cube from there to `1 1 1`.

A group is what shows, fades and moves. Groups follow their style every frame, so transitions and `@keyframes` work:

- `display: none`, `visibility: hidden` and `opacity: 0` hide it, and hidden groups can't be hovered.
- `opacity` fades it. A faded group draws see-through; its block entities show only while it is at least half opaque.
- `-mc-translate: x y z` moves it, in blocks. Values left out are 0: `-mc-translate: 0 -0.5`.
- `-mc-yaw` turns it about the vertical axis through the middle of its blocks, the same way it turns a `<model>`:
  positive is clockwise seen from above, as Minecraft turns entities (south to west).
- `-mc-pitch` tips it about the x axis (east to west) and `-mc-roll` about the z axis (north to south), also through
  its middle. Positive tips its top north and east, toward the viewer at the default view.
- Together, it is rolled first, then pitched, then turned by its yaw, as Minecraft poses entities.

`-mc-translate` moves entities and anchors too. Each group is meshed once and again only when its blocks change, so
moving and fading groups is cheap; adding, removing or changing `<block>` and `<block-group>` children from a script
updates the scene on the next frame. Layer views are groups too: one per layer, hidden above the one shown.

The camera uses the properties `<model>` uses. At `-mc-yaw` and `-mc-pitch` 0 a scene is seen as the inventory sees a
block: 30° from above, from the north-east, so the north and east sides show. `-mc-yaw: -90deg` shows the south and
east sides, `180deg` the south and west. The scene fits its box at any turn, `-mc-model-scale` zooms, and
`-mc-scene-center: x y z` is the point it turns about and keeps in the middle (`auto`, the middle of its blocks, by
default). All of them animate.

Hovering a block shows its name as a vanilla tooltip, unless the scene has `notooltips`, and fires `blockhover` on the
scene with `detail` `{x, y, z, state, group}` (`group` is the `<block-group>` that holds it, or null). It fires again
with a null `detail` when the pointer leaves the blocks. A click that didn't drag fires `blockclick` with the same
`detail`. Blocks count as whole cubes for both.

### A build, step by step

Vellum has no timeline. A build that assembles itself is groups, CSS and a timer, as the Cottage showcase
(`/vellum showcase scene`) does it:

```html
<style>
  scene { transition: -mc-yaw 1600ms ease-in-out, -mc-pitch 1600ms ease-in-out; -mc-yaw: -90deg; }
  scene.roof-view { -mc-yaw: -20deg; -mc-pitch: 10deg; }
  block-group.pending { opacity: 0; }
  block-group.drop { animation: drop 900ms ease-out both; }
  @keyframes drop { from { opacity: 0; -mc-translate: 0 5 0; } 45% { opacity: 1; } }
  .caption { transform: translate(-50%, -100%); transition: -mc-translate 1200ms ease-in-out; }
</style>
<scene structure="mymod:structures/hut.nbt">
  <block-group class="pending" x="0..6" y="0" z="0..6"></block-group>
  <block-group class="pending" x="0..6" y="1..3" z="0..6"></block-group>
  <block-group class="pending" x="0..6" y="4..6" z="0..6"></block-group>
  <scene-anchor class="caption" x="0" y="0" z="0"></scene-anchor>
</scene>
<script>
  const steps = [
    {text: 'Floor', point: '3 1 3'},
    {text: 'Walls', point: '3 4 6'},
    {text: 'Roof', point: '3 7 3', view: 'roof-view'},
  ];
  const scene = document.querySelector('scene');
  const groups = scene.querySelectorAll('block-group');
  const caption = scene.querySelector('.caption');
  let step = 0;
  function next() {
    if (step === steps.length) return;
    groups[step].className = 'drop';
    caption.textContent = steps[step].text;
    caption.style.setProperty('-mc-translate', steps[step].point);
    scene.className = steps[step].view || '';
    step++;
    setTimeout(next, 2000);
  }
  next();
</script>
```

The caption glides from point to point on its `-mc-translate` transition, the camera on its own. An entity walks the
same way: set its `-mc-translate` with a transition, and `walk` while it moves.

What renders: block models with faces between neighbors in a group culled, connected blocks as their states say
(structure files save fences, panes, walls and stairs connected; write the connections yourself on `<block>`), fluids,
block entities (chests, beds, signs, banners) with their own renderers but at rest, and entities. Blocks are lit full
bright by the inventory's light, so the world's time and dimension never show. Faces between two groups are drawn,
so a group can move away without leaving holes. On 1.21.1 scenes draw nothing ([versions](../versions.md)). The
previewer draws blocks from their models with simple shading, entities as silhouettes, and no block entities.

## Tooltips

Any element can have a `title` (plain text; a newline breaks the line) or a `title-json` (a chat component, for
colored text). Once the pointer has rested on the element for its `-mc-tooltip-delay`, half a second unless a rule
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

## Entity render states

Minecraft 26.3 only: 1.21.1 has no entity render states, and Vellum poses the entity itself there.

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
