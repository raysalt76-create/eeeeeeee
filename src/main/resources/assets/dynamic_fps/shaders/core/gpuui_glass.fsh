#version 330

in vec2 v_texCoord;
out vec4 fragColor;

layout(std140) uniform UiParameters {
    vec4 params[16];
};

// params[1]: opacity, top light, grain, shimmer speed.
float hash21(vec2 p) {
    p = fract(p * vec2(0.1031, 0.11369));
    p += dot(p, p.yx + 19.19);
    return fract((p.x + p.y) * p.x);
}

void main() {
    vec2 uv = v_texCoord;
    float opacity = clamp(params[1].x, 0.0, 1.0);
    float topLight = clamp(params[1].y, 0.0, 1.0);
    float grain = clamp(params[1].z, 0.0, 1.0);
    float speed = clamp(params[1].w, 0.0, 1.0);

    float edge = pow(1.0 - uv.y, 4.0) * topLight;
    float side = pow(1.0 - uv.x, 3.0) * 0.28;
    float diagonal = abs(uv.x - uv.y * 0.18 -
                         (0.45 + sin(params[0].z * speed * 0.22) * 0.18));
    float sheen = (1.0 - smoothstep(0.0, 0.23, diagonal)) * 0.18;

    // Screen-space grain stays fine at any target resolution and does not flicker.
    float noise = hash21(floor(uv * max(params[0].xy, vec2(1.0)))) - 0.5;
    float alpha = opacity * (edge * 0.11 + side * 0.035 + sheen * 0.055
                           + (noise + 0.5) * grain * 0.018);
    fragColor = vec4(vec3(0.78, 0.90, 1.0), clamp(alpha, 0.0, 0.15));
}
