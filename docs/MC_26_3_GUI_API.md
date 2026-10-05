# Minecraft 26.3 client GUI API notes

Notes for the Minecraft backend in `common/`. Checked against vanilla 26.3 (NeoForm 26.3-1), NeoForge 26.3.0.26-beta and Fabric API 0.161.0+26.3. Working examples live in Chronicle (`~/Documents/mod/common/src/main/java/dev/chronicle/mod/client/`) and claudemons (`~/Documents/claudemons/common/src/main/java/dev/claudemons/mod/client/AutomatonScreen.java`).

Decompiled vanilla sources: unzip `~/Documents/mod/common/build/moddev/artifacts/vanilla-26.3-1-sources.jar` (after the first build, also `common/build/moddev/artifacts/` in this repo). NeoForge-patched sources: `~/Documents/mod/neoforge/build/moddev/artifacts/minecraft-patched-26.3.0.26-beta-sources.jar`.

## 0. What changed in 26.x
- `GuiGraphics` is now **`net.minecraft.client.gui.GuiGraphicsExtractor`**. Drawing is *extraction*: every call records a render-state object into a `GuiRenderState`; `GuiRenderer` batches and draws later.
- `render(...)` is now `extractRenderState(GuiGraphicsExtractor, int mouseX, int mouseY, float a)` on `Renderable`/`Screen`. `renderBackground` is `extractBackground`; `renderLabels`/`renderSlot` are `extractLabels`/`extractSlot`.
- `ResourceLocation` is now **`net.minecraft.resources.Identifier`**: `fromNamespaceAndPath`, `withDefaultNamespace`, `parse`, `tryParse`.
- GPU API: `com.mojang.renderpearl.api.*` (`RenderPipeline`, `GpuTextureView`, `GpuSampler`, `GpuFormat`, `PrimitiveTopology`, `FilterMode`). OpenGL and Vulkan backends; GLSL compiled to SPIR-V. `com.mojang.blaze3d.*` still has `RenderSystem`, `NativeImage`, `VertexConsumer`, `PoseStack`, `Window`, `InputConstants`.
- **Windowing and input use SDL3** (GLFW is gone). Key codes are SDL scancodes (`KEY_ESCAPE=41`, `KEY_RETURN=40`). Mouse buttons are `LEFT=1`, `MIDDLE=2`, `RIGHT=3`. Modifier masks: `MOD_SHIFT=3`, `MOD_CONTROL=192`, `MOD_ALT=768`.
- Screens: `minecraft.gui.setScreen(screen)` and `minecraft.gui.screen()`. There is no `Minecraft.setScreen`. On NeoForge, `Screen.onClose()` calls `minecraft.gui.popScreenLayer()`.

## 1. GuiGraphicsExtractor
`new GuiGraphicsExtractor(Minecraft, GuiRenderState, int mouseX, int mouseY)`. `Gui.extractRenderState` creates one per frame. `pose` is a `Matrix3x2fStack(16)`.

```java
Matrix3x2fStack pose()              // org.joml: pushMatrix()/popMatrix()/translate(float,float)/scale(float,float)/rotate(float rad)
int guiWidth(); int guiHeight();
void nextStratum();                 // new top-level layer: everything after draws above everything before
void blurBeforeThisStratum();       // menu blur; throws if called twice per frame
void enableScissor(int x0,int y0,int x1,int y1); // transformed by pose (axis-aligned bbox), intersected with parent
void disableScissor();
boolean containsPointInScissor(int x,int y);
void requestCursor(CursorType);     // applied once at end of frame
```
- **Pose stack depth is limited to about 15 pushes.** Don't push once per DOM element. Keep your own matrix stack and `pose().set(...)` it, or push one level and set the matrix for each draw.
- **Colours are ARGB ints.** Helpers are in `net.minecraft.util.ARGB` (`color`, `multiplyAlpha`, `alpha/red/green/blue`, `srgbLerp`...).
- `text(...)` is skipped when alpha is 0. **There is no group opacity**: multiply alpha into every element. **Items cannot be tinted or faded**; they are blitted from the item atlas.

