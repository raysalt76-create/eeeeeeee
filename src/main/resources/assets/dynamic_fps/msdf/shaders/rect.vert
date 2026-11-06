#version 330 core

// One instance per rounded rect. Stride 28 bytes:
//   vec4 rect (16) | float radius (4) | int color (4) | float pad (4)
// The quad comes from gl_VertexID, same as the text shader.
layout(location = 0) in vec4  rect;    // x, y, w, h (screen space)
layout(location = 1) in float radius;  // corner radius in pixels
layout(location = 2) in int   color;   // packed ARGB

uniform mat4 proj;

out vec2  vLocal;   // position relative to the rect centre
out vec2  vHalf;    // half extents
out float vRadius;
out vec4  vColor;

const vec2 quad[4] = vec2[](
    vec2(0.0, 0.0),
    vec2(1.0, 0.0),
    vec2(0.0, 1.0),
    vec2(1.0, 1.0)
);

void main() {
    vec2 q = quad[gl_VertexID & 3];
    vec2 pos = rect.xy + q * rect.zw;

    vHalf   = rect.zw * 0.5;
    vLocal  = pos - (rect.xy + vHalf);
    // A radius larger than half the shortest side would invert the SDF, so clamp it here
    // rather than trusting callers.
    vRadius = min(radius, min(vHalf.x, vHalf.y));

    float a = float((color >> 24) & 0xFF) / 255.0;
    float r = float((color >> 16) & 0xFF) / 255.0;
    float g = float((color >>  8) & 0xFF) / 255.0;
    float b = float( color        & 0xFF) / 255.0;
    vColor = vec4(r, g, b, a);

    gl_Position = proj * vec4(pos, 0.0, 1.0);
}
