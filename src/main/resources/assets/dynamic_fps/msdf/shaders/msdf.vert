#version 330 core

// One INSTANCE per glyph; the quad itself is generated from gl_VertexID, so there is no
// vertex or index buffer at all. Stride is 36 bytes:
//   vec4 rect (16) | vec4 uv (16) | int color (4)
//
// Note there is deliberately no per-glyph model matrix and no depth attribute, unlike the
// renderer this was adapted from. Position and scale are baked into rect/size on the CPU,
// which is all a HUD needs, and ordering inside one flush is simply instance order — so a
// 116-byte stride collapses to 36 and the depth-buffer interaction disappears entirely.
layout(location = 0) in vec4 rect;   // x, y, w, h   (screen space)
layout(location = 1) in vec4 uv;     // u0, v0, uW, vH (atlas space, normalised)
layout(location = 2) in int  color;  // packed ARGB

uniform mat4 proj;

out vec2 vUV;
out vec4 vColor;

const vec2 quad[4] = vec2[](
    vec2(0.0, 0.0),
    vec2(1.0, 0.0),
    vec2(0.0, 1.0),
    vec2(1.0, 1.0)
);

void main() {
    vec2 q = quad[gl_VertexID & 3];

    vec2 pos = rect.xy + q * rect.zw;
    vUV = uv.xy + q * uv.zw;

    float a = float((color >> 24) & 0xFF) / 255.0;
    float r = float((color >> 16) & 0xFF) / 255.0;
    float g = float((color >>  8) & 0xFF) / 255.0;
    float b = float( color        & 0xFF) / 255.0;
    vColor = vec4(r, g, b, a);

    gl_Position = proj * vec4(pos, 0.0, 1.0);
}