### Rectangles
```java
void fill(int x0,int y0,int x1,int y1,int col)                       // RenderPipelines.GUI
void fill(RenderPipeline pipeline,int x0,int y0,int x1,int y1,int col)
void fillGradient(int x0,int y0,int x1,int y1,int colTop,int colBottom) // vertical only
void outline(int x,int y,int width,int height,int color)
void horizontalLine(int x0,int x1,int y,int col); void verticalLine(int x,int y0,int y1,int col);
void textHighlight(int x0,int y0,int x1,int y1, boolean invertText)
```
- Coordinates are ints. For sub-pixel positions, translate or scale the pose with floats.
- `fill` emits its vertices as (x0,y0), (x0,y1), (x1,y1), (x1,y0).
- **GUI pipelines cull back faces.** A mirrored pose (negative scale) culls quads. Custom pipelines should use `.withCull(false)`.

### Textures and sprites
```java
void blit(RenderPipeline p, Identifier tex, int x,int y, float u,float v, int w,int h, int texW,int texH[, int color])
void blit(RenderPipeline p, Identifier tex, int x,int y, float u,float v, int w,int h, int srcW,int srcH, int texW,int texH[, int color])
void blit(Identifier tex, int x0,int y0,int x1,int y1, float u0,float u1,float v0,float v1)      // GUI_TEXTURED, normalised UVs
void blit(GpuTextureView view, GpuSampler sampler, int x0,int y0,int x1,int y1, float u0,float u1,float v0,float v1)
void blitSprite(RenderPipeline p, Identifier sprite, int x,int y,int w,int h[, float alpha | int color])
void blitSprite(RenderPipeline p, Identifier sprite, int spriteW,int spriteH,int texX,int texY,int x,int y,int w,int h[, int color])
```
- `blitSprite(Identifier...)` handles nine-slice and tiling automatically. It reads `gui.scaling` from the sprite's `.mcmeta`: `stretch`, `tile{width,height}`, or `nine_slice{width,height,border,stretch_inner}`. Example, `widget/button.png.mcmeta`: `{"gui":{"scaling":{"type":"nine_slice","width":200,"height":20,"border":3}}}`.
- Sprites in `assets/<ns>/textures/gui/sprites/<path>.png` join the GUI atlas as `Identifier(ns, "<path>")`.
- `.png` textures sample with REPEAT + NEAREST unless their `.mcmeta` says `{"texture":{"blur":true,"clamp":true}}`. So `background-repeat` can blit with u/v beyond 1.
- Pipelines: `RenderPipelines.GUI_TEXTURED`, `GUI_TEXTURED_PREMULTIPLIED_ALPHA`, `GUI` (untextured).
- Texture ids: `TextureManager.getTexture(Identifier)` auto-loads `ns:textures/....png`.

### Text
```java
void text(Font f, String s, int x,int y,int color[, boolean dropShadow])   // default shadow = true
void text(Font f, FormattedCharSequence|Component s, int x,int y,int color[, boolean dropShadow])
void centeredText(...); int textWithWordWrap(...); void textWithBackdrop(...)
```

### Items, entities
```java
void item(ItemStack s, int x,int y[, int seed]); void fakeItem(ItemStack s,int x,int y)
void itemDecorations(Font f, ItemStack s, int x,int y[, String countText])   // count, durability, cooldown
void entity(EntityRenderState rs, float scale, Vector3fc translation, Quaternionfc rotation,
            @Nullable Quaternionfc overrideCameraAngle, int x0,int y0,int x1,int y1)
```
Items are 16×16. They are pre-rendered into `GuiItemAtlas` at 16×guiScale px and blitted with the pose, so a scale above 1 pixelates.

### Tooltips (deferred, drawn on top at frame end)
```java
setTooltipForNextFrame(Component c, int x,int y)
setTooltipForNextFrame(Font f, ItemStack s, int x,int y)
setComponentTooltipForNextFrame(Font f, List<Component> lines, int x,int y)
```

