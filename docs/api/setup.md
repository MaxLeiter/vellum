# Setting up

How to depend on Vellum, and which parts of its API are stable. [All docs](../index.md)

## Depending on Vellum

To start a new mod, copy one of the [templates](../../template/README.md). Otherwise, Vellum is on
[maven.maxleiter.com](https://maven.maxleiter.com):

```groovy
repositories {
    maven {
        url = "https://maven.maxleiter.com"
        content { includeGroup("dev.vellum") }
    }
}

// common/ (compiles against vanilla): the API, plus the engine types it exposes
dependencies { compileOnly("dev.vellum:vellum-common-26.3:0.5.1") }

// neoforge/ and fabric/: the loader jar, so dev runs load Vellum as a mod (it bundles the engine and Rhino)
dependencies { implementation("dev.vellum:vellum-neoforge-26.3:0.5.1") }   // or vellum-fabric-26.3
```

To build against an unreleased Vellum, run `./gradlew publishToMavenLocal` in a Vellum checkout and add
`mavenLocal()` to your repositories.

Each Minecraft version has its own artifacts, all at the same Vellum version:

| Minecraft | Common | NeoForge | Fabric |
|---|---|---|---|
| 26.3 | `dev.vellum:vellum-common-26.3` | `dev.vellum:vellum-neoforge-26.3` | `dev.vellum:vellum-fabric-26.3` |
| 26.2 | `dev.vellum:vellum-common-26.2` | `dev.vellum:vellum-neoforge-26.2` | `dev.vellum:vellum-fabric-26.2` |
| 1.21.1 | `dev.vellum:vellum-common-1.21.1` | `dev.vellum:vellum-neoforge-1.21.1` | `dev.vellum:vellum-fabric-1.21.1` |

On 1.21.1 the Fabric jar is remapped to intermediary names like any Fabric mod for an obfuscated version: depend on it
with `modImplementation`, and compile against Mojang's mappings, as `vellum-common-1.21.1` is. The API is the same on
every version, except:

- `VellumEntities.registerPortraitState` is 26.3 only (1.21.1 has no entity render states; see
  [Entity render states](elements.md#entity-render-states)).
- `DocumentDriver.onKey` handlers get Vellum's `dev.vellum.mod.client.input.KeyEvent` on 1.21.1, a record with
  the shape of 26.3's `net.minecraft.client.input.KeyEvent` (`key()`, `modifiers()`, `isEscape()`,
  `hasShiftDown()`...) whose `key()` is a GLFW key code and whose `scancode()` replaces `keycode()`.
- On 26.2 the input events are Minecraft's own, as on 26.3, but 26.2 uses GLFW: `KeyEvent.key()` is a GLFW key code and
  `scancode()` replaces 26.3's `keycode()`; its render API is in `com.mojang.blaze3d.*` where 26.3's is in
  `com.mojang.renderpearl.api.*`.
- `VellumScreen` and `VellumContainerScreen` override 1.21.1's screen methods (`render`, `mouseClicked(double, double,
  int)`...), so subclasses override those there.

Make Vellum a required dependency: `[[dependencies.<modid>]] modId="vellum" type="required"` in `neoforge.mods.toml`,
`"depends": {"vellum": ">=0.5.1"}` in `fabric.mod.json`, and a required dependency on your Modrinth and CurseForge
pages, so launchers install it with your mod. Don't bundle Vellum in your jar (`jarJar` or `include`). Players then
get Vellum fixes without waiting for every mod that bundles it, and a modpack has one copy.

If your mod works without Vellum, make it optional instead (`type="optional"`, `"suggests"`), and:

- Check that Vellum is loaded before touching `dev.vellum` classes:
  - NeoForge: `ModList.get().isLoaded("vellum")`
  - Fabric: `FabricLoader.getInstance().isModLoaded("vellum")`
- Put your Vellum-facing code in a separate class. It is then only class-loaded when Vellum is present, and you can
  fall back to a vanilla screen otherwise.

Either way:

- Server calls run on the server thread; client calls run on the render thread.
- A page URL is a resource id: `mymod:vellum/shop.html` is
  `assets/mymod/vellum/shop.html` in your jar (or in a resource pack, which can restyle your UI). Stylesheets,
  scripts and images referenced from a page resolve relative to it, so `<link rel="stylesheet" href="shop.css">`
  loads `assets/mymod/vellum/shop.css`.

Vellum's payloads are optional on both loaders: a server with Vellum accepts clients without it, and the other way
round. `VellumServer.open` then returns a session that is already closed.

## Stability

The API is `dev.vellum.mod.server.VellumServer`, `VellumSession`, the settings file's keys, and `dev.vellum.mod.client.VellumScreens`,
`VellumScreen`, `VellumContainerScreen`, `VellumHud`, `VellumEntities`, `VellumAutomation` and `DocumentDriver`'s
public methods. Other classes are
internal. Vellum is at 0.x: expect changes, which will be listed in the changelog.
