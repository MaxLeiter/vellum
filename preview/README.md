# Vellum previewer

Renders a Vellum page in a window without launching Minecraft. It uses the engine, Java2D, and Minecraft's own font
glyphs and GUI sprites, which it reads from your Minecraft jar. It is also where the snapshot tests live.

## Running it

```sh
./gradlew :preview:run --args="path/to/page.html"
./gradlew :preview:run --args="path/to/page.html --scale 3 --size 427x240 --data data.json"
./gradlew :preview:run --args="--canvas-test"
```

Paths are relative to the repository root. In the window:

| Key | Does |
|---|---|
| F5 | Reload (the page also reloads when it or a file it loaded changes) |
| F12 | Inspector: hover an element to see its margin, border, padding and content boxes |
| 1-4 | GUI scale (ignored while a text field has focus) |
| Ctrl/Cmd+S | Save a PNG of the window to the working directory |

`--data` pushes a JSON file to the page as its data after loading.

`--narrate` prints what Minecraft's narrator would be given, after each frame: the page's title once it loads, what
its live regions announce (`live:`, or `at once:` for assertive ones), and the focused and hovered elements when what
they read changes, in plain English (`pointer: Reply 1: About the letter, button`). The game phrases them as vanilla
phrases its widgets and waits for the pointer to rest; the previewer prints at once.

Headless snapshot: `--snapshot out.png [--frames N]` renders N frames, 16 ms apart, and writes the last one. The
exit code is 1 if the page failed. `--canvas-test` draws a fixed sheet of canvas primitives without the engine
(text styles, nine-sliced sprites, textures, items, gradients, clipping, rotation, alpha), to check the renderer
against the game.

If the engine throws, the window shows the exception instead of the page and keeps running. Save a fix to reload.

On macOS the JDK checks for file changes every two seconds, so reloads can lag by up to that long.

## Scripted input

`--actions actions.txt` drives the page with a script instead of opening a window, and saves screenshots along the
way. It is handy for checking hover states, menus, typing and scrolling without clicking through them by hand.

```sh
./gradlew :preview:run --args="ui/shop.html --actions shop-actions.txt --out build/shots"
```

The file has one action per line; lines starting with `#` are comments. Frames run 16 ms apart from t = 0, as in a
snapshot, and every input action is followed by one frame, so the next action and the next shot see its effect.
A target is a point in GUI pixels (`120 40`) or a CSS selector, which aims at the first matching element as it is
painted (after scrolling and transforms): at the centre of the part of it that shows, cut to its scroll containers
and the window, as `VellumAutomation` aims in game. When none of it shows, the previewer first scrolls its scroll
containers to bring it into view. An element covered by another one at that point is an error.

| Action | Does |
|---|---|
| `wait <frames>` | Runs that many frames (timers, animations, transitions). |
| `move <target>` | Moves the pointer there (hover). |
| `click <target>` | Moves there, presses and releases the left button. |
| `wheel <target> <px>` | Turns the wheel over the target; positive scrolls down. A notch is 24 px. |
| `key <key>` | Presses and releases a key by its DOM name (`Enter`, `ArrowDown`, `Escape`, `a`), with modifiers joined by `+` (`Shift+Tab`, `Ctrl+a`). |
| `type <text>` | Types the rest of the line, a key press per character. |
| `shot <name>` | Writes the current frame to `<name>.png`. |
| `bench <frames>` | Runs that many frames and prints the time a frame took. |

```
# open the dropdown, pick the second option, and show the result
wait 30
click #mode
shot open
key ArrowDown
key Enter
wait 10
shot picked
```

Shots go to `--out`, else next to `--snapshot` (which also gets the last frame), else the working directory. The
exit code is 1 if the page failed and 2 if the script is malformed or a selector matches nothing shown; the message
names the line.

## Minecraft assets

Assets are read straight from a Minecraft 26.3 client jar and never copied into the repository. The jar is looked up
in this order:

1. `-Dvellum.mcJar=/path/to/client.jar` or the `VELLUM_MC_JAR` environment variable;
2. `vanilla-26.3-*.jar` in `common/versions/26.3/build/moddev/artifacts/` or `common/build/moddev/artifacts/` (this
   repository after a build, or a parent directory's);
3. the launcher's `versions/26.3/26.3.jar` in `~/Library/Application Support/minecraft`, `~/.minecraft` or
   `%APPDATA%/.minecraft`.

Without a jar, text uses a Java2D font with Minecraft's ASCII advances (layout matches the game), and textures and
sprites draw as the magenta and black missing texture.

A page under `<root>/assets/<ns>/...` is addressed as `ns:path`, and `<root>` is searched before the jar, so the
page's `ns:` stylesheets, scripts and textures resolve as they do in game. Other pages use plain file paths.

## Snapshot tests

```sh
./gradlew :preview:test                                 # compare with the goldens in src/test/snapshots
./gradlew :preview:test -Dvellum.updateSnapshots=true   # accept the current output as the new goldens
```

On a mismatch, the actual image and a diff (differing pixels in red) are written to `preview/build/snapshots/`.
Tests that need Minecraft's assets are skipped when no jar is found. Page snapshots cover
`src/test/resources/pages/*.html` and the demo UIs, and they are skipped while the engine still throws
`UnsupportedOperationException`.