### Draw order
`GuiRenderState` is a list of **strata**, each a chain of nodes. A new element goes one node above the highest node holding something its bounds intersect. Within a node, elements are re-sorted by (scissor, pipeline, texture) for batching. Painter's order therefore holds only for **overlapping** elements, which is the only case where order is visible. Call `nextStratum()` to force "everything after is on top", e.g. for dropdowns, modals and tooltips.

**An element whose `bounds()` is null is DROPPED.**

### Custom render elements (arbitrary quads, custom shaders)
```java
public interface GuiElementRenderState extends ScreenArea {      // ScreenArea: @Nullable ScreenRectangle bounds();
    void buildVertices(VertexConsumer vertexConsumer);
    RenderPipeline pipeline();
    TextureSetup textureSetup();
    @Nullable ScreenRectangle scissorArea();
}
// vanilla: ColoredRectangleRenderState, BlitRenderState
vertexConsumer.addVertexWith2DPose(pose, x, y).setUv(u, v).setColor(argb);
```
- `TextureSetup.noTexture()`, `TextureSetup.singleTexture(GpuTextureView, GpuSampler)`.
- Samplers: `RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR|NEAREST)`, `getRepeat(FilterMode)`.
- Bounds: `new ScreenRectangle(x, y, w, h).transformMaxBounds(pose)`, intersected with the scissor. They must be non-null.

**Submitting a custom element.** `guiRenderState` is private in vanilla:
- **NeoForge** adds `g.submitGuiElementRenderState(state)`, `g.submitPictureInPictureRenderState(state)` and `g.peekScissorStack()`.
- **Fabric**'s transitive access widener exposes `g.guiRenderState` (`addGuiElement`, `addPicturesInPictureState`) and `g.scissorStack`.
- **Common code**: use a mixin `@Accessor` for `guiRenderState` and `scissorStack` (both loaders ship Mixin), or a loader service.

For tessellated geometry, use `RenderPipelines.GUI`, which is `POSITION_COLOR` with QUADS topology. Each quad is 4 vertices, and a triangle is a quad with its last vertex repeated.

**GuiRenderer.** A new draw starts whenever pipeline, scissor or TextureSetup changes. Only `Sampler0..2`, `DynamicTransforms`, `Projection`, `Fog`, `Globals` and `Lighting` are bound, so there are **no custom uniforms**. Per-element shader parameters must travel as vertex attributes. `BufferBuilder` can write `Position`, `Color`, `UV0` (RG32F), `UV1`/`UV2` (RG16 SINT), `UV3` (RG32F, `setUv3`), `Normal` and `LineWidth`, all bound by name. Projection is `setupOrtho(..., width/guiScale, height/guiScale)`, so GUI units are scaled px.

**Custom pipelines (for example an SDF rounded rect).**
```java
RenderPipeline.builder()
  .withLocation(Identifier.fromNamespaceAndPath(MODID, "pipeline/sdf_rect"))
  .withBindGroupLayout(BindGroupLayouts.GLOBALS).withBindGroupLayout(BindGroupLayouts.PROJECTION)
  .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
  .withVertexShader(Identifier.fromNamespaceAndPath(MODID, "core/sdf_rect"))     // assets/<ns>/shaders/core/sdf_rect.vsh
  .withFragmentShader(Identifier.fromNamespaceAndPath(MODID, "core/sdf_rect"))
  .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
  .withVertexBinding(0, FORMAT).withPrimitiveTopology(PrimitiveTopology.QUADS).withCull(false).build();
```
- Shaders are GLSL `#version 330` with `#extension GL_ARB_separate_shader_objects : require`. Varyings need explicit `layout(location=N)`. Includes use `#include <minecraft:dynamictransforms.glsl>`.
- Registration:
  - NeoForge: `RegisterRenderPipelinesEvent.registerPipeline`.
  - Fabric: `RenderPipelines.register` via the access widener.
  - Unregistered pipelines also compile lazily on first use.

