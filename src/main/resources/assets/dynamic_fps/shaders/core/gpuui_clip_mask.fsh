#version 330

uniform sampler2D Sampler0;

layout(std140) uniform UiParameters {
    vec4 params[16];
};

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 targetSize = max(params[0].xy, vec2(1.0));
    vec2 guiSize = max(vec2(params[3].w, params[4].x), vec2(1.0));
    vec2 postPose = vec2(gl_FragCoord.x * guiSize.x / targetSize.x,
                         (targetSize.y - gl_FragCoord.y) * guiSize.y / targetSize.y);

    vec2 localPosition;
    localPosition.x = postPose.x * params[2].y + postPose.y * params[2].w + params[3].y;
    localPosition.y = postPose.x * params[2].z + postPose.y * params[3].x + params[3].z;

    vec4 clipRect = params[1];
    float radius = clamp(params[2].x, 0.0, min(clipRect.z, clipRect.w) * 0.5);
    vec2 halfSize = clipRect.zw * 0.5;
    vec2 q = abs(localPosition - clipRect.xy - halfSize) - (halfSize - vec2(radius));
    float distanceToClip = length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
    float edgeWidth = max(fwidth(distanceToClip), 1.0e-4);
    float coverage = 1.0 - smoothstep(-edgeWidth, edgeWidth, distanceToClip);

    vec2 previousUv = gl_FragCoord.xy / targetSize;
    coverage *= texture(Sampler0, previousUv).r;
    fragColor = vec4(coverage, coverage, coverage, coverage);
}
