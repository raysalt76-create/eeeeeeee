#version 330

uniform sampler2D Sampler0;

in vec2 v_texCoord;
in vec2 v_sourceUv;
out vec4 fragColor;

layout(std140) uniform UiParameters {
    vec4 params[16];
};

// Dual-filter Kawase upsample (tent). params[1].x is the sample offset in source half-texels.
void main() {
    vec2 halfTexel = 0.5 / vec2(textureSize(Sampler0, 0));
    vec2 o = halfTexel * max(params[1].x, 0.0);
    vec4 sum = texture(Sampler0, v_sourceUv + vec2(-o.x * 2.0, 0.0));
    sum += texture(Sampler0, v_sourceUv + vec2(o.x * 2.0, 0.0));
    sum += texture(Sampler0, v_sourceUv + vec2(0.0, -o.y * 2.0));
    sum += texture(Sampler0, v_sourceUv + vec2(0.0, o.y * 2.0));
    sum += texture(Sampler0, v_sourceUv + vec2(-o.x, o.y)) * 2.0;
    sum += texture(Sampler0, v_sourceUv + vec2(o.x, o.y)) * 2.0;
    sum += texture(Sampler0, v_sourceUv + vec2(-o.x, -o.y)) * 2.0;
    sum += texture(Sampler0, v_sourceUv + vec2(o.x, -o.y)) * 2.0;
    fragColor = sum / 12.0;
}