## 2. Text and fonts
`Font`:
- `lineHeight = 9`
- `int width(String|FormattedText|FormattedCharSequence)` (ceil)
- `List<FormattedCharSequence> split(FormattedText, int maxWidth)`
- `plainSubstrByWidth`
- `StringSplitter getSplitter()`, whose methods are:
  - `float stringWidth(...)`
  - `int plainIndexAtWidth(String, int maxWidth, Style)`
  - `findLineBreak`
  - `splitLines`

Style (`net.minecraft.network.chat.Style`, immutable `with*`):
- `withColor(int rgb)`, `withShadowColor(int argb)`, `withoutShadow()`
- `withBold/withItalic/withUnderlined/withStrikethrough/withObfuscated(Boolean)`
- `withFont(FontDescription)`

Build text with `Component.literal(s).withStyle(style)`. Bold widens glyphs, so measure with the same style.

Fonts: `new FontDescription.Resource(Identifier)` (`FontDescription.DEFAULT` = `minecraft:default`). Vanilla ships `default`, `uniform`, `alt` and `illageralt`, defined by `assets/<ns>/font/<name>.json` (provider types `bitmap`, `ttf`, `space`, `unihex`, `reference`).

Shadow: `ARGB.scaleRGB(color, 0.25)`, offset 1px. A style colour replaces RGB but keeps the alpha passed to `text`.

Scaled text:
```java
g.pose().pushMatrix(); g.pose().translate(x, y); g.pose().scale(s, s); g.text(font, str, 0, 0, color, false); g.pose().popMatrix();
```

## 3. Picture-in-picture (entities in the GUI)
- `PictureInPictureRenderer<T extends PictureInPictureRenderState>` renders into an offscreen texture and blits it.
- Registration:
  - NeoForge: `RegisterPictureInPictureRenderersEvent.register(Class<T>, Supplier<...>)`, then `g.submitPictureInPictureRenderState`.
  - Fabric: `PictureInPictureRendererRegistry.register(ctx -> ...)`.
- Entity helper: `InventoryScreen.extractEntityInInventoryFollowsMouse(GuiGraphicsExtractor g, int x0,int y0,int x1,int y1, int size, float offsetY, float mouseX, float mouseY, LivingEntity e)`.
- Custom portrait: Chronicle `DialogueScreen.entityPortrait` (~L672). It builds the `EntityRenderState` and calls `g.entity(state, scale, translation, rotation, tilt, x0,y0,x1,y1)`.
- How a picture is drawn (`PictureInPictureRenderer.prepare`): a texture of `(x1-x0)*guiScale × (y1-y0)*guiScale` px, ortho projection, pose `translate(w/2, getTranslateY(h))` then `scale(guiScale*state.scale(), same, -same)`, so a unit is `state.scale()` GUI px and y points down. `renderToTexture` submits into a `SubmitNodeCollector`; the picture is then blitted (`blitTexture`) with `GUI_TEXTURED_PREMULTIPLIED_ALPHA` and colour `-1`. The texture view is private, so a renderer that blits with a colour (to tint or fade) needs it widened (Vellum's AT/AW). `textureIsReadyToBlit(state)` skips the render when the texture still holds the right picture (vanilla's oversized items use it).
- Pools: NeoForge reuses last frame's renderer for an *equal* state first (record `equals`), then any of the same texture size; two equal states in one frame make it drop a renderer, so states must differ within a frame. Fabric reuses by order within the frame.
- Entities: `GuiEntityRenderer.renderToTexture` sets `Lighting.Entry.ENTITY_IN_UI`, translates, rotates, sets the camera from the tilt, and calls `entityRenderDispatcher.submit(state, camera, 0,0,0, pose, collector)`. `EntityType.create(level, reason)` returns null for monsters in peaceful worlds (`canSpawn`); display entities use `create(level, new EntitySpawnRequest(reason, true))` after checking `type.isEnabled(level.enabledFeatures())`.
- Blocks: `new BlockModelResolver(mc.getModelManager()).update(BlockModelRenderState, BlockState, BlockDisplayContext.create())`, then `state.submit(pose, collector, light, OverlayTexture.NO_OVERLAY, 0)`; the model spans 0..1 and includes block-entity special models (chests). `mc.getModelManager().getBlockModelSet().get(blockState)` changes identity on resource reload. Parse states with `BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, "oak_stairs[facing=east]", false)`.
- Items: `mc.getItemModelResolver().updateForTopItem(new TrackingItemStackRenderState(), stack, ItemDisplayContext.NONE, level, null, seed)` resolves without a display transform (centred on the origin, a block wide); `usesBlockLight()` tells block-like models (lit `ITEMS_3D`) from flat ones (`ITEMS_FLAT`); `getModelIdentity()`/`isAnimated()` say when a picture can be kept; `getModelBoundingBox()` gives the model's extents. Vanilla's GUI view of blocks is the `block/block` display transform: rotation 30, 225, 0 and scale 0.625, after `pose.scale(1, -1, -1)`.

