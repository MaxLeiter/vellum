# Scripting Vellum UIs

Vellum pages can carry JavaScript: `<script>` elements (inline or `src`), inline handlers (`onclick="..."`), and
Vue-style templates that bind HTML to data without writing any script at all. This guide covers what scripts can
use, how templates work, and where Vellum differs from a browser.

Scripts run in [Mozilla Rhino](https://github.com/mozilla/rhino) 1.9 inside a sandbox, because pages can come from
servers (see DECISIONS.md D-005 and D-010).

- [The language](#the-language)
- [How scripts run](#how-scripts-run)
- [Globals](#globals)
- [The DOM](#the-dom)
- [Events](#events)
- [Animations](#animations)
- [The `vellum` object](#the-vellum-object)
- [Templates](#templates)
- [Limits](#limits)

## The language

Rhino implements most of ES2015 and a good part of later editions. These all work:

- `let` and `const`, arrow functions, template literals (and `String.raw`), default and rest parameters
- destructuring with defaults (`const {a, b: [c] = []} = obj`), spread in array and object literals
  (`[...a, 1]`, `{...defaults, size: 2}`)
- `for (let x of list)`, `for...in`, generators and custom iterables, labels
- optional chaining `a?.b?.()`, nullish coalescing `??` and `??=`, `**`, optional `catch {}` bindings
- `Map`, `Set`, `WeakMap`, `Symbol`, `Promise` (including `all`, `allSettled`, `any`), `Proxy`, `Reflect`, `BigInt`
- getters, setters, shorthand methods and computed keys in object literals; named capture groups and lookbehind in
  regular expressions
- modern library methods: `Array.prototype.flat/includes/findLast/at/toSorted`, `Object.entries/fromEntries/hasOwn`,
  `String.prototype.padStart/replaceAll/at`...

These do **not** work. Each is a syntax error unless noted:

| Not supported | Write instead |
|---|---|
| `class A {}` | constructor functions and prototypes, or factory functions returning objects |
| `async function`, `await`, `async () => ...` | `promise.then(...)` |
| spread in calls: `f(...args)` | `f.apply(null, args)` |
| `for (const x of list)`, `for (const k in obj)` | `for (let x of list)` |
| object rest in destructuring: `const {a, ...rest} = obj` | copy and `delete`, or pick the fields you need |
| `import` / `export` | several `<script>` elements; they share one global scope |
| `Intl` (it is undefined) | format numbers and dates yourself |

Two semantic differences matter. **A `const` declared in a loop body is bound once**: later iterations keep the first
value, so declare per-iteration values with `let`:

```js
for (let i = 0; i < 3; i++) { const b = i * 2; out.push(b); }  // pushes 0, 0, 0
for (let i = 0; i < 3; i++) { let b = i * 2; out.push(b); }    // pushes 0, 2, 4
```

And **`let` in a loop head is one binding for the whole loop**, not one per iteration. Closures created in the loop
all see the final value:

```js
const handlers = [];
for (let i = 0; i < 3; i++) handlers.push(() => i);  // all three return 3
for (let x of [1, 2]) handlers.push(() => x);        // both return 2
[0, 1, 2].forEach(i => handlers.push(() => i));      // 0, 1, 2: forEach gives each call its own i
```

## How scripts run

Scripts run after the document is parsed, in document order (like `defer`). Then `DOMContentLoaded` and `load`
fire. A `<script>` inserted later by a script runs when it is connected.

When the page goes away (its screen closes, its HUD overlay is hidden, a link or `location.href` loads another page,
or it is reloaded), `pagehide` and then `unload` fire at `window` (`addEventListener('unload', fn)`). Scripts still
run then, so a page can save state or `vellum.send` a last message; timers it starts never fire.

Everything runs on one thread: the game's render thread. Each time the engine calls into scripts (running a
`<script>`, an event listener, an inline handler, a timer, an animation frame, a message from the server) is an
**entry**. When an entry finishes, Vellum

1. runs the microtask queue (promise callbacks and `queueMicrotask`),
2. re-renders templates (see [Templates](#templates)),
3. reports promise rejections that nobody handled.

Errors never escape to the game. An uncaught exception is reported through the host (the game log in Minecraft,
`host.errors` in tests) with the script name and line, for example
`Error in script ns:vellum/shop.html#script: TypeError: Cannot read property "price" from undefined (ns:vellum/shop.html#script:12)`,
and the page keeps working. Line numbers of inline scripts count from the `<script>` tag.

## Globals

The global object is `window` (also `self` and `globalThis`). Top-level `var`, `let`, `const` and function
declarations of every `<script>` are globals.

| Global | Notes |
|---|---|
| `document` | The page. |
| `console.log/info/debug/warn/error` | To the host log at the matching level. Strings print raw, other values as JSON; a leading string can use `%s %d %i %f %o %O %c`. |
| `setTimeout(fn, ms, ...args)`, `setInterval`, `clearTimeout`, `clearInterval` | Run on the frame clock. `fn` may be a string of code. |
| `requestAnimationFrame(fn)`, `cancelAnimationFrame(id)` | `fn(timestamp)` runs once, before the next frame is drawn, after due timers. |
| `performance.now()` | The current frame time in ms, the clock `requestAnimationFrame` timestamps use. It does not advance during an entry. |
| `queueMicrotask(fn)` | |
| `structuredClone(value)` | A JSON round trip: plain data only. |
| `localStorage`, `sessionStorage` | `getItem`, `setItem`, `removeItem`, `clear`, `key(i)`, `length`. In memory, per page; both start empty when the page opens. |
| `location` | `href` (reading gives the page URL; setting it navigates), `assign(url)`, `replace(url)`, `reload()`. Relative URLs resolve against the page. |
| `innerWidth`, `innerHeight` | Viewport size in GUI pixels. |
| `devicePixelRatio` | Minecraft's GUI scale. |
| `getComputedStyle(el, pseudo?)` | Read-only: `getPropertyValue('margin-top')` or `.marginTop`. Values are CSS text. |
| `addEventListener`, `removeEventListener`, `dispatchEvent` | On `window`, these go to the document. |
| `close()` | Closes the screen (same as `vellum.close()`). |
| `JSON`, `Math`, `Date`, `RegExp`, `Promise`... | The standard library. |
| `Event`, `CustomEvent` | Constructible. `Node`, `Element` (alias `HTMLElement`), `Text`, `Document`, `DocumentFragment`, `MouseEvent`, `WheelEvent`, `KeyboardEvent`, `FocusEvent`, `InputEvent`, `TransitionEvent` (alias `AnimationEvent`), `CSSStyleDeclaration`, `DOMTokenList`, `Animation` and `Storage` exist for `instanceof`. |

There is no `fetch`, `XMLHttpRequest`, `WebSocket`, `alert`, `prompt` or module loader. Talk to the server with
[`vellum.send` and `vellum.on`](#the-vellum-object).

## The DOM

Nodes are the same object every time you reach them (`document.getElementById('a') === list.firstElementChild`).
Lists such as `childNodes`, `children`, `querySelectorAll()` and `getElementsByClassName()` return real arrays,
which are snapshots: they do not update when the document changes.

**Node** (elements, text, the document and fragments): `nodeType`, `nodeName`, `ownerDocument`, `isConnected`,
`parentNode`, `parentElement`, `childNodes`, `firstChild`, `lastChild`, `previousSibling`, `nextSibling`,
`children`, `childElementCount`, `firstElementChild`, `lastElementChild`, `previousElementSibling`,
`nextElementSibling`, `textContent`, `hasChildNodes()`, `contains(node)`, `appendChild`, `insertBefore`,
`removeChild`, `replaceChild`, `cloneNode(deep)`, `remove()`, `append(...)`, `prepend(...)`, `before(...)`,
`after(...)`, `replaceWith(...)`, `replaceChildren(...)` (these accept nodes and strings), `querySelector`,
`querySelectorAll`, `getElementsByTagName`, `getElementsByClassName`, `addEventListener`, `removeEventListener`,
`dispatchEvent`, and `on<event>` handler properties (`button.onclick = fn`).

**Element**:

- Identity and attributes: `tagName` (upper case), `localName`, `id`, `className`, `classList`, `dataset`,
  `attributes` (an array of `{name, value}`), `getAttribute`, `setAttribute`, `removeAttribute`, `hasAttribute`,
  `hasAttributes`, `toggleAttribute(name, force?)`, `getAttributeNames()`.
- Reflected attributes: `name`, `type`, `placeholder`, `href`, `src`, `title`, `tabIndex`, and the flags
  `disabled`, `hidden`, `readOnly`, `required`, `autofocus`.
- `classList`: `add(...)`, `remove(...)`, `toggle(token, force?)`, `contains`, `replace(old, new)`, `item(i)`,
  `length`, `value`, `forEach(fn)`.
- `dataset`: `data-*` attributes as camelCase properties (`data-slot-count` is `dataset.slotCount`).
- `style`: the inline style. Any CSS property in camelCase (`el.style.backgroundColor = '#333'`, vendor prefixes as
  `webkitTransform` or `WebkitTransform`), plus `setProperty(name, value, priority?)` (for `--custom` properties
  too), `getPropertyValue`, `getPropertyPriority`, `removeProperty`, `cssText`, `length`, `item(i)`. Assigning
  `null` or `''` removes a property; assigning a string to `el.style` sets `cssText`.
- Markup: `innerHTML`, `outerHTML`, `insertAdjacentHTML(position, html)`, `innerText` (the same as `textContent`).
- Selectors: `matches(selector)`, `closest(selector)`. An invalid selector throws a `SyntaxError`.
- Form state: `value`, `checked`, and for `<select>` `selectedIndex`, `options`, and `selected` on options.
  `value` and `checked` are live state; the attributes keep their initial values. A checkbox's `value` is `"on"`
  unless it has a `value` attribute. Checking a radio unchecks the others of its group. Setting a select's `value`
  (or `selectedIndex`) to something no option has leaves nothing selected (`selectedIndex` is then -1).
- Geometry (layout is brought up to date first): `getBoundingClientRect()` (`x`, `y`, `width`, `height`, `top`,
  `right`, `bottom`, `left` in GUI pixels: the box as painted, so after scrolling and transforms),
  `offsetLeft/Top/Width/Height` (relative to the viewport; `offsetParent` is always null), `clientWidth/Height`,
  `scrollWidth/Height`, `scrollLeft/Top` (settable), `scrollTo(x, y)` or `scrollTo({left, top, behavior})`,
  `scrollBy(...)`, `scrollIntoView()` (`true`/`false`, or `{block, inline, behavior}`). Scrolls without a
  `behavior` (also setting `scrollTop`) follow the element's `scroll-behavior`, which is `smooth` by default in
  Vellum: the position then eases there over the next frames. `scroll` events fire once per frame.
- `focus()`, `blur()`, `click()`, `animate(keyframes, options)`.
- Canvases: `width`/`height` (settable; a new size clears the canvas) and `getContext('2d')`, which returns the
  canvas's one 2D context (null for other types and other elements). The context supports `fillStyle` and
  `strokeStyle` (CSS colours), `lineWidth`, `globalAlpha`, `save()`/`restore()`, `fillRect`, `strokeRect`,
  `clearRect`, `getImageData`, `putImageData` (with an optional dirty rectangle), `createImageData(w, h)` or
  `(imageData)`, and `drawImage(canvas, ...)` with 3, 5 or 9 arguments (another canvas only, scaled
  nearest-neighbour). Image data is `{width, height, data}` with `data` a `Uint8ClampedArray` of RGBA bytes (a plain
  array works for `putImageData` too). Coordinates are canvas pixels, and edges snap to whole pixels: there is no
  antialiasing, text, paths, transforms, gradients or patterns. A canvas can be drawn on before it is added to the
  page.

**Text**: `data`, `nodeValue`, `length`.

**Document**: `documentElement`, `head`, `body`, `title` (settable), `activeElement` (the focused element, or
`body`), `readyState`, `URL`, `location`, `defaultView`, `getElementById`, `createElement`, `createTextNode`,
`createDocumentFragment`.

## Events

`addEventListener(type, listener, options)` takes a function or an object with `handleEvent`; `options` is a
capture flag or `{capture, once}`. `removeEventListener` matches the same function and capture flag. Inside a
listener, `this` is the element the listener is on.

Every event has `type`, `target`, `currentTarget`, `eventPhase`, `bubbles`, `cancelable`, `defaultPrevented`,
`timeStamp`, `preventDefault()`, `stopPropagation()` and `stopImmediatePropagation()`. All listeners of one dispatch
see the same event object. By kind:

| Event | Fields |
|---|---|
| `MouseEvent` (`click`, `mousedown`, `mousemove`, `mouseover`...) | `clientX`, `clientY` (also `pageX`, `pageY`), `offsetX`, `offsetY`, `button`, `buttons`, `detail` (click count), `relatedTarget`, `shiftKey`, `ctrlKey`, `altKey`, `metaKey` |
| `WheelEvent` (`wheel`) | the mouse fields, `deltaX`, `deltaY` (GUI pixels), `deltaMode` (always 0) |
| `KeyboardEvent` (`keydown`, `keyup`) | `key` (`"a"`, `"Enter"`, `"ArrowLeft"`, `"Escape"`...), `code` (`"KeyA"`), `keyCode` (the legacy code of the physical key, as browsers report it on a US layout), `repeat`, and the modifier keys |
| `FocusEvent` (`focus`, `blur`, `focusin`, `focusout`) | `relatedTarget` |
| `InputEvent` (`beforeinput`, `input`, `change`) | `data`, `inputType` |
| `TransitionEvent` / `AnimationEvent` | `propertyName`, `animationName`, `elapsedTime` |
| `CustomEvent` | `detail` |

Make your own with `new Event(type, {bubbles, cancelable})` or `new CustomEvent(type, {detail, bubbles, cancelable})`
(both default to not bubbling, as in browsers) and `el.dispatchEvent(event)`, which returns false when a listener
called `preventDefault()`.

Inline handlers (`<button onclick="buy(this.dataset.item, event)">`) run with `this` = the element and `event` in
scope; returning `false` cancels the event, and so does returning `false` from an `on<event>` property handler.
Unlike browsers, inline handlers do not see the element's properties as variables: write `this.value`, not `value`.

Pressing Escape closes the screen unless a `keydown` listener calls `preventDefault()`.

The `title` attribute shows a tooltip, as in browsers but drawn like Minecraft's: when the pointer has rested on an
element for half a second, the nearest `title` from the hovered element up shows at the pointer. A newline in the
value breaks the line (`&#10;` in HTML, `'\n'` in a script string); long lines wrap. `title-json` takes a chat
component instead, for coloured text (`title-json='{"text":"Rare","color":"gold"}'`), read like `<mc-text json>`.
An empty `title` hides an ancestor's. Pressing a button or key hides the tooltip until the pointer moves to another
element with one. Changing the attribute from a script changes the tooltip while it shows.

## Animations

`el.animate(keyframes, options)` runs a Web Animations style animation on top of the element's CSS:

```js
const anim = panel.animate(
  [{opacity: 0, transform: 'translateY(8px)'}, {opacity: 1, transform: 'none'}],
  {duration: 200, easing: 'ease-out', fill: 'forwards'});
anim.finished.then(() => console.log('shown'));
```

- Keyframes are an array of objects (`offset` and `easing` per keyframe are optional; missing offsets are spread
  evenly) or an object of arrays (`{opacity: [0, 1], marginLeft: ['0px', '8px']}`). Properties are camelCase or
  CSS names; values are CSS text.
- Options are a duration in ms, or `{duration, delay, easing, iterations, direction, fill}`. `easing` is `linear`
  (the default), `ease`, `ease-in`, `ease-out`, `ease-in-out`, `step-start`, `step-end`, `cubic-bezier(...)` or
  `steps(n, jump-start|jump-end|jump-none|jump-both)`; `iterations` may be `Infinity`; `direction` is `normal`,
  `reverse`, `alternate` or `alternate-reverse`; `fill` is `none`, `forwards`, `backwards` or `both`.
- The returned `Animation` has `play()`, `pause()`, `cancel()`, `finish()`, `reverse()`, `playState` (`idle`,
  `running`, `paused`, `finished`), `currentTime`, `playbackRate`, `onfinish`, `oncancel` and a `finished` promise
  (rejected with an `AbortError` when cancelled).

## The `vellum` object

| Member | |
|---|---|
| `vellum.data` | The latest data from the server or the mod that opened the page (a parsed JSON value; `{}` until data arrives). Templates read from it. |
| `vellum.on(channel, fn)` | Calls `fn(value)` for messages on `channel`; `'data'` fires whenever `vellum.data` is replaced. Returns a function that removes the listener. |
| `vellum.off(channel, fn)` | Removes a listener. |
| `vellum.send(channel, value)` | Sends `JSON.stringify(value)` to the server or mod. At most 20 messages per second (bursts of 20); extra messages are dropped with a warning, and `send` returns false. |
| `vellum.state(object)` | Makes the object's properties visible to [templates](#templates) and returns it. |
| `vellum.close()` | Closes the screen. |
| `vellum.playSound(id, volume = 1, pitch = 1)` | Plays a sound event such as `'minecraft:ui.button.click'`. |
| `vellum.t(key, ...args)` | Translates a language key, e.g. `vellum.t('gui.done')`. |
| `vellum.open(url)` | Opens another Vellum page (relative to this one). |
| `vellum.nextTick(fn)` | Calls `fn` once [templates](#updates) have rendered the current state (at the next frame). |

A message also reaches the page as a `message` event on the document whose `detail` is `[channel, json]`.

## Templates

Templates bind HTML to data with Vue-like syntax and need no build step. They are compiled once, when the page has
loaded and its scripts have run, so a page needs no `<script>` at all to use them:

```html
<div class="mc-panel">
  <h1>{{ title }}</h1>
  <p v-if="items.length === 0">Nothing for sale.</p>
  <ul v-else>
    <li v-for="item in items" :key="item.id" :class="{ sold: item.stock === 0 }">
      <item :id="item.id"></item> {{ item.name }} - {{ item.price }} emeralds
      <button :disabled="item.stock === 0" @click="vellum.send('buy', item.id)">Buy</button>
    </li>
  </ul>
</div>
```

The server sends `{"title": "Armorer", "items": [...]}` as data, and the page follows every update.

### Expressions and scope

`{{ expr }}`, directive values and handlers are JavaScript. A name is looked up in, in order:

1. the loop variables of enclosing `v-for`s (and `$event` in handlers),
2. `vellum.data`,
3. the objects passed to `vellum.state()`, in the order they were registered,
4. the globals: functions and variables of your scripts, `Math`, `JSON`, `vellum`...

Assigning to a name (`@click="count++"`) writes to whichever of those objects has it, or creates a global. In
handler statements `this` is the element. Methods are called as methods: in `@click="shop.buy"`, and in
`@click="buy"` for a method of a `vellum.state()` object, `this.stock` inside `buy` reads that object's `stock`.

```html
<button @click="count++">Clicked {{ count }} times</button>
<script>
  const counter = vellum.state({count: 0});
</script>
```

Text interpolation shows strings as they are, `null` and `undefined` as nothing, and objects and arrays as JSON.

### Updates

Templates render when the page loads. After any entry (an event handler, a timer, an animation frame, a message, a
`v-model` input...) Vellum re-evaluates every binding once, at the start of the next frame, and changes the DOM only
where a value changed. It repeats until nothing changes, at most 10 times; if a template still changes after that
(an expression that modifies state when evaluated, such as `{{ n++ }}`), it logs a warning and stops. There is
nothing to call: change your state, and the page catches up by the next frame.

As in Vue, the DOM does not change while your code runs: a handler that sets `count` and then reads the button's
text sees the old text. To read the DOM after it updates, pass a callback to `vellum.nextTick(fn)`.

Templates are compiled once. Markup added later (by `innerHTML` or `v-html`) is not compiled.

### Directives

| Directive | |
|---|---|
| `{{ expr }}` | In text and in attribute values (`title="Level {{ level }}"`). |
| `:attr="expr"` / `v-bind:attr` | Sets an attribute. `null`, `undefined` and `false` remove it; `true` sets it empty (`:disabled="busy"`). `:value` and `:checked` on form controls set their live state. |
| `:class` / `v-class` | A string, an array, or an object of `{className: condition}`; added to the static `class`. |
| `:style` / `v-style` | A string, an array, or an object of `{property: value}` (camelCase or CSS names; `null` and `false` values are skipped); added to the static `style`. |
| `v-if`, `v-else-if`, `v-else` | On sibling elements. Only the first branch whose condition holds is in the document. A branch's element is created once and kept while hidden, so its state (typed text, scroll position) survives. |
| `v-for="item in items"` | Also `(item, index) in items`, `(value, key, index) in object`, and `n in 5` (1 to 5); `of` works like `in`. Repeats the element. |
| `:key="expr"` / `v-key` | On a `v-for` element: the item's identity. Reordering a keyed list moves the existing elements; without a key, elements are reused by position. Keys should be unique. |
| `v-show="expr"` | Hides the element (it gets the `v-hidden` attribute, which the default stylesheet hides) without removing it. |
| `@event="..."` / `v-on:event` | A statement (`count++`), a method name (`save`, called with the event), or a function (`e => pick(e.key)`). The event is `$event`. |
| `v-model="path"` | Two-way binding for `input`, `textarea` and `select` (see below). |
| `v-text="expr"`, `v-html="expr"` | Replace the element's content with text or with markup. |
| `v-cloak` | Removed once the templates have rendered. Add `[v-cloak] { display: none }` to hide raw `{{ }}` until then. |
| `v-pre` | The element and its content are left alone. |

`v-if` on an element that also has `v-for` filters the items: `<li v-for="q in quests" v-if="!q.done">`.

`v-if` and `v-for` go on real elements. A `<template>` wrapper is not supported (its content is never rendered),
and there are no components.

### Event modifiers

Add them after the event name: `@click.prevent.stop="..."`.

| Modifier | |
|---|---|
| `.prevent`, `.stop` | Call `preventDefault()` / `stopPropagation()`. |
| `.once` | Run once. |
| `.self` | Only when the event's target is this element (not a child). |
| `.capture` | Listen in the capture phase. |
| `.enter`, `.esc`, `.space`, `.tab`, `.delete`, `.up`, `.down`, `.left`, `.right` | Key events: only for these keys. |
| `.left`, `.middle`, `.right` | Mouse events: only for this button. |
| `.ctrl`, `.shift`, `.alt`, `.meta` | Only while this modifier key is held. |

### v-model

```html
<input v-model="name">                                  <!-- string -->
<input type="number" v-model="amount">                  <!-- number -->
<input type="range" min="0" max="10" v-model="volume">  <!-- number -->
<input type="checkbox" v-model="agreed">                <!-- boolean -->
<input type="checkbox" value="iron" v-model="picked">   <!-- array: holds "iron" while checked -->
<input type="radio" name="size" value="2" v-model.number="size">
<select v-model="mode"><option>easy</option><option>hard</option></select>
```

Text fields update the model on every `input` event; `.lazy` waits for `change` (Enter or losing focus). `.trim`
trims the text, and `.number` turns text that parses as a number into one (`type="number"` and `type="range"` do
this anyway). The model must be something you can assign to: a name or a property path.

## Limits

Scripts are sandboxed because pages can come from servers:

- No Java access: `java`, `Packages` and friends do not exist, and no Java object is ever visible to scripts.
- No network, files or other pages: the only way out is `vellum.send`.
- CPU: each entry may run about 50 million instructions or 250 ms, whichever comes first. A script that runs over is
  stopped (its `catch` and `finally` blocks do not run), the error is reported, and the page stays usable. The
  limit covers everything the entry does, including microtasks and template updates.
- Recursion is limited to 1000 nested calls (an `InternalError` you can catch).
- `innerHTML`, `outerHTML`, `insertAdjacentHTML` and `v-html` take at most 1M characters.
- `localStorage` and `sessionStorage` hold at most 256K characters each (keys plus values); going over throws a
  `RangeError` whose message starts with `QuotaExceededError`.
- `v-for` renders at most 10,000 items.
- `vellum.send` delivers at most 20 messages per second.

There is no memory limit, so avoid building huge strings or arrays.
