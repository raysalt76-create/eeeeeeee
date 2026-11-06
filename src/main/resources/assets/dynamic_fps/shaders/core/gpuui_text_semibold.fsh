#version 330

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
in vec4 vertexColor;
in vec2 texCoord;
out vec4 fragColor;

float median3(vec3 value) {
    return max(min(value.r, value.g), min(max(value.r, value.g), value.b));
}

void main() {
    vec3 sampleValue = texture(Sampler0, texCoord).rgb;
    // Move the MSDF edge inward to thicken strokes without changing glyph spacing.
    float signedDistance = median3(sampleValue) - 0.44;
    vec2 unitRange = vec2(32.0 / 4096.0);
    vec2 screenTexSize = 1.0 / max(fwidth(texCoord), vec2(1.0e-6));
    float screenRange = max(0.5 * dot(unitRange, screenTexSize), 1.0);
    float coverage = clamp(signedDistance * screenRange + 0.5, 0.0, 1.0);
    vec2 maskUv = gl_FragCoord.xy / vec2(textureSize(Sampler1, 0));
    float clipCoverage = texture(Sampler1, maskUv).r;
    float alpha = vertexColor.a * ColorModulator.a * coverage * clipCoverage;
    if (alpha <= 0.0) discard;
    fragColor = vec4(vertexColor.rgb * ColorModulator.rgb, alpha);
}