## 4. Textures and canvas
- `NativeImage(w, h, zero)`: `getPixel/setPixel(x,y,argb)`, `fillRect`, `close()`.
- `DynamicTexture(Supplier<String> label, NativeImage img)` uploads on creation; `getPixels()`, `upload()` and `close()` are available.
- `TextureManager.register(Identifier, AbstractTexture)`.
- Blit without registering: `g.blit(texture.getTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST), x0,y0,x1,y1, u0,u1,v0,v1)`.
- Examples: Chronicle `MapImage.java` (L49) and `MapIcons.java` (L174).

## 5. Screen lifecycle and input
`Screen` (`net.minecraft.client.gui.screens.Screen`):
- Lifecycle: `init()`, `repositionElements()`, `resize(w,h)`, `tick()`, `added()`, `removed()`, `onClose()`.
- Behaviour flags: `shouldCloseOnEsc()`, `isPauseScreen()` (default true), `isInGameUi()` (default false; true means a translucent background over the world).
- Drawing: `extractBackground(g, mx, my, a)`, `extractRenderState(g, mx, my, a)`. `a` is the frame delta in ticks, not partial ticks; use `Util.getMillis()` for animation time.

Input (records in `net.minecraft.client.input.*`):
```java
void    mouseMoved(double x, double y)
boolean mouseClicked(MouseButtonEvent e, boolean doubleClick)
boolean mouseReleased(MouseButtonEvent e)
boolean mouseDragged(MouseButtonEvent e, double dx, double dy)
boolean mouseScrolled(double x, double y, double scrollX, double scrollY)
boolean keyPressed(KeyEvent e); boolean keyReleased(KeyEvent e)
boolean charTyped(CharacterEvent e)
boolean preeditUpdated(@Nullable PreeditEvent e)   // IME
```
- `MouseButtonEvent(double x, double y, MouseButtonInfo(int button, int modifiers))`
- `KeyEvent(int key /*SDL scancode*/, int keycode /*SDL keycode*/, int modifiers)`, with helpers `isEscape() isConfirmation() isLeft() hasShiftDown() hasControlDown() isSelectAll() isCopy() isPaste() isCut()`. The shortcut modifier is Cmd on macOS.
- `CharacterEvent(int codepoint)`, with `codepointAsString()`.

`InputConstants`:
- Letters: `KEY_A=4`...
- Digits: `KEY_1=30`...`KEY_0=39`
- Editing: `KEY_RETURN=40`, `KEY_ESCAPE=41`, `KEY_BACKSPACE=42`, `KEY_TAB=43`, `KEY_SPACE=44`, `KEY_DELETE=76`
- Navigation: `KEY_HOME=74`, `KEY_PAGEUP=75`, `KEY_END=77`, `KEY_PAGEDOWN=78`, `KEY_RIGHT=79`, `KEY_LEFT=80`, `KEY_DOWN=81`, `KEY_UP=82`
- Modifier keys: `KEY_LCONTROL=224`, `KEY_LSHIFT=225`

