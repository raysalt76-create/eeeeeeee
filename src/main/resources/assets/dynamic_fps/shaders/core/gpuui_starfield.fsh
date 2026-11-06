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

void main() {
    vec2 uv = v_texCoord;
    float t = params[0].z;
    vec3 tint = params[1].rgb;
    float op = clamp(params[1].a, 0.0, 1.0);

    float aspect = max(1.0, params[0].x / max(1.0, params[0].y));
    vec2 p = vec2(uv.x * aspect, uv.y);

    // Three parallax layers of twinkling stars drifting slowly.
    float stars = 0.0;
    for (int l = 0; l < 3; l++) {
        float fl = float(l);
        vec2 drift = vec2(t * 0.012 * (1.0 + fl), -t * 0.006 * (1.0 + fl));
        float scale = 38.0 + fl * 52.0;
        vec2 g = (p + drift) * scale;
        vec2 cell = floor(g);
        vec2 f = fract(g);
        float h = hash(cell + vec2(fl * 23.0));
        if (h > 0.90) {
            vec2 center = vec2(hash(cell + vec2(1.0)), hash(cell + vec2(2.0)));
            float d = length(f - center);
            float tw = 0.45 + 0.55 * sin(t * (1.5 + 3.0 * h) + h * 6.2831);
            float s = (1.0 - smoothstep(0.0, 0.09, d)) * max(0.0, tw) * (0.4 + 0.6 * (1.0 - fl / 3.0));
            stars += s;
        }
    }
    float alpha = clamp(stars, 0.0, 1.0) * op * 0.85;
    fragColor = vec4(tint, alpha);
}
