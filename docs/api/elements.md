# Minecraft elements

Items, slots, entities, models, player heads and sprites on a page, and how your entities are drawn in them. [All docs](../index.md)


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

## Placing entities and models

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

## Tooltips

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
