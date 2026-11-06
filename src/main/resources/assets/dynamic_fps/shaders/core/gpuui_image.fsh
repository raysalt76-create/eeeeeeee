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
in vec2 localPosition;
flat in vec2 rectangleSize;
flat in float cornerRadius;
out vec4 fragColor;

float roundedRectDistance(vec2 position, vec2 size, float radius) {
    vec2 halfSize = size * 0.5;
    float safeRadius = clamp(radius, 0.0, min(halfSize.x, halfSize.y));
    vec2 q = abs(position - halfSize) - (halfSize - vec2(safeRadius));
    return length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - safeRadius;
}

void main() {
    float distanceToEdge = roundedRectDistance(localPosition, rectangleSize, cornerRadius);
    float edgeWidth = max(fwidth(distanceToEdge), 1.0e-4);
    float shapeCoverage = 1.0 - smoothstep(-edgeWidth, edgeWidth, distanceToEdge);
    vec2 maskUv = gl_FragCoord.xy / vec2(textureSize(Sampler1, 0));
    float clipCoverage = texture(Sampler1, maskUv).r;
    vec4 imageColor = texture(Sampler0, texCoord);
    float alpha = imageColor.a * vertexColor.a * ColorModulator.a * shapeCoverage * clipCoverage;
    if (alpha <= 0.0) discard;
    fragColor = vec4(imageColor.rgb * vertexColor.rgb * ColorModulator.rgb, alpha);
}