**Text input has to be enabled through SDL**, or `charTyped` never fires. When a text field gains or loses focus, call `Minecraft.getInstance().onTextInputFocusChange(GuiEventListener owner, boolean focused)`. `textInputManager().setTextInputArea(x0,y0,x1,y1)` positions the IME window. Vanilla `EditBox.setFocused` shows the pattern.

Other:
- Clipboard: `minecraft.keyboardHandler.getClipboard()` / `setClipboard(String)`.
- Cursor: `g.requestCursor(CursorTypes.POINTING_HAND)` each frame. `CursorTypes` has `ARROW, IBEAM, CROSSHAIR, POINTING_HAND, RESIZE_NS, RESIZE_EW, RESIZE_ALL, NOT_ALLOWED`.
- Window: `getGuiScale()`, `getGuiScaledWidth/Height()`.
- Sounds: `minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F))`.

## 6. AbstractContainerScreen and slots
- Fields: `imageWidth/imageHeight` (protected final, set in the constructor), `leftPos/topPos` (centred in `init()`), `menu`, `hoveredSlot`.
- Render flow: `extractContents` translates the pose by `(leftPos, topPos)`, then calls `extractLabels` and `extractSlots` → `extractSlot(g, slot, mx, my)` (item at `slot.x, slot.y` plus decorations). After that comes `extractCarriedItem`, which starts a new stratum.
- Hit testing goes through private `getHoveredSlot` → `isHovering(slot, mx, my)`. It uses `slot.x/slot.y` (16×16) relative to `leftPos/topPos`.
- **`Slot.x` and `Slot.y` are `public final int`.** To place slots at positions computed by layout, make them mutable:
  - mixin `@Mutable @Accessor`, works on both loaders from common
  - NeoForge AT: `public-f net.minecraft.world.inventory.Slot x`
  - Fabric: `mutable field net/minecraft/world/inventory/Slot x I`

  Then either set `leftPos = topPos = 0` and use absolute positions, or keep slots relative. Override `hasClickedOutside`.
- NeoForge adds getters `getHoveredSlot() getLeftPos() getTopPos()` and the `renderSlotContents` hook.
- Registering screens:
  - NeoForge: `RegisterMenuScreensEvent.register(menuType, Screen::new)`.
  - Fabric: `MenuScreens.register(menuType, Screen::new)` via the access widener.

## 7. HUD layers
Chronicle's approach is a common `static void extract(GuiGraphicsExtractor g, DeltaTracker d)`, which bails out if `mc.player == null || mc.gui.hud.isHidden()`. The loaders register it:
- NeoForge: `RegisterGuiLayersEvent`, `e.registerAbove(VanillaGuiLayers.TITLE, id, Hud::extract)`.
- Fabric: `HudElementRegistry.attachElementAfter(VanillaHudElements.TITLE_AND_SUBTITLE, id, Hud::extract)`.

## 8. Loader hooks
| Need | NeoForge | Fabric |
|---|---|---|
| Custom `GuiElementRenderState` | `g.submitGuiElementRenderState(s)` | `g.guiRenderState.addGuiElement(s)` (AW) |
| Pipeline | `RegisterRenderPipelinesEvent` | `RenderPipelines.register` (AW) |
| PiP renderer | `RegisterPictureInPictureRenderersEvent` | `PictureInPictureRendererRegistry` |
| HUD | `RegisterGuiLayersEvent` | `HudElementRegistry` |
| Menu screens | `RegisterMenuScreensEvent` | `MenuScreens.register` (AW) |
| Payloads | `RegisterPayloadHandlersEvent` / `RegisterClientPayloadHandlersEvent` | `PayloadTypeRegistry` + `ClientPlayNetworking`/`ServerPlayNetworking` |
