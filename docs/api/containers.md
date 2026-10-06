# Container screens

Inventories and other menus whose slots are `<slot>` elements on a page. [All docs](../index.md)


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

Try it: `/vellum demo chest` opens a chest whose screen is `assets/vellum/vellum/demo/chest.html`.
