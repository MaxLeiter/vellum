# Development

Commands, hot reload, and driving pages from code for autopilots and tests. [All docs](../index.md)

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
- `config/vellum.properties`: `client.reducedMotion=true` makes pages match `@media (prefers-reduced-motion: reduce)`.
  The file holds every other setting too ([Settings](security.md#settings)).

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

Vellum's own autopilot (`./gradlew :neoforge:26.3:runClient -Pautopilot`, or `:fabric:26.3`, `:neoforge:1.21.1`
and `:fabric:1.21.1`) uses all of this: it waits for each page to settle, hovers the showcase title screen, drags a
turntable, opens a page under a resting cursor and checks it is hovered within its first frames, hovers items and
titles for their tooltips, checks that a map pin with `-mc-tooltip-delay: 0ms` shows its title on the first frame and
what the narrator is given on a conversation (its title, a hovered reply, each new line of its log), closes a page
with a mod's key through `onKey`, fills in the templates demo, clicks a row scrolled out of the Mobdex's list, and
answers the demo toast through chat, whose hovered button the overlay narrates. It ends by logging how many of its
checks failed, and on 1.21.1 the ones it skipped because their feature is 26.3 only.
