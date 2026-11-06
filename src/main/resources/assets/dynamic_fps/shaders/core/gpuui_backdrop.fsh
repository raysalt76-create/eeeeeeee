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
in vec2 localPosition;
flat in vec2 rectangleSize;
flat in float cornerRadius;
flat in float blurStrength;
out vec4 fragColor;

void main() {
    vec2 halfSize = rectangleSize * 0.5;
    float radius = clamp(cornerRadius, 0.0, min(halfSize.x, halfSize.y));
    vec2 q = abs(localPosition - halfSize) - (halfSize - vec2(radius));
    float dist = length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
    float aa = max(fwidth(dist), 0.001);
    float coverage = 1.0 - smoothstep(-aa, aa, dist);
    if (coverage <= 0.0) discard;

    // The blur chain upsamples back to framebuffer resolution, so pixels map one-to-one.
    vec2 maskSize = vec2(textureSize(Sampler1, 0));
    vec2 sceneSize = vec2(textureSize(Sampler0, 0));
    vec2 effectUv = gl_FragCoord.xy / max(sceneSize, vec2(1.0));
    vec4 blurred = texture(Sampler0, clamp(effectUv, vec2(0.0), vec2(1.0)));

    // Clip masks are native framebuffer textures; if unclipped (1x1 fallback), full coverage is used.
    float maskCoverage = maskSize.x > 1.5 ? texture(Sampler1, clamp(gl_FragCoord.xy / maskSize, vec2(0.0), vec2(1.0))).r : 1.0;
    float alpha = vertexColor.a * ColorModulator.a * coverage * maskCoverage;
    vec3 rgb = blurred.rgb * vertexColor.rgb * ColorModulator.rgb;
    if (vertexColor.a < 0.999) rgb *= vertexColor.a;
    // Triangular dither hides 8-bit banding in wide, low-contrast blurred gradients.
    vec2 ditherSeed = gl_FragCoord.xy;
    float n1 = fract(sin(dot(ditherSeed, vec2(12.9898, 78.233))) * 43758.5453);
    float n2 = fract(sin(dot(ditherSeed + 0.37, vec2(39.3468, 11.1352))) * 24634.6345);
    rgb += vec3((n1 + n2 - 1.0) / 255.0);
    fragColor = vec4(rgb, alpha);
}
