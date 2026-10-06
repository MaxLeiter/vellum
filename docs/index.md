# Vellum docs

Vellum shows HTML, CSS and JavaScript pages as Minecraft screens, inventories and HUD overlays. The Java API is the same
on NeoForge and Fabric, and on Minecraft 26.3 and 1.21.1 apart from the differences listed where they apply.

## Java API

- [Setting up](api/setup.md): how to depend on Vellum, and which parts of its API are stable.
- [Opening pages](api/pages.md): opening a page from the client or from the server, and exchanging data and messages with it.
- [Container screens](api/containers.md): inventories and other menus whose slots are `<slot>` elements on a page.
- [HUD overlays](api/hud.md): pages drawn over the HUD, and overlays that take input over chosen screens.
- [Minecraft elements](api/elements.md): items, slots, entities, models, player heads and sprites on a page, and how your entities are drawn in them.
- [Narration](api/narration.md): what Minecraft's narrator reads from a page.
- [Security](api/security.md): what players are protected from, what a page can still do, what mod and server authors must do, and every setting in `config/vellum.properties`.
- [Development](api/development.md): commands, hot reload, and driving pages from code for autopilots and tests.

## Pages and scripts

- [Scripting](SCRIPTING.md): the JavaScript dialect, templates and the `vellum` object pages use.
- [Porting a screen](MIGRATING.md): moving a hand-drawn screen to a Vellum page.

## Internals

- [Minecraft 26.3 GUI notes](MC_26_3_GUI_API.md): how the 26.3 GUI renderer works, for working on Vellum itself.
- [Previewer](../preview/README.md) and [patched Rhino](../rhino/README.md).
