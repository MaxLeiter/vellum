#version 330
#extension GL_ARB_separate_shader_objects : require

// Vellum's rounded boxes, borders and box-shadows (McCanvas): one quad per shape, its coverage worked out per pixel
// from the shape's signed distance. The quad's vertices all carry the same shape; see SdfRenderState for the layout.

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

layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec2 local;
layout(location = 2) flat out vec2 halfSize;
layout(location = 3) flat out vec4 radii;
layout(location = 4) flat out vec2 params;
#ifdef SHADOW
layout(location = 5) flat out vec2 flags;
#endif

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position.xy, 0.0, 1.0);
    vertexColor = Color;
    local = UV0;
    halfSize = UV3;
    radii = vec4(UV1, UV2) / 16.0;
    params = vec2(LineWidth, Position.z);
#ifdef SHADOW
    flags = Normal.xy;
#endif
}
