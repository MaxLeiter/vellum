# Minecraft versions

[All docs](index.md)

Vellum builds for two Minecraft versions from one source tree, with [Stonecutter](https://stonecutter.kikugie.dev):

| Minecraft | NeoForge | Fabric | Java |
|---|---|---|---|
| 26.3 | 26.3.0.26-beta | Loader 0.19.5, Fabric API 0.161.0+26.3 | 25 |
| 1.21.1 | 21.1.255 | Loader 0.19.5, Fabric API 0.116.17+1.21.1 | 21 |

Each version has its own Gradle projects, named after it: `:common:1.21.1`, `:neoforge:1.21.1`, `:fabric:1.21.1`.
Their settings are in `stonecutter.properties.toml`. The engine, Rhino and the previewer are built once and shared.
The jars are `vellum-<loader>-<minecraft>-<version>.jar` (`vellum-neoforge-1.21.1-0.3.0.jar`) in
`<loader>/versions/<minecraft>/build/libs/`, and a release publishes
`dev.vellum:vellum-{common,neoforge,fabric}-<minecraft>` for both versions at the same Vellum version.

The sources are written for 26.3. Where 1.21.1 differs, Stonecutter comments (`//? if >=26 {`) pick the code, and
renames such as `Identifier`/`ResourceLocation` are replacements in `stonecutter.gradle`. Code that differs a lot
lives in one file per version under `common/versions/<minecraft>/src`: `McGui` (drawing), `McClient` (screens and
input), `KeyNames`, `Scene` and `EntityPortrait` (3D), `GameTests`. Keep committed sources on 26.3: if you switch
Stonecutter's active version to work on 1.21.1, switch back before committing.

Everything works on 1.21.1, with these differences:

- `<entity>` and `<model>` can't fade: under half opacity they aren't drawn, as items aren't on either version. `-mc-tint` works.
- `VellumEntities.registerPortraitState` doesn't exist, since 1.21.1 has no entity render states. Entity `components`
  are ignored, and `variant` and `color` are set through the entity's NBT.
- The CSS `cursor` shows over screens only, not over HUD overlays.
- Fabric draws HUD overlays after the whole HUD, since 1.21.1 has no HUD layers.
- Key events are GLFW's: `DocumentDriver.onKey` handlers get `dev.vellum.mod.client.input.KeyEvent` with GLFW key codes.
- The dev tour (`-Ptour`) is 26.3 only.

## Adding a Minecraft version

1. Add the version to `minecraftVersions` in `settings.gradle`.
2. Add a `["<version>"]` table to `stonecutter.properties.toml` with the same keys as the others. NeoForge and
   NeoForm versions are on [projects.neoforged.net](https://projects.neoforged.net/neoforged/neoforge), Fabric's on
   [fabricmc.net/develop](https://fabricmc.net/develop/). Fabric picks its Loom plugin itself: plain Loom for 26.x,
   the remapping one for older, obfuscated versions.
3. Make it compile: `./gradlew :common:<version>:compileJava`, then the loaders. Small differences are Stonecutter
   comments (`//? if >=26.3 {`) or renames in `stonecutter.gradle`. For the per-version files (`McGui`, `McClient`,
   `KeyNames`, `Scene`, `EntityPortrait`, `GameTests`), copy the nearest version's `common/versions/<minecraft>/src`
   and fix what changed. The access transformer and access widener are shared by every version.
4. `./gradlew build` builds and tests every version on both loaders, including the GameTests. Then look at the
   demos in the game: `./gradlew :neoforge:<version>:runClient -Pautopilot` and the same for `:fabric:<version>`.
   On macOS, copy `options.txt` (with `enableVsync:false`) and `config/fml.toml` (with `earlyWindowControl = false`)
   from `neoforge/versions/26.3/runs/client` into the new version's run directory first, or the client hangs.
5. Add the version to the table at the top of this file, list anything that works differently on it, and add a line
   to `CHANGELOG.md` under the next version.

Commit with the sources on 26.3. Publishing needs nothing more. CI and the release workflow build every version in
`settings.gradle`. A release publishes each version's artifacts to maven.maxleiter.com, uploads its jars to Modrinth
and CurseForge tagged with its Minecraft version, and lists them in the GitHub release. If one jar also runs on other
Minecraft versions, list them all in that version's table as `publish_minecraft_versions = "26.2, 26.2.1"`.
