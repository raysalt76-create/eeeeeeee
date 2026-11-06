#version 330

#moj_import <minecraft:dynamictransforms.glsl>

in vec4 vertexColor;
in vec2 texCoord0;
flat in float packedRadiusPx;
out vec4 fragColor;

float resolveRadius(float fallbackPx) {
    return packedRadiusPx > 0.0 ? packedRadiusPx : fallbackPx;
}

float roundedMaskSingle(vec2 uv, float radiusPx, float softness) {
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

    float aa = max(0.90, fwidth(dist) * softness);
    return 1.0 - smoothstep(-aa, aa, dist);
}

float roundedMaskHQ(vec2 uv, float radiusPx, float softness) {
    vec2 texel = vec2(length(dFdx(uv)), length(dFdy(uv)));
    vec2 tap = texel * 0.5;
    return (
        roundedMaskSingle(uv + vec2(-tap.x, -tap.y), radiusPx, softness) +
        roundedMaskSingle(uv + vec2( tap.x, -tap.y), radiusPx, softness) +
        roundedMaskSingle(uv + vec2(-tap.x,  tap.y), radiusPx, softness) +
        roundedMaskSingle(uv + vec2( tap.x,  tap.y), radiusPx, softness)
    ) * 0.25;
}

float innerShadow(vec2 uv, float radiusPx, float shadowWidth) {
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

    return smoothstep(-shadowWidth, 0.0, dist);
}

void main() {
    vec4 base = vertexColor * ColorModulator;
    float cornerRadius = resolveRadius(18.0);

    float mask = roundedMaskHQ(texCoord0, cornerRadius, 2.30);

    float alpha = base.a * mask;
    if (alpha < 0.003) {
        discard;
    }

    fragColor = vec4(base.rgb, alpha);
}
