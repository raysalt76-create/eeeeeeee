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

    float aa = max(0.8, fwidth(dist) * softness);
    return 1.0 - smoothstep(-aa, aa, dist);
}

void main() {
    vec4 base = vertexColor * ColorModulator;
    float cornerRadius = resolveRadius(8.0);
    float mask = roundedMask(texCoord0, cornerRadius, 2.5);

    // Soft vertical glow centred on the row — theme-neutral (no hardcoded colour)
    float centerGlow = exp(-pow((texCoord0.y - 0.5) * 2.8, 2.0));
    float sideFade   = clamp(1.0 - abs(texCoord0.x - 0.5) * 1.3, 0.0, 1.0);

    // Brighten the base colour rather than adding a fixed-hue tint,
    // so the effect looks correct on every theme.
    float brightness = 1.0 + 0.22 * centerGlow * sideFade;
    vec3 rgb = base.rgb * brightness;

    // Subtle top-light so the row feels slightly lifted
    float topLight = pow(1.0 - texCoord0.y, 1.4) * 0.08;
    rgb += base.rgb * topLight;

    float alpha = base.a * mask;
    if (alpha < 0.003) {
        discard;
    }

    fragColor = vec4(rgb, alpha);
}
