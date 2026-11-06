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

    float aa = max(0.70, fwidth(dist) * softness);
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

void main() {
    vec4 base = vertexColor * ColorModulator;
    float cornerRadius = resolveRadius(18.0);
    float mask = roundedMaskHQ(texCoord0, cornerRadius, 2.20);

    // Top sheen: bright highlight concentrated at the top edge
    float sheen = smoothstep(1.0, 0.0, texCoord0.y);
    float center = clamp(1.0 - abs(texCoord0.x - 0.5) * 2.0, 0.0, 1.0);

    // Fresnel: slight glow at the very corners / edges
    float edgeDist = min(min(texCoord0.x, 1.0 - texCoord0.x),
                         min(texCoord0.y, 1.0 - texCoord0.y));
    float fresnel = pow(1.0 - clamp(edgeDist * 4.0, 0.0, 1.0), 2.0) * 0.06;

    vec3 rgb = base.rgb * (1.0 + 0.12 * sheen) + vec3(0.02, 0.03, 0.04) * center * 0.06;
    rgb += vec3(fresnel);

    // Alpha uniform across the whole glass pane (no bottom-fade artifact)
    float alpha = base.a * mask;
    if (alpha < 0.003) {
        discard;
    }

    fragColor = vec4(rgb, alpha);
}
