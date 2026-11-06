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
flat in vec2 effectData;
out vec4 fragColor;

void main() {
    vec2 halfSize = rectangleSize * 0.5;
    float radius = clamp(cornerRadius, 0.0, min(halfSize.x, halfSize.y));
    vec2 q = abs(localPosition - halfSize) - (halfSize - vec2(radius));
    float dist = length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
    float edgeWidth = max(fwidth(dist), 1.0e-4);
    float outerCoverage = 1.0 - smoothstep(-edgeWidth, edgeWidth, dist);
    // UV2 is a signed-short vertex channel, so border width is sent as 1/256-pixel fixed point.
    float borderWidth = max(effectData.x / 256.0, 0.0);
    float innerCoverage = 0.0;
    vec2 innerSize = rectangleSize - vec2(borderWidth * 2.0);
    if (borderWidth > 0.0 && min(innerSize.x, innerSize.y) > 0.0) {
        float innerRadius = max(radius - borderWidth, 0.0);
        vec2 innerHalf = innerSize * 0.5;
        vec2 innerQ = abs(localPosition - halfSize) - (innerHalf - vec2(innerRadius));
        float innerDistance = length(max(innerQ, vec2(0.0))) + min(max(innerQ.x, innerQ.y), 0.0) - innerRadius;
        float innerEdgeWidth = max(fwidth(innerDistance), 1.0e-4);
        innerCoverage = 1.0 - smoothstep(-innerEdgeWidth, innerEdgeWidth, innerDistance);
    }
    float coverage = outerCoverage * (1.0 - innerCoverage);
    vec2 maskUv = gl_FragCoord.xy / vec2(textureSize(Sampler1, 0));
    // Clip-mask only (like gpuui_shadow). The previous build also multiplied by
    // texture(Sampler0, 0.5).r, but solid rects bind an EMPTY Sampler0, so that term
    // returned 0 and discarded every rect/panel/border -- the invisible-GUI bug.
    coverage *= texture(Sampler1, maskUv).r;
    float alpha = vertexColor.a * ColorModulator.a * coverage;
    if (alpha <= 0.0) discard;
    fragColor = vec4(vertexColor.rgb * ColorModulator.rgb, alpha);
}
