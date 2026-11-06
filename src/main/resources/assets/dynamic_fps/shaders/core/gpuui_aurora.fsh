#version 330

in vec2 v_texCoord;
out vec4 fragColor;

layout(std140) uniform UiParameters {
    vec4 params[16];
};

// params[0]: width, height, time, delta. params[1].rgb: tint, params[1].a: opacity.
float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float noise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1, 0)), u.x),
               mix(hash(i + vec2(0, 1)), hash(i + vec2(1, 1)), u.x), u.y);
}

const mat2 ROT = mat2(0.80, 0.60, -0.60, 0.80);

float fbm(vec2 p) {
    float f = 0.0;
    f += 0.5000 * noise(p); p = ROT * p * 2.02;
    f += 0.2500 * noise(p); p = ROT * p * 2.03;
    f += 0.1250 * noise(p);
    return f / 0.875;
}

void main() {
    vec2 uv = v_texCoord;
    float t = params[0].z * 0.2;
    vec3 tint = params[1].rgb;
    float op = clamp(params[1].a, 0.0, 1.0);

    float aspect = max(1.0, params[0].x / max(1.0, params[0].y));
    float x = uv.x * aspect;

    // Three waving light curtains hung from the top, each drifting on its own fbm.
    float light = 0.0;
    for (int i = 0; i < 3; i++) {
        float fi = float(i);
        float wavy = fbm(vec2(x * 1.4 + fi * 3.1, t + fi * 2.0));
        float base = 0.22 + 0.16 * fi + 0.22 * wavy;      // curtain centre height (from top)
        float d = uv.y - base;
        float ribbon = exp(-d * d * (42.0 - fi * 10.0));  // soft glowing band
        float streak = 0.55 + 0.45 * fbm(vec2(x * 7.0, uv.y * 2.5 - t * 3.0)); // vertical streaks
        light += ribbon * streak * (0.7 - fi * 0.15);
    }
    light *= smoothstep(1.0, 0.15, uv.y);                 // dissipate toward the bottom

    vec3 col = mix(tint, tint.gbr, smoothstep(0.15, 0.6, uv.y)); // subtle hue roll down the curtain
    float edge = smoothstep(0.0, 0.05, uv.x) * smoothstep(1.0, 0.95, uv.x);
    float alpha = clamp(light, 0.0, 1.0) * op * 0.7 * edge;
    fragColor = vec4(col, alpha);
}
