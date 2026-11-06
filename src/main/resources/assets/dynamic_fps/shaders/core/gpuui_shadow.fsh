#version 330

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

uniform sampler2D Sampler1;

in vec4 vertexColor;
in vec2 localPosition;
flat in vec2 rectangleSize;
flat in float cornerRadius;
flat in vec2 shadowEffects;
out vec4 fragColor;

void main() {
    vec2 halfSize = rectangleSize * 0.5;
    float radius = clamp(cornerRadius, 0.0, min(halfSize.x, halfSize.y));
    vec2 q = abs(localPosition - halfSize) - (halfSize - vec2(radius));
    float dist = length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
    float spread = shadowEffects.y;
    float softness = shadowEffects.x;
    float shadowDistance = dist - spread;
    float edgeWidth = max(fwidth(shadowDistance), 0.35);
    float falloff = max(softness, edgeWidth);
    float coverage = 1.0 - smoothstep(-edgeWidth, falloff, shadowDistance);
    vec2 maskUv = gl_FragCoord.xy / vec2(textureSize(Sampler1, 0));
    float clipCoverage = texture(Sampler1, maskUv).r;
    float alpha = vertexColor.a * ColorModulator.a * coverage * clipCoverage;
    if (alpha <= 0.0) discard;
    fragColor = vec4(vertexColor.rgb * ColorModulator.rgb, alpha);
}
