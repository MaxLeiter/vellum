# Design: drawing boxes with a signed-distance shader

Status: steps 1 to 4 are built for 26.3 and 26.2 (`Canvas.fillBoxShadow`, `SdfRenderState`,
`assets/vellum/shaders/core/sdf_box`, the overrides in `McCanvas`), behind `client.shapeShader`. Loader registration
and the previewer are still to do. Where the build differs from the proposal below, the "As built" section says so.

## Why

Every rounded rectangle, border and box-shadow is tessellated on the CPU into flat-colour quads
(`engine/.../paint/Shapes.java`, `Gradients.java`, `Shadows.java`, collected by `QuadBatch`) and drawn with vanilla's
`RenderPipelines.GUI`. That has three costs:

- **Jagged edges.** Quads have no antialiasing, so curves step at every device pixel. That is fine at 8 px radii and
  GUI scale 2, but visible at large radii, at scale 1, or under a `transform: rotate()`.
- **Blur is faked.** A blurred shadow is 2 to 6 concentric rings with a smoothstep ramp (`Shadows.ramp`). Large blurs
  band, and each shadow is dozens of quads.
- **Vertex count.** A rounded box with a border and one shadow is a few hundred vertices, rebuilt every frame and
  copied in `McCanvas.fillQuads` (it cannot keep the caller's arrays).

A signed-distance (SDF) fragment shader draws each of these as **one quad**: the shader works out per pixel how far it
is from the shape's edge and turns that into coverage. Edges come out antialiased at any scale or rotation, and a
Gaussian shadow has a cheap closed form.

## Scope

The first version replaces three things on 26.3 and 26.2:

| Primitive | Engine entry point | Shader handles it when | Otherwise |
|---|---|---|---|
| Rounded fill | `Canvas.fillRoundedRect` | every corner is circular (rx = ry) | tessellate (today's path) |
| Border | `Canvas.fillBorder` | one colour and one width on all four sides, circular corners | tessellate |
| Box shadow | new `Canvas.fillBoxShadow` | circular corners, and for an unoffset outer shadow, the box is opaque | tessellate |

These cover what pages actually use: `.mc-panel`, buttons, cards, glows. Elliptical corners, per-side border colours
or widths, and dashed, dotted, groove, ridge and double borders keep the current code, which stays the reference
behaviour and the 1.21.1 path.

Not in scope: gradients (they already clip to the rounded shape through `QuadBatch`; moving them is a later step) and
**rounded `overflow: hidden` clipping**. The SDF cannot do clipping, because the clip has to apply to everything
inside the box, including text, items, sprites and 3D scenes, which use vanilla's pipelines. That needs a mask: a
stencil, or drawing the subtree to an offscreen target and blitting it through the SDF. It is a separate project.

## Engine changes (`engine`, every version)

1. Add one method to `Canvas`, with a default that runs today's tessellation:

   ```java
   /**
    * Draws one box-shadow of a box. {@code shape} is the shadow's rectangle after offset and spread, {@code radii} its
    * corner radii (as for fillRoundedRect), {@code blur} the CSS blur radius. Inset shadows are clipped to
    * {@code clip}/{@code clipRadii} (the padding box). {@code boxOpaque} says the border box hides what is under it.
    */
   default void fillBoxShadow(float[] shape, float[] radii, float blur, int argb, boolean inset,
                              float[] clip, float[] clipRadii, boolean boxOpaque) { ... }
   ```

   `Shadows` keeps its geometry code behind this default; `BoxPainter` calls the canvas instead of
   `shadows.outer/inset(batch, ...)` directly.

2. Nothing else in the engine changes. `fillRoundedRect` and `fillBorder` already exist for this purpose (the
   `Canvas` javadoc says "a backend may override them with something better (an SDF shader, say)").

3. Tests: `RecordingCanvas` records the new call, and the existing shadow tests move to call
   `Shadows` through the default.

## Minecraft side (`common`, 26.3 and 26.2)

### Pipeline

One pipeline, `vellum:pipeline/sdf_box`, with shaders at `assets/vellum/shaders/core/sdf_box.{vsh,fsh}`, built as the
GUI API notes describe: QUADS, translucent blend, no culling (so mirrored transforms need no rewinding), the
`GLOBALS`, `PROJECTION` and `DYNAMIC_TRANSFORMS` bind groups.

Registered by each loader at startup (NeoForge `RegisterRenderPipelinesEvent`, Fabric `RenderPipelines.register`
through the access widener) so a broken shader fails at load, not on the first page. On 26.2 the classes are in
`com.mojang.blaze3d.*`, on 26.3 in `com.mojang.renderpearl.api.*`; the pipeline builder lives in each version's `McGui`
source.

### Getting parameters to the shader

`GuiRenderer` binds no custom uniforms, so everything travels per vertex. Each quad repeats the same shape parameters
on its four vertices. A vertex format of our own (to check: whether 26.x lets a pipeline declare one, or only
vanilla's):

| Attribute | Type | Carries |
|---|---|---|
| `Position` | 3 × float | screen position: the shape's bounds grown by the blur, plus one device px for the antialiased edge |
| `Color` | RGBA8 | the colour, opacity already multiplied in (as today) |
| `UV0` | 2 × float | the vertex's position in the box's local space, relative to its centre |
| `UV3` | 2 × float | half width, half height |
| `UV1`, `UV2` | 2 × int16 each | the four corner radii in 1/16 px (up to 2047 px) |
| `LineWidth` | float | mode and size: 0 = fill; > 0 = border of that width; < 0 = shadow with σ = −value (CSS σ = blur / 2) |

Inset shadows set a flag in the sign of the half size, or get their own pipeline if that reads poorly; to be decided
when writing the shader.

Working in local space and antialiasing with `fwidth(distance)` keeps edges one device pixel wide under any
`transform`, including rotation, which McCanvas already supports for quads.

### Fragment shader

- **Fill:** coverage = `clamp(0.5 - d / fwidth(d), 0, 1)` with `d` the rounded-box distance (Inigo Quilez's
  `sdRoundBox` with per-quadrant radius).
- **Border:** `max(d_outer, -d_inner)`, where inner is the box shrunk by the width with radii `max(0, r - width)`, which
  is the CSS inner radius for an even border.
- **Shadow:** Evan Wallace's closed form for a blurred rounded rectangle: an exact Gaussian integral across x, a few
  samples along y. Four to eight samples look like a true Gaussian at any blur, so the banding goes away.
- **Inset shadow:** 1 − the outer profile, with the padding-box clip as a second distance.

### McCanvas

`McCanvas` overrides the three methods. Each checks that the shader can draw the call (circular radii, one border
colour, and so on), computes the screen bounds as it does for quads, and adds one `SdfBoxRenderState` (a new
`GuiElementRenderState` beside `QuadsRenderState`); otherwise it calls the `Canvas` default. Clipping, opacity and
transforms are unchanged.

`GuiRenderer` sorts elements within a node by (scissor, pipeline, texture), so boxes on the same layer still batch
into one draw. Interleaving SDF boxes with vanilla fills costs a draw call per switch only where they overlap.

## Keeping the previewer honest

The previewer's `ImageCanvas` draws with antialiasing **off**, so it matches the game pixel for pixel today. Once the
game antialiases boxes, the previewer should too: override the same three methods in `ImageCanvas` with Java2D's
antialiased `RoundRectangle2D` and a Gaussian for shadows. Otherwise a page tuned in the previewer looks different in
game, which is what the previewer is for.

## Plan

1. Engine: `Canvas.fillBoxShadow` with today's code as the default; `BoxPainter` calls it. No visible change.
2. 26.3: pipeline, shaders and `SdfBoxRenderState` for fills only. Compare against the tessellated fill in
   `/vellum canvastest` (new cells: the same box both ways, at GUI scales 1 to 4, rotated).
3. Borders, then shadows, each with canvastest cells and a before/after screenshot of the showcase pages.
4. Port to 26.2 (package names), then register on both loaders.
5. Previewer: antialiased overrides in `ImageCanvas`.
6. A config switch (`render.sdf=true` in `vellum.properties`) to fall back to tessellation, kept until the shader has
   been seen on a range of GPUs and both backends (OpenGL and Vulkan, since 26.3 compiles GLSL to SPIR-V).

1.21.1 stays on tessellation: its renderer is immediate-mode with a different shader system, and porting there is
not worth the code until someone asks.

## As built (26.3)

- **Vertex format.** A pipeline can declare its own `VertexFormat`, and `BufferBuilder` writes any of vanilla's
  eight attribute names into it, so the layout is close to the table above. Shadows needed more than fits, so a
  shadow's spread rides in `Position.z` (the GUI pipelines have no depth test) and its flags in `Normal`. Offset inset
  shadows would need two more floats; they are tessellated. Shadows blur with one corner radius, so a shadow whose
  corners differ is tessellated too.
- **One pipeline per kind.** `vellum:pipeline/sdf_box` (fills and borders) and `vellum:pipeline/sdf_shadow` (the same
  shaders with `SHADOW` defined), no culling, so mirrored transforms need no rewinding.
- **No loader registration yet.** Vanilla compiles a pipeline it was not told about on first use;
  `SdfRenderState.available()` asks for both, and a failure (shaders not loaded, a driver that rejects them) is logged
  once, retried every few seconds, and tessellated meanwhile.
- **The cutout.** The proposal's `boxOpaque` flag is not needed: `fillBoxShadow` passes the box and the `Shadow`, so the
  shader knows the box and leaves it uncovered under an outer shadow that is not offset, as the tessellation does.
- **Square shapes stay quads.** Unrounded fills and borders, and unblurred square shadows, keep the exact quads, which
  are as crisp on whole device pixels.
- **Checked** under the dev autopilot on NeoForge 26.3 with Mesa's software Vulkan (the OpenGL backend could not open
  a window on the virtual display): every page draws, the autopilot's 26 checks pass, and `dev/shapes.html` covers the
  cases above.

## As built (26.2)

- 26.2's `BufferBuilder` writes seven attribute names (`Position`, `Color`, `UV0`, `UV1`, `UV2`, `Normal`,
  `LineWidth`) and has no `UV3`. 26.2's `SdfRenderState` makes the quad the box grown by one margin on every side
  (the largest the call asked for on any side) and sends the margin in `Position.z`, so the shader gets the half size
  as `|UV0|` minus the margin. A shadow has one radius, which fits in `UV1`, and its spread travels as the bits of a
  float split over `UV2`'s two shorts.
- The shader source is shared. The 26.2 pipelines define `VELLUM_26_2`, which switches `sdf_box.vsh` and `.fsh` to
  26.2's conventions: `#moj_import` instead of `#include` (vanilla's include files order `DynamicTransforms`
  differently on the two versions, so the shader has to use them), and inputs and outputs without `layout(location)`,
  because both of 26.2's backends match attributes and varyings by name.
- `GpuDevice.precompilePipeline(...).isValid()` stands in for 26.3's `getCompiledPipelineNullable`. The device keeps
  a pipeline that failed until resources reload, so the retry only succeeds after a reload.
- Checked under the dev autopilot on NeoForge 26.2 with the OpenGL backend (macOS, GL 4.1 on Metal): the shapes page
  looks as it does on 26.3.

## Open questions

- Does 26.3's OpenGL backend draw the same as its Vulkan one? 26.3 has only been run on Vulkan, 26.2 only on OpenGL.
- An outer shadow that is not offset has the part under the border box cut out, so translucent boxes do not darken.
  The shader would need the box's own shape as well as the shadow's, which does not fit the vertex budget. The proposal
  only takes the shader path when the box is opaque. Is that common enough, or should the cutout get its own
  attributes?
