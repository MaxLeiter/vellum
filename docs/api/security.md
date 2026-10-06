# Security

What players are protected from, what a page can still do, what mod and server authors must do, and every setting in `config/vellum.properties`. [All docs](../index.md)


Players join servers they don't control, and servers let in clients they don't control. Vellum assumes the worst of
both. A page a server opens is untrusted whether the server sent its HTML (`openInline`) or it comes from a mod: a
server resource pack can replace any mod's page, script or stylesheet, so the page's code may be the server's either
way. Your own bundled pages run under the same rules. Messages a client sends are untrusted too, since a modified
client can send anything.

## What players are protected from

- Scripts run in a sandbox: no Java, no network, no files. Their only way out is `vellum.send`, to the server that
  opened the page or to the mod's own handlers. They can't read the clipboard (only the player's own Ctrl+V into a
  text field does) or write it (only Ctrl+C or Ctrl+X).
- A page can't keep the game busy. Each script call has a budget (50M instructions or 1 second), timers and frame
  callbacks get 100 ms a frame, and a page that keeps running out of budget, or keeps frames slower than 200 ms, is
  stopped.
- A page can't fill the game's memory. One script call may allocate 256 MiB, a page may have 100,000 nodes nested
  512 deep and 16M canvas pixels, built-ins refuse arrays over 1M elements and strings over 16M characters, and a
  page whose script finds the heap more than 90% full after a collection is stopped and released. `localStorage` and
  `sessionStorage` hold 256K characters each and last only as long as the page, so nothing reaches the disk.
- A page can't crash the game. Any error, stack overflow and out-of-memory included, stops only that page, which
  then shows its error. It can write at most 50 log lines a second, each cut to 4096 characters.
- Pages load stylesheets, scripts, images and fonts through Minecraft's resource manager and nothing else. A URL with
  a scheme is a resource id like any other (`https://example.com/a.png` names `assets/https/...`), so no request
  leaves the game, no third party learns the player's IP, and no file outside the resource packs is read. In a
  development environment pages are also read from `common/src/main/resources/assets`, and never from outside it.
- A canvas can only draw other canvases, so `getImageData` can't read the pixels of a texture, a skin or the screen.
  There is no `toDataURL`.
- Shift+Escape always closes a Vellum screen, and the page never sees the key. Escape pressed three times within 1.5
  seconds closes it too, even when the page keeps Escape with `preventDefault()`.
- A server page only replaces nothing, a container screen or another Vellum screen. While the player is in chat, the
  pause menu, options, a sign or any other screen, the page waits until they leave it. A server can't catch keys
  typed into chat, and the pause menu with its Disconnect button stays reachable.
- A server that opens a page within a second of the player closing one with Escape, three times in a row, can't
  open pages for 30 seconds, and the player is told in chat. Opens are rate limited (5 at once, then one a second;
  a waiting page is replaced by a newer one).
- Links to web pages ask first, as chat links do, and only right after a click or key press in the page, so a script
  can't bring the question up again and again. `client.webLinks=block` turns web links off. Other links only load
  `.html` pages from resources.
- `vellum.send` delivers at most 20 messages a second, counted per screen, so reloading the page or following a
  link doesn't reset the count.
- `vellum.playSound` plays only sounds the game knows, at most 8 a second per screen, no louder than
  `client.maxSoundVolume` and then scaled by the player's sound settings.
- While the player types into a server's page, Vellum draws a notice at the bottom of the screen. The page can't
  cover it or tell that it is there.
- Data from a server is capped in size and nesting depth before a script parses it. A malformed packet, in either
  direction, disconnects the sender and crashes nothing.
- `client.serverPages=ask` asks once per server visit before its first page shows; `block` refuses every server
  page. Refused pages are reported closed, so the server's session ends.

## What a page can still do

- Look like anything. With Minecraft's fonts and sprites a page can copy a vanilla screen, a Microsoft sign-in
  form or a "Disconnected" screen with a link. The typing notice is the only tell. Never type a password into a
  Minecraft screen.
- Read what the player types or pastes into its own fields, and send it to the server.
- Learn about the client and send it home: whether a resource exists and an image's size (from layout),
  translations including other mods' (`vellum.t`, `<mc-text key>`, translatable `<mc-text json>`), the player's key
  bindings (`keybind` components), the GUI scale and window size, and `client.reducedMotion`. A server can tell
  from these which mods and resource packs a player has, as vanilla's translatable sign text once allowed.
