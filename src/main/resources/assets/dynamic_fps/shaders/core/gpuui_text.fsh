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
    vec2 unitRange = vec2(32.0 / 4096.0);
    vec2 fwidthCoord = max(fwidth(texCoord), vec2(1.0e-5));
    vec2 screenTexSize = vec2(1.0) / fwidthCoord;
    float sRange = max(0.5 * dot(unitRange, screenTexSize), 1.0);

    // Standard screen-space MSDF reconstruction: one median sample and a
    // derivative-scaled transition keep small glyphs crisp without distorting stems.
    float signedDistance = median3(texture(Sampler0, texCoord).rgb) - 0.5;
    float screenDistance = signedDistance * sRange;
    float coverage = clamp(screenDistance + 0.5, 0.0, 1.0);

    vec2 maskUv = gl_FragCoord.xy / vec2(textureSize(Sampler1, 0));
    float clipCoverage = texture(Sampler1, maskUv).r;
    float alpha = vertexColor.a * ColorModulator.a * coverage * clipCoverage;
    if (alpha <= 0.001) discard;

    fragColor = vec4(vertexColor.rgb * ColorModulator.rgb, alpha);
}
