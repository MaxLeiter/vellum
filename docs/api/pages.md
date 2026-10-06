# Opening pages

Opening a page from the client or from the server, and exchanging data and messages with it. [All docs](../index.md)

## Opening a page on the client

```java
VellumScreen screen = VellumScreens.open("mymod:vellum/settings.html");
screen.driver().onMessage("save", value -> MyConfig.save(value.getAsJsonObject()));

// With initial data (the page's vellum.data):
VellumScreens.open("mymod:vellum/journal.html", journalJson);

// Inline HTML, e.g. generated at runtime:
VellumScreens.openInline("<h1>Hello</h1><p>{{ name }}</p>", data);
```

- `vellum.close()` (or Escape, unless the page handles it) closes the screen. Shift+Escape always closes it, and so
  do three quick Escapes (see [Security](security.md)).
- `vellum.send(channel, value)` in the page calls every `onMessage(channel, ...)` handler with the value as a
  `JsonElement`. A page may send a burst of 20 messages, then 20 a second (`client.messageBurst`,
  `client.messagesPerSecond`), so up to 40 arrive in the first second.
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
  showing that page now, to push updates to. The hook also runs when a server opens or links to the page:
  `driver.serverSession()` is then true, and you should not give the page anything the server must not see.

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
  are rate-limited (a burst of 40, then 20 per second); malformed or too deeply nested JSON and messages from other
  players are dropped. Treat every message as hostile input: see [Security](security.md).
- `onClose` runs once, when the player closes the screen, opens another session, leaves, or you call `close()`. A
  player has at most 8 open sessions; opening another ends the oldest.
- Limits: inline pages 200,000 characters, data 100,000 characters (as JSON), messages 8,192 characters. `open` and
  `push` throw `IllegalArgumentException` above them. Servers can lower these in `config/vellum.properties`.
- Pages sent by servers run sandboxed: no Java access, no network, no files, and a CPU and memory budget. See
  [Security](security.md) for what they can and can't do.
- The player decides whether server pages show at all (`client.serverPages`), and a page waits while the player is
  in chat or a menu. Don't assume the page is on screen as soon as `open` returns.
