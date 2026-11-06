#version 330

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

float median3(vec3 sampleColor) {
    return max(min(sampleColor.r, sampleColor.g), min(max(sampleColor.r, sampleColor.g), sampleColor.b));
}

float screenPxRange() {
    vec2 unitRange = vec2(40.0) / vec2(textureSize(Sampler0, 0));
    vec2 screenTexSize = vec2(1.0) / max(fwidth(texCoord0), vec2(1e-6));
    return max(0.5 * dot(unitRange, screenTexSize), 1.0);
}

float distToAlpha(float distanceValue, float pxRange) {
    float signedDistance = (distanceValue - 0.5) * pxRange;
    return clamp(signedDistance + 0.5, 0.0, 1.0);
}

float mtsdfDistance(vec2 uv) {
    vec4 sampleValue = texture(Sampler0, uv);
    float msdfDistance = median3(sampleValue.rgb);
    float sdfDistance = sampleValue.a;
    float disagreement = max(
        max(abs(sampleValue.r - sampleValue.g), abs(sampleValue.g - sampleValue.b)),
        abs(sampleValue.b - sampleValue.r)
    );
    float useMsdf = smoothstep(0.015, 0.12, disagreement);
    return mix(sdfDistance, msdfDistance, useMsdf);
}

void main() {
    float pxRange = screenPxRange();
    float alpha = distToAlpha(mtsdfDistance(texCoord0), pxRange);

    if (pxRange < 1.2) {
        vec2 sampleOffset = max(fwidth(texCoord0) * 0.35, vec2(1e-6));
        float neighborAlpha =
                distToAlpha(mtsdfDistance(texCoord0 + vec2(sampleOffset.x, 0.0)), pxRange) +
                distToAlpha(mtsdfDistance(texCoord0 - vec2(sampleOffset.x, 0.0)), pxRange) +
                distToAlpha(mtsdfDistance(texCoord0 + vec2(0.0, sampleOffset.y)), pxRange) +
                distToAlpha(mtsdfDistance(texCoord0 - vec2(0.0, sampleOffset.y)), pxRange);
        neighborAlpha *= 0.25;

        float blend = 1.0 - smoothstep(1.0, 1.2, pxRange);
        alpha = mix(alpha, (alpha * 0.90) + (neighborAlpha * 0.10), blend);
    }

    vec4 color = vertexColor * ColorModulator;
    if (color.a * alpha < 0.004) discard;
    fragColor = vec4(color.rgb, color.a * alpha);
}
