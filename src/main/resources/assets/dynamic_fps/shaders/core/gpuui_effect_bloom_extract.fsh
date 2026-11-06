#version 330

uniform sampler2D Sampler0;

in vec2 v_texCoord;
in vec2 v_sourceUv;
out vec4 fragColor;

layout(std140) uniform UiParameters {
    vec4 params[16];
};

void main() {
    vec2 texel = 1.0 / vec2(textureSize(Sampler0, 0));
    vec2 d = texel * 0.5;
    vec4 color = texture(Sampler0, v_sourceUv + vec2(-d.x, -d.y));
    color += texture(Sampler0, v_sourceUv + vec2( d.x, -d.y));
    color += texture(Sampler0, v_sourceUv + vec2(-d.x,  d.y));
    color += texture(Sampler0, v_sourceUv + vec2( d.x,  d.y));
    color *= 0.25;

    float luminance = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
    float threshold = clamp(params[1].y, 0.0, 0.99);
    float contribution = smoothstep(threshold, min(1.0, threshold + 0.18), luminance);
    fragColor = vec4(color.rgb * contribution, color.a * contribution);
}
