#version 330
#ifndef VELLUM_26_2
#extension GL_ARB_separate_shader_objects : require
#endif

// See sdf_box.vsh. Without SHADOW: a rounded box filled (params.x = 0) or its border of width params.x, antialiased
// over one device pixel. With SHADOW: a box-shadow, the shape blurred by a Gaussian of sigma params.x, grown (outer)
// or shrunk (inset) by the spread params.y; inset shadows stay inside the box, and an outer shadow with flags.y set
// leaves the box itself uncovered.

#ifdef VELLUM_26_2
#moj_import <minecraft:dynamictransforms.glsl>
#define AT(n)
#else
#include <minecraft:dynamictransforms.glsl>
#define AT(n) layout(location = n)
#endif

AT(0) in vec4 vertexColor;
AT(1) in vec2 local;
AT(2) flat in vec2 halfSize;
AT(3) flat in vec4 radii;
AT(4) flat in vec2 params;
#ifdef SHADOW
AT(5) flat in vec2 flags;
#endif

AT(0) out vec4 fragColor;

/** The corner radius of the quadrant p is in (y down): radii are top-left, top-right, bottom-right, bottom-left. */
float cornerRadius(vec2 p, vec4 r) {
    return p.x < 0.0 ? (p.y < 0.0 ? r.x : r.w) : (p.y < 0.0 ? r.y : r.z);
}

/** Signed distance from p to a rounded box of half size b centred on the origin: negative inside. */
float roundedBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

/** Radii grown (outwards > 0) or shrunk by d, as CSS spreads a shadow: square corners stay square. */
vec4 offsetRadii(vec4 r, float d) {
    return mix(vec4(0.0), max(r + d, 0.0), step(0.0001, r));
}

/** How much of the pixel at signed distance d is inside, with aa the size of a pixel in distance units. */
float coverage(float d, float aa) {
    return clamp(0.5 - d / aa, 0.0, 1.0);
}

#ifdef SHADOW
// Evan Wallace's blurred rounded rectangle (https://madebyevan.com/shaders/fast-rounded-rectangle-shadows/): exact
// across x, four samples of the Gaussian along y.

vec2 erf(vec2 x) {
    vec2 s = sign(x), a = abs(x);
    x = 1.0 + (0.278393 + (0.230389 + 0.078108 * (a * a)) * a) * a;
    x *= x;
    return s - s / (x * x);
}

float gaussian(float x, float sigma) {
    return exp(-(x * x) / (2.0 * sigma * sigma)) / (2.50662827 * sigma);
}

float shadowX(float x, float y, float sigma, float corner, vec2 b) {
    float delta = min(b.y - corner - abs(y), 0.0);
    float curved = b.x - corner + sqrt(max(0.0, corner * corner - delta * delta));
    vec2 integral = 0.5 + 0.5 * erf((x + vec2(-curved, curved)) * (0.70710678 / sigma));
    return integral.y - integral.x;
}

/** The fraction of a rounded box (half size b, one corner radius) blurred by sigma that covers p. */
float blurredBox(vec2 p, vec2 b, float corner, float sigma) {
    float low = p.y - b.y, high = p.y + b.y;
    float start = clamp(-3.0 * sigma, low, high), end = clamp(3.0 * sigma, low, high);
    float dy = (end - start) / 4.0;
    float y = start + dy * 0.5, value = 0.0;
    for (int i = 0; i < 4; i++) {
        value += shadowX(p.x, p.y - y, sigma, corner, b) * gaussian(y, sigma) * dy;
        y += dy;
    }
    return value;
}
#endif

void main() {
    // One device pixel in local units, under any canvas transform.
    float aa = max(length(fwidth(local)) * 0.70710678, 1e-4);
    float r = cornerRadius(local, radii);
    float alpha;
#ifdef SHADOW
    bool inset = flags.x > 0.5, cutout = flags.y > 0.5;
    // An outer shadow that is not a cutout ignores the box; a cutout's inside needs no blur.
    float box = inset || cutout ? coverage(roundedBox(local, halfSize, r), aa) : 0.0;
    if (cutout && !inset && box >= 1.0) {
        discard;
    }
    float sigma = max(params.x, aa * 0.5);
    float spread = inset ? -params.y : params.y;
    vec2 shape = halfSize + spread;
    // Shadows only reach here with one radius for every corner (McCanvas checks), so the blur needs just one.
    float corner = offsetRadii(vec4(r), spread).x;
    float shadow = shape.x > 0.0 && shape.y > 0.0 ? blurredBox(local, shape, min(corner, min(shape.x, shape.y)), sigma) : 0.0;
    alpha = inset ? box * (1.0 - shadow) : shadow * (1.0 - box);
#else
    alpha = coverage(roundedBox(local, halfSize, r), aa);
    float width = params.x;
    if (width > 0.0) {
        vec2 inner = halfSize - width;
        if (inner.x > 0.0 && inner.y > 0.0) {
            float innerRadius = cornerRadius(local, offsetRadii(radii, -width));
            alpha *= 1.0 - coverage(roundedBox(local, inner, innerRadius), aa);
        }
    }
#endif
    vec4 color = vertexColor * vec4(1.0, 1.0, 1.0, alpha);
    if (color.a == 0.0) {
        discard;
    }
    fragColor = color * ColorModulator;
}
