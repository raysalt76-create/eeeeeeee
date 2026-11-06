#version 330 core

uniform sampler2D tex;
uniform vec2 texelSize;
uniform float offset;

in vec2 uv;
out vec4 fragColor;

void main() {
    vec2 step = texelSize * offset;
    vec4 sum = vec4(0.0);
    sum += texture(tex, uv + vec2(-step.x * 2.0, 0.0));
    sum += texture(tex, uv + vec2(-step.x, step.y)) * 2.0;
    sum += texture(tex, uv + vec2(0.0, step.y * 2.0));
    sum += texture(tex, uv + vec2(step.x, step.y)) * 2.0;
    sum += texture(tex, uv + vec2(step.x * 2.0, 0.0));
    sum += texture(tex, uv + vec2(step.x, -step.y)) * 2.0;
    sum += texture(tex, uv + vec2(0.0, -step.y * 2.0));
    sum += texture(tex, uv + vec2(-step.x, -step.y)) * 2.0;
    fragColor = sum / 12.0;
}
