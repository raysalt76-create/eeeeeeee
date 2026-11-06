#version 330

in vec2 v_texCoord;
out vec4 fragColor;

layout(std140) uniform UiParameters {
    vec4 params[16];
};

void main() {
    float time = params[0].z;
    float speed = max(0.05, params[1].x);
    float phase = fract(time * speed);
    float distanceToSweep = abs(v_texCoord.x - (phase * 1.6 - 0.3) + v_texCoord.y * 0.15);
    float sweep = 1.0 - smoothstep(0.0, 0.16, distanceToSweep);
    float edge = smoothstep(0.0, 0.35, v_texCoord.y) * (1.0 - smoothstep(0.55, 1.0, v_texCoord.y));
    fragColor = vec4(0.55, 0.78, 1.0, sweep * edge * 0.13);
}