- Draw any entity the client knows by its network id. The server sent those entities, so this reveals nothing to
  it, but it can show the player entities the game would hide.
- Make the client look up any player name with Mojang for `<player-head name>`, as vanilla player head items do.
- Link to another mod's page (`<a href>`, `vellum.open`). The page stays in the server's session, so its
  `vellum.send` messages go to the server, and `onPageLoad` hooks run for it.
- Use its budgets in full. A page can allocate up to its limits every frame and make the game collect garbage more
  often, navigate or reload itself over and over, and keep Escape. The player can still leave with Shift+Escape.
- Push data as often as it likes. Each push runs the page's listeners within their CPU budget. A server that wants
  to slow a client down has vanilla ways to do it too.

## What mod and server authors must do

On the server:

- Treat every message as hostile. A modified client can send any JSON value on any channel to its own sessions, up
  to the rate limit. Check types (`isJsonPrimitive()` before `getAsString()`), ranges (negative, zero and enormous
  counts; `1e999` parses as infinity) and that the player may act now: still near the block, still holding the
  items, the shop still in stock. Vellum logs a handler's exception and carries on.
- Take ids, prices and names from your own state, not from the message. A message should say what the player
  chose, and the server decides what that costs.
- Don't rely on `onClose` arriving soon. A client may never report a close. Vellum ends a player's oldest session
  once they have `server.maxSessionsPerPlayer` open, and all of them when they leave.
- Messages are parsed strictly (no `NaN`, comments or trailing text), nest at most `server.maxMessageDepth` deep,
  and come with the real sending player. A client can't reach another player's sessions or one that has closed.
- Don't build inline HTML from text players wrote. A name or chat line put into `openInline` markup, `innerHTML` or
  `v-html` can add elements with `onclick` handlers, which run in the viewer's page and can send messages as that
  player. Pass such text in `vellum.data` and show it with `{{ }}` or `textContent`, which never parse HTML.
- Keep `push` data small. The client parses all of it every time.

On the client:

- `VellumScreens.onPageLoad` hooks run for server pages too. Check `driver.serverSession()` before giving the page
  data the server shouldn't have.
- Keep secrets out of pages and `vellum.data`. The player can read whatever you send, and a resource pack can
  replace any page's scripts.
- If your page keeps Escape with a `keydown` listener, give it a close button too. Players can leave with
  Shift+Escape or three Escapes, but few know that.

## Settings

`config/vellum.properties` is read on both sides when the game starts. The first start writes it with every key,
its default and a comment, and later starts add keys it lacks. A missing, malformed or out-of-range value falls back
to its default with a warning in the log; Vellum never fails to start over it. `server.` keys matter on a server
(a dedicated one, or the one inside a singleplayer world), `client.` keys on a client, and `limits.` keys wherever
pages run.

What a server sends and accepts:

| Key | Default | Range | Meaning |
|---|---|---|---|
| `server.maxInlineHtmlChars` | 200000 | 1 to 200000 | Largest page `openInline` sends; larger ones throw. |
| `server.maxDataChars` | 100000 | 1 to 100000 | Largest `vellum.data` `open` and `push` send, as JSON; larger throws. |
| `server.maxMessageChars` | 8192 | 1 to 8192 | Longest message accepted from a page; longer ones are dropped. |
| `server.maxMessageDepth` | 32 | 1 to 255 | Deepest nesting of arrays and objects in a message. |
| `server.messageBurst` | 40 | 1 to 10000 | Messages a session takes at once. |
| `server.messagesPerSecond` | 20 | 0.1 to 10000 | Messages a session takes per second after the burst. |
| `server.maxSessionsPerPlayer` | 8 | 1 to 1024 | Open sessions per player; one more ends the oldest. |

What a client lets pages do:

