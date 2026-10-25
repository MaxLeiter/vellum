# Minecraft versions

[All docs](index.md)

Vellum builds for three Minecraft versions from one source tree, with [Stonecutter](https://stonecutter.kikugie.dev):

| Minecraft | NeoForge | Fabric | Java |
|---|---|---|---|
| 26.3 | 26.3.0.26-beta | Loader 0.19.5, Fabric API 0.161.0+26.3 | 25 |
| 26.2 | 26.2.0.88 | Loader 0.19.5, Fabric API 0.161.0+26.2 | 25 |
| 1.21.1 | 21.1.255 | Loader 0.19.5, Fabric API 0.116.17+1.21.1 | 21 |

Each version has its own Gradle projects, named after it: `:common:1.21.1`, `:neoforge:1.21.1`, `:fabric:1.21.1`.
Their settings are in `stonecutter.properties.toml`. The engine, Rhino and the previewer are built once and shared.
The jars are `vellum-<loader>-<minecraft>-<version>.jar` (`vellum-neoforge-1.21.1-0.3.0.jar`) in
`<loader>/versions/<minecraft>/build/libs/`, and a release publishes
`dev.vellum:vellum-{common,neoforge,fabric}-<minecraft>` for every version at the same Vellum version.

The sources are written for 26.3. Where 1.21.1 differs, Stonecutter comments (`//? if >=26 {`) pick the code, and where 26.2 differs from 26.3 finer ones (`//? if >=26.3 {`, `#? if >=26.3 {` in the access widener and
transformer). Renames such as `Identifier`/`ResourceLocation` are replacements in `stonecutter.gradle`. Files that
1.21.1 needs written differently live in `common/versions/1.21.1/src`: `McGui` (drawing), `McClient` (screens and
input), `Scene` and `EntityPortrait` (3D), `ModelContent`, `PlayerHeadContent`, `McSurface`, `VellumEntities`,
`GameTests`, and the input event records. Their 26.x versions in `common/src` are wrapped in `//? if >=26 {`, so
1.21.1 compiles only its own. Keep committed sources on 26.3: if you switch Stonecutter's active version to work on
1.21.1, switch back before committing.

Everything works on 26.2, except that the dev tour (`-Ptour`) is 26.3 only. 26.2 is the 26.3 code with these
differences, which are `>=26.3` comments and the `blaze3d`/`renderpearl` rename in `stonecutter.gradle`:

- The render API is `com.mojang.blaze3d.pipeline.RenderPipeline` and `com.mojang.blaze3d.textures.FilterMode`/`GpuTextureView`; 26.3 moved them to `com.mojang.renderpearl.api.*`.
- Keys are GLFW's, not SDL's: `KeyEvent(key, scancode, modifiers)` holds a GLFW key code, as on 1.21.1, and `KeyNames` is the GLFW one. Mouse buttons are GLFW's (0 left, 1 right, 2 middle); there is no `onTextInputFocusChange`.
- `PoseStack` has `mulPose(Quaternionfc)` but no `rotate` or `rotateDegrees`; `SubmitNodeCollection` has a no-argument constructor and `submitModel` takes a sprite and a crumbling overlay where 26.3 takes a `UvMapping`.
- `GuiGraphicsExtractor.setTooltipForNextFrame` has no `replaceExisting` overload with an item style (NeoForge's takes the stack and style only); `ScreenNarrationCollector.update` has no `NarrationTrigger`.
- `MouseHandler.onButton`, `onScroll`, `onMove` and `KeyboardHandler.keyPress`, `charTyped` are private: they are widened (`#? if <26.3`), and `PictureInPictureRenderer.textureView` is widened with the `blaze3d` type.
- The shape shader's vertices are packed differently. 26.2's `BufferBuilder` writes only `Position`, `Color`, `UV0`, `UV1`, `UV2`, `Normal` and `LineWidth` (no `UV3`), so on 26.2 `SdfRenderState` grows the box by the same margin on every side and sends the margin in `Position.z`; the shader works out the half size from that. A shadow's spread travels as the bits of a float in `UV2`. The 26.2 pipelines define `VELLUM_26_2`, which picks the 26.2 parts of `sdf_box.vsh` and `.fsh`: `#moj_import` instead of `#include`, and attributes and varyings without `layout(location)`, since 26.2 matches them by name. A pipeline that fails to compile stays failed until resources reload, so the retry in `SdfRenderState.available()` only helps after one.
- The dev tour is off: `DevTour` and `TourCursor` are stubs (their `else` blocks), since the tour hides and reads the system cursor through SDL.

Everything works on 1.21.1, with these differences:

- Rounded boxes, borders and box-shadows are drawn as quads, without the shape shader.
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
   comments (`//? if >=26.3 {`) or renames in `stonecutter.gradle`. Only a version that needs a file written
   differently throughout gets its own copy in `common/versions/<minecraft>/src`, as 1.21.1 does; wrap the shared
   file in a condition that leaves it out for that version. The access transformer and access widener are shared by every version, with `#? if` blocks
   for what one version needs (a disabled block's lines are commented with `##`, not `#`: the widener parser rejects
   the leading space a single `#` leaves behind). Check the mod metadata too: NeoForge 26.x needs `iconFile` instead
   of `logoFile` in `neoforge.mods.toml` (`neoforge_logo_key`), since NeoForge's deprecation warning screen stops the
   client at startup. Typical breakage between neighbouring 26.x versions: package moves (`blaze3d` to `renderpearl`),
   private input handlers that need widening, and changed signatures in tooltip and model submission code;
   26.2 is a worked example (the `>=26.3` comments and the `renderpearl` rename in `stonecutter.gradle`).
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
