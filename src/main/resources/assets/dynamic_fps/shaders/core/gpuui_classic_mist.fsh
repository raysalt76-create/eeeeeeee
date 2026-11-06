#version 330

in vec2 v_texCoord;
out vec4 fragColor;

layout(std140) uniform UiParameters {
    vec4 params[16];
};

// Numerically stable 2D hash without trigonometric precision loss across GPU architectures
float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// 2D Smooth Hermite value noise
float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i + vec2(0.0, 0.0)),
                   hash(i + vec2(1.0, 0.0)), u.x),
               mix(hash(i + vec2(0.0, 1.0)),
                   hash(i + vec2(1.0, 1.0)), u.x), u.y);
}

// Rotated 4-octave Fractional Brownian Motion (fBm)
const mat2 ROT = mat2(0.80, 0.60, -0.60, 0.80);

float fbm(vec2 p) {
    float f = 0.0;
    f += 0.5000 * noise(p); p = ROT * p * 2.02;
    f += 0.2500 * noise(p); p = ROT * p * 2.03;
    f += 0.1250 * noise(p); p = ROT * p * 2.01;
    f += 0.0625 * noise(p);
    return f / 0.9375;
}

// Domain Warping for fluid curling smoke and atmospheric vapor tendrils
vec2 warp(vec2 p, float t) {
    vec2 q = vec2(fbm(p + vec2(t * 0.035, t * 0.02)),
                  fbm(p + vec2(5.2, 1.3) - vec2(t * 0.028, t * 0.022)));
    vec2 r = vec2(fbm(p + 3.2 * q + vec2(1.7, 9.2) + vec2(t * 0.045, -t * 0.032)),
                  fbm(p + 3.2 * q + vec2(8.3, 2.8) - vec2(t * 0.036, -t * 0.026)));
    return r;
}

void main() {
    vec2 uv = v_texCoord;
    float time = params[0].z * 0.40;

    // Aspect ratio correction so noise frequency remains uniform across wide rectangular panes
    float aspect = max(1.0, params[0].x / max(1.0, params[0].y));
    vec2 coord = vec2(uv.x * aspect, uv.y) * 2.6;

    // Domain-warped curl noise producing organic, flowing wisps
    vec2 flow = warp(coord, time);
    float wisps = fbm(coord + 2.4 * flow);
    wisps = smoothstep(0.30, 0.76, wisps);

    // Subtle macro vapor ambient presence for depth
    vec2 pA = (uv - vec2(0.18 + 0.08 * sin(time * 0.5), 0.32 + 0.06 * cos(time * 0.4))) / vec2(0.42, 0.48);
    vec2 pB = (uv - vec2(0.84 + 0.07 * cos(time * 0.4 + 1.2), 0.44 + 0.07 * sin(time * 0.35))) / vec2(0.44, 0.50);
    vec2 pC = (uv - vec2(0.50 + 0.09 * sin(time * 0.35 + 2.0), 0.90 + 0.05 * cos(time * 0.45))) / vec2(0.46, 0.38);

    float macroVapor = exp(-dot(pA, pA) * 2.5) * 0.18
                     + exp(-dot(pB, pB) * 2.5) * 0.15
                     + exp(-dot(pC, pC) * 2.5) * 0.12;

    // Combine layered wisps with ambient vapor
    float totalMist = macroVapor * 0.65 + wisps * 0.22;

    // Subtle edge fade so atmospheric mist dissipates gracefully before window borders
    vec2 vignette = smoothstep(vec2(0.0), vec2(0.06), uv) * smoothstep(vec2(1.0), vec2(0.94), uv);
    totalMist *= vignette.x * vignette.y;

    // Tactical luminous off-white tone (#f1f3f2)
    float opacity = clamp(totalMist * params[1].x, 0.0, 0.36);
    fragColor = vec4(vec3(0.95, 0.97, 0.96), opacity);
}
