#version 330

in vec2 v_texCoord;
out vec4 fragColor;

layout(std140) uniform UiParameters {
    vec4 params[16];
};

// params[0]: width, height, time, delta. params[1]: opacity, speed, scale, ground bias.
// params[2].rgb: fog tint.
float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float noise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1, 0)), f.x),
               mix(hash(i + vec2(0, 1)), hash(i + vec2(1, 1)), f.x), f.y);
}

float fbm(vec2 p) {
    float v = 0.0, a = 0.5;
    mat2 r = mat2(0.8, -0.6, 0.6, 0.8);
    for (int i = 0; i < 5; i++) {
        v += a * noise(p);
        p = r * p * 2.03 + 7.1;
        a *= 0.5;
    }
    return v;
}

void main() {
    vec2 uv = v_texCoord;
    float aspect = clamp(params[0].x / max(params[0].y, 1.0), 0.6, 3.5);
    float t = params[0].z * clamp(params[1].y, 0.0, 2.0) * 0.05;
    float scale = max(params[1].z, 0.4);
    vec2 p = vec2(uv.x * aspect, uv.y) * scale;

    // Domain-warped, two-depth drift: far layer slow, near layer faster and opposite.
    vec2 warp = vec2(fbm(p + vec2(t, -t * 0.6)), fbm(p + vec2(5.2 - t * 0.8, 1.3 + t)));
    float far = fbm(p * 0.7 + warp * 1.6 + vec2(t * 0.5, 0.0));
    float near = fbm(p * 1.5 + warp * 2.2 - vec2(t * 1.1, t * 0.3));
    float density = far * 0.62 + near * 0.38;
    density = smoothstep(0.34, 0.82, density);

    // Ground-heavy falloff gives volume: thicker at the bottom, wisps rising.
    float ground = mix(1.0, 0.25 + 0.95 * smoothstep(0.0, 1.0, uv.y), clamp(params[1].w, 0.0, 1.0));
    vec3 tint = params[2].rgb;
    vec3 color = mix(tint, vec3(0.86, 0.92, 1.0), 0.45 + 0.35 * near);
    float edgeFade = smoothstep(0.0, 0.10, uv.x) * (1.0 - smoothstep(0.90, 1.0, uv.x))
                   * smoothstep(0.0, 0.08, uv.y) * (1.0 - smoothstep(0.92, 1.0, uv.y));
    float alpha = clamp(density * ground * clamp(params[1].x, 0.0, 1.0) * edgeFade, 0.0, 0.5);
    fragColor = vec4(color, alpha);
}