| Key | Default | Range | Meaning |
|---|---|---|---|
| `client.serverPages` | `allow` | `allow`, `ask`, `block` | Whether pages a server opens show. `ask` asks once per visit. |
| `client.maxInlineHtmlChars` | 200000 | 1 to 200000 | Largest inline page shown. |
| `client.maxDataChars` | 100000 | 1 to 100000 | Largest data accepted from a server. |
| `client.maxDataDepth` | 64 | 1 to 512 | Deepest nesting accepted in data from a server. |
| `client.openBurst` | 5 | 1 to 1000 | Pages a server may open at once. |
| `client.opensPerSecond` | 1 | 0.01 to 1000 | Pages a server may open per second after the burst. |
| `client.messageBurst` | 20 | 1 to 10000 | Messages a page may send at once, per screen. |
| `client.messagesPerSecond` | 20 | 0.1 to 10000 | Messages a page may send per second, per screen, across reloads. |
| `client.forceCloseKey` | `key.keyboard.escape` | a key name | With Shift, closes any Vellum screen. Names as in `options.txt`. |
| `client.forceClosePresses` | 3 | 2 to 10 | Escapes within 1.5 s that close a page which keeps Escape. |
| `client.reopenStrikes` | 3 | 1 to 100 | Reopens in a row that stop a server's pages. |
| `client.reopenBlockSeconds` | 30 | 1 to 3600 | How long they stay stopped. |
| `client.soundsPerSecond` | 8 | 0 to 1000 | Sounds a page may play per second (and at once), per screen; 0 mutes pages. |
| `client.maxSoundVolume` | 1 | 0 to 1 | Loudest volume a page may ask for. |
| `client.webLinks` | `ask` | `ask`, `block` | Web links: confirm first (after a click or key press), or never open. |
| `client.typingNotice` | `true` | `true`, `false` | The notice while typing into a server's page. |
| `client.reducedMotion` | `false` | `true`, `false` | Pages see `prefers-reduced-motion: reduce`. |

The engine's caps on every page, a mod's or a server's. Each must be at least 1, and `heapLimitPercent` at most
100 (100 turns the heap check off). They are fields of `dev.vellum.engine.Limits`, whose `describe` gives each
key's comment; a host can return its own from `Host.limits()`.

| Key | Default | Meaning |
|---|---|---|
| `limits.instructionBudget` | 50000000 | Instructions one script call may run. |
| `limits.timeBudgetMs` | 1000 | Wall-clock ms one script call may take. |
| `limits.loadTimeBudgetMs` | 10000 | The same while the page loads. |
| `limits.maxStackDepth` | 1000 | Nested script calls. |
| `limits.maxBudgetOverruns` | 3 | Calls stopped by a budget before the page stops. |
| `limits.frameScriptTimeMs` | 100 | Timer and frame-callback ms per frame; the rest wait a frame. |
| `limits.slowFrameMs` | 200 | A frame slower than this counts as slow. |
| `limits.maxSlowFrames` | 25 | Slow frames in a row before the page stops. |
| `limits.entryAllocation` | 268435456 | Bytes one script call may allocate (256 MiB). |
| `limits.heapLimitPercent` | 90 | Heap use after a collection that stops a running page. |
| `limits.maxStringLength` | 16777216 | Characters from `repeat`, `padStart`, `padEnd`, `replace`, `join`. |
| `limits.maxArrayLength` | 1048576 | Arrays built-ins iterate, `apply` arguments, pieces of `split` and `match`. |
| `limits.maxBufferBytes` | 16777216 | Bytes of an `ArrayBuffer` or typed array. |
| `limits.maxBigIntBits` | 65536 | Bits of a BigInt (for the whole game). |
| `limits.maxTimers` | 10000 | Pending timers and animation frames. |
| `limits.maxMarkupLength` | 1048576 | Characters `innerHTML`, `outerHTML`, `insertAdjacentHTML` and `v-html` take. |
| `limits.storageQuota` | 262144 | Characters in each of `localStorage` and `sessionStorage`. |
| `limits.maxLogLength` | 4096 | Characters of one console message. |
| `limits.logRate` | 50 | Log lines a second. |
| `limits.maxForItems` | 10000 | Items of one `v-for`. |
| `limits.maxTemplatePasses` | 10 | Template passes per frame. |
| `limits.maxNodes` | 100000 | Nodes in a page. |
| `limits.maxDepth` | 512 | Element nesting, and JSON nesting in `JSON.parse`. |
| `limits.maxCssNesting` | 32 | Nested CSS functions and blocks. |
| `limits.maxSelectorParts` | 256 | Parts of one selector. |
| `limits.maxListItems` | 64 | Items of a shadow list. |
| `limits.maxGridTracks` | 100000 | Tracks of a grid track list. |
| `limits.maxVarLength` | 65536 | Characters of a value after `var()` substitution. |
| `limits.maxCanvasSize` | 2048 | Pixels on each side of a canvas. |
| `limits.maxCanvasPixels` | 16777216 | Pixels of all of a page's canvases. |

`client.serverPages` defaults to `allow`. Server UIs are what Vellum is for, vanilla lets servers open container
screens without asking, and the protections above work without the player's help. Players who want a say can set
`ask`.
