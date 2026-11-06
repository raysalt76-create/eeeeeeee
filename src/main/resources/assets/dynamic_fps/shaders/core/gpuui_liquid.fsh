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

// Liquid glass: refracted + dispersed blurred backdrop, accent-tinted body, specular rim light.
// vertexColor.rgb = tint colour, vertexColor.a = tint strength.
void main() {
    vec2 halfSize = rectangleSize * 0.5;
    float radius = clamp(cornerRadius, 0.0, min(halfSize.x, halfSize.y));
    vec2 p = localPosition - halfSize;
    vec2 q = abs(p) - (halfSize - vec2(radius));
    float dist = length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
    float aa = max(fwidth(dist), 0.001);
    float coverage = 1.0 - smoothstep(-aa, aa, dist);
    if (coverage <= 0.0) discard;

    vec2 sgn = vec2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);
    vec2 n = (q.x > 0.0 && q.y > 0.0) ? normalize(q) : (q.x > q.y ? vec2(1.0, 0.0) : vec2(0.0, 1.0));
    n *= sgn;

    float bevel = max(4.0, min(22.0, min(halfSize.x, halfSize.y) * 0.55));
    float edge = 1.0 - clamp(-dist / bevel, 0.0, 1.0);
    float lens = edge * edge * (3.0 - 2.0 * edge);
    float ppu = 1.0 / max(fwidth(localPosition.x), 1.0e-4);

    vec2 maskSize = vec2(textureSize(Sampler1, 0));
    vec2 sceneSize = maskSize.x > 1.5 ? maskSize : vec2(textureSize(Sampler0, 0));
    vec2 uv = gl_FragCoord.xy / max(sceneSize, vec2(1.0));
    vec2 shift = -n * lens * bevel * 0.9 * ppu / max(sceneSize, vec2(1.0));
    vec2 disp = n * lens * 1.6 * ppu / max(sceneSize, vec2(1.0));
    vec3 bg = vec3(
        texture(Sampler0, clamp(uv + shift + disp, vec2(0.0), vec2(1.0))).r,
        texture(Sampler0, clamp(uv + shift, vec2(0.0), vec2(1.0))).g,
        texture(Sampler0, clamp(uv + shift - disp, vec2(0.0), vec2(1.0))).b);

    // Frosted body: slight desaturation + lift, then accent tint.
    float luma = dot(bg, vec3(0.299, 0.587, 0.114));
    vec3 body = mix(vec3(luma), bg, 0.85) * 0.92 + 0.035;
    body = mix(body, vertexColor.rgb, vertexColor.a);

    // Specular rim, brightest on the upper-left lit edge, plus a soft inner glow.
    vec2 light = normalize(vec2(-0.55, -0.83));
    float facing = max(dot(n, light), 0.0);
    float rim = (1.0 - smoothstep(0.0, 1.6 * aa + 1.1 / ppu * 1.4, -dist)) * (0.28 + 0.72 * facing);
    float glow = pow(edge, 3.0) * (0.10 + 0.22 * facing);
    float sheen = (1.0 - smoothstep(0.0, 1.0, (localPosition.y / max(rectangleSize.y, 1.0)))) * 0.045;
    vec3 rgb = body + vec3(rim * 0.55 + glow + sheen);

    float maskCoverage = maskSize.x > 1.5 ? texture(Sampler1, clamp(gl_FragCoord.xy / maskSize, vec2(0.0), vec2(1.0))).r : 1.0;
    fragColor = vec4(rgb * ColorModulator.rgb, coverage * maskCoverage * ColorModulator.a);
}
