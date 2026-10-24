#version 330

// Vellum's rounded boxes, borders and box-shadows (McCanvas): one quad per shape, its coverage worked out per pixel
// from the shape's signed distance. The quad's vertices all carry the same shape; see SdfRenderState for the layout.
//
// VELLUM_26_2 is defined by 26.2's pipelines. 26.2 matches attributes and varyings by name, imports with #moj_import
// and has no UV3, so its quad is the box grown by one margin (Position.z) on every side, and the half size is |UV0|
// minus that margin.

#ifdef VELLUM_26_2
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
// Boxes: the corner radii in 1/16 px. Shadows: UV1.x is the one radius, UV2 the bits of the spread, low then high.
in ivec2 UV1;
in ivec2 UV2;
in float LineWidth;
#ifdef SHADOW
in vec3 Normal;
#endif

#define AT(n)
#else
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

// Screen position; z carries the spread of a shadow.
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
// The vertex in the box's local space (GUI px before the canvas transform), from the box's centre.
layout(location = 2) in vec2 UV0;
// The box's half width and height.
layout(location = 3) in vec2 UV3;
// Corner radii in 1/16 px: top-left, top-right; bottom-right, bottom-left.
layout(location = 4) in ivec2 UV1;
layout(location = 5) in ivec2 UV2;
// A box's border width (0 fills it); a shadow's sigma.
layout(location = 6) in float LineWidth;
#ifdef SHADOW
// x: an inset shadow; y: leave the box uncovered (an outer shadow that is not offset).
layout(location = 7) in vec3 Normal;
#endif

#define AT(n) layout(location = n)
#endif

AT(0) out vec4 vertexColor;
AT(1) out vec2 local;
AT(2) flat out vec2 halfSize;
AT(3) flat out vec4 radii;
AT(4) flat out vec2 params;
#ifdef SHADOW
AT(5) flat out vec2 flags;
#endif

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position.xy, 0.0, 1.0);
    vertexColor = Color;
    local = UV0;
#ifdef VELLUM_26_2
    halfSize = abs(UV0) - Position.z;
#ifdef SHADOW
    radii = vec4(float(UV1.x) / 16.0);
    params = vec2(LineWidth, intBitsToFloat((UV2.x & 0xFFFF) | (UV2.y << 16)));
#else
    radii = vec4(UV1, UV2) / 16.0;
    params = vec2(LineWidth, 0.0);
#endif
#else
    halfSize = UV3;
    radii = vec4(UV1, UV2) / 16.0;
    params = vec2(LineWidth, Position.z);
#endif
#ifdef SHADOW
    flags = Normal.xy;
#endif
}
