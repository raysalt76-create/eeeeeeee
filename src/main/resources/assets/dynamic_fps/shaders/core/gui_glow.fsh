#version 330

#moj_import <minecraft:dynamictransforms.glsl>

in vec4 vertexColor;
in vec2 texCoord0;
flat in float packedRadiusPx;
out vec4 fragColor;

float resolveRadius(float fallbackPx) {
    return packedRadiusPx > 0.0 ? packedRadiusPx : fallbackPx;
}

float roundedMask(vec2 uv, float radiusPx, float softness) {
    vec2 dUVdx = dFdx(uv);
    vec2 dUVdy = dFdy(uv);
    float widthPx  = 1.0 / max(length(dUVdx), 1e-5);
    float heightPx = 1.0 / max(length(dUVdy), 1e-5);
    vec2 sizePx = vec2(widthPx, heightPx);

    float radius = min(radiusPx, min(sizePx.x, sizePx.y) * 0.5);
    vec2 p = (uv - 0.5) * sizePx;
    vec2 halfSize = sizePx * 0.5 - vec2(radius);
    vec2 q = abs(p) - halfSize;
    float dist = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;

    float aa = max(0.7, fwidth(dist) * softness);
    return 1.0 - smoothstep(-aa, aa, dist);
}

void main() {
    vec4 base = vertexColor * ColorModulator;
    float cornerRadius = resolveRadius(10.0);
    float mask = roundedMask(texCoord0, cornerRadius, 2.8);

    // Gaussian radial falloff — much softer than the old linear `1 - length(...)`.
    // The falloff is slightly asymmetric (wider on X) to fill rectangular
    // glow quads more naturally.
    vec2 d = (texCoord0 - 0.5) * vec2(1.0, 1.2);
    float glow = exp(-dot(d, d) * 4.5);

    // Brightens the centre; rgb stays theme-coloured
    vec3 rgb = base.rgb * (1.0 + 0.12 * glow);

    // Single linear alpha (old `mask * mask` created a harsh blob centre)
    float alpha = base.a * mask * glow;
    if (alpha < 0.002) {
        discard;
    }

    fragColor = vec4(rgb, alpha);
}
