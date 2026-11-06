#version 330

// Composites the simulated dye field into a rounded-rectangle GUI pane.
// Sampler0 = dye field, Sampler1 = rounded clip mask (1x1 white when none).
// Paired with the shared gpuui_liquid.vsh vertex shader.
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

in vec4 vertexColor;       // rgb = tint, a = opacity
in vec2 localPosition;     // pixels within the pane
flat in vec2 rectangleSize;
flat in float cornerRadius;
out vec4 fragColor;

void main() {
    vec2 halfSize = rectangleSize * 0.5;
    float radius = clamp(cornerRadius, 0.0, min(halfSize.x, halfSize.y));
    vec2 pp = localPosition - halfSize;
    vec2 q = abs(pp) - (halfSize - vec2(radius));
    float dist = length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
    float aa = max(fwidth(dist), 0.001);
    float coverage = 1.0 - smoothstep(-aa, aa, dist);
    if (coverage <= 0.0) discard;

    // Material render targets are stored bottom-up, so flip V like the Material draw path does.
    vec2 uv = vec2(localPosition.x / max(rectangleSize.x, 1.0),
                   1.0 - localPosition.y / max(rectangleSize.y, 1.0));
    vec4 dye = texture(Sampler0, clamp(uv, 0.0, 1.0));

    vec2 maskSize = vec2(textureSize(Sampler1, 0));
    float maskCoverage = maskSize.x > 1.5
            ? texture(Sampler1, clamp(gl_FragCoord.xy / maskSize, vec2(0.0), vec2(1.0))).r
            : 1.0;

    vec3 rgb = dye.rgb * vertexColor.rgb;
    float alpha = clamp(dye.a, 0.0, 1.0) * vertexColor.a * coverage * maskCoverage * ColorModulator.a;
    fragColor = vec4(rgb * ColorModulator.rgb, alpha);
}
