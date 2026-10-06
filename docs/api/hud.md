# HUD overlays

Pages drawn over the HUD, and overlays that take input over chosen screens. [All docs](../index.md)


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
