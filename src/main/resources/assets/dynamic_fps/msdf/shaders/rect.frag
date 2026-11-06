#version 330 core

// Signed distance to a rounded box, antialiased with the screen-space derivative so the edge
// is ~1px regardless of scale. Same idea as the MSDF text shader: reconstruct the edge
// analytically instead of sampling it, so nothing is ever blocky.
in vec2  vLocal;
in vec2  vHalf;
in float vRadius;
in vec4  vColor;

out vec4 fragColor;

float roundedBoxSdf(vec2 p, vec2 halfSize, float radius) {
    vec2 q = abs(p) - halfSize + radius;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - radius;
}

void main() {
    float dist = roundedBoxSdf(vLocal, vHalf, vRadius);
    // fwidth() is the distance change across one pixel, which is exactly the AA width needed.
    float edge = fwidth(dist) * 0.5;
    float alpha = 1.0 - smoothstep(-edge, edge, dist);

    if (alpha <= 0.002) discard;
    fragColor = vec4(vColor.rgb, vColor.a * alpha);
}
