#version 330

// Water atmosphere for the ClickGUI: a lit water surface seen from above. A height field (directional swells plus
// drifting fBm chop, plus ripples spreading from the cursor) is shaded per pixel: diffuse light, sun glints, a soft
// fresnel sheen and moving caustics, over a slow gradient through up to three user colours. It is stateless and
// full-resolution, so the pane is always full and never smears, fades out or bands. Straight-alpha output.
//
// params[0] = [width, height, time, delta]   (written by the renderer)
// params[1] = [mouseU, mouseV, velX, velY]   (cursor in 0..1 UV, top-left origin, + smoothed velocity)
// params[2] = [c1.r, c1.g, c1.b, opacity]
// params[3] = [c2.r, c2.g, c2.b, colourCount]
// params[4] = [c3.r, c3.g, c3.b, flowSpeed]
// params[5] = [mouseRipple, 0, 0, 0]

in vec2 v_texCoord;
out vec4 fragColor;

layout(std140) uniform UiParameters {
    vec4 params[16];
};

float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}

const mat2 ROT = mat2(0.80, 0.60, -0.60, 0.80);

float fbm(vec2 p) {
    float f = 0.0;
    f += 0.5000 * noise(p); p = ROT * p * 2.02;
    f += 0.2500 * noise(p); p = ROT * p * 2.03;
    f += 0.1250 * noise(p); p = ROT * p * 2.01;
    f += 0.0625 * noise(p);
    return f / 0.9375;
}

// Surface height at p (pane units: x scaled by aspect). Values stay roughly in [-1, 1].
float waves(vec2 p, float t) {
    float h = 0.0;
    h += 0.26 * sin(dot(p, vec2(0.80, 0.60)) * 5.0 + t * 1.10);
    h += 0.18 * sin(dot(p, vec2(-0.55, 0.83)) * 7.5 - t * 1.35);
    h += 0.12 * sin(dot(p, vec2(0.96, -0.28)) * 11.0 + t * 1.80);
    h += 0.42 * (fbm(p * 2.6 + vec2(t * 0.22, t * 0.15)) - 0.5);
    h += 0.20 * (fbm(p * 6.0 - vec2(t * 0.30, -t * 0.20)) - 0.5);
    return h;
}

// Rings spreading from the cursor; strength fades to zero when the cursor rests.
float ripples(vec2 p, vec2 m, float strength, float t) {
    float d = length(p - m);
    return strength * sin(d * 48.0 - t * 8.0) * exp(-d * 7.0) * smoothstep(0.0, 0.03, d);
}

float height(vec2 p, vec2 m, float strength, float t) {
    return waves(p, t) + ripples(p, m, strength, t);
}

vec3 palette(float k, vec3 c1, vec3 c2, vec3 c3, float count) {
    if (count < 1.5) return c1;
    if (count < 2.5) return mix(c1, c2, smoothstep(0.2, 0.8, k));
    float a = smoothstep(0.0, 0.5, k);
    float b = smoothstep(0.5, 1.0, k);
    return mix(mix(c1, c2, a), c3, b);
}

void main() {
    vec2 uv = v_texCoord;
    float w = max(1.0, params[0].x);
    float hgt = max(1.0, params[0].y);
    float aspect = w / hgt;
    float flow = max(0.05, params[4].w);
    float t = params[0].z * 0.55 * flow;

    vec2 p = vec2(uv.x * aspect, uv.y);
    vec2 m = vec2(params[1].x * aspect, params[1].y);
    vec2 vel = params[1].zw;
    float rippleOn = params[5].x;
    float strength = rippleOn * clamp(length(vel) * 60.0, 0.0, 1.0) * 0.35;

    // Surface normal from central differences, about one screen pixel apart.
    float e = 1.25 / hgt;
    float hC = height(p, m, strength, t);
    float hX = height(p + vec2(e, 0.0), m, strength, t) - height(p - vec2(e, 0.0), m, strength, t);
    float hY = height(p + vec2(0.0, e), m, strength, t) - height(p - vec2(0.0, e), m, strength, t);
    float bumpScale = 0.045 / e;
    vec3 n = normalize(vec3(-hX * bumpScale * 0.5, -hY * bumpScale * 0.5, 1.0));

    // Body colour: a slow gradient through the user's colours, darker in the troughs.
    vec3 c1 = params[2].rgb;
    vec3 c2 = params[3].rgb;
    vec3 c3 = params[4].rgb;
    float count = params[3].w;
    float k = clamp(fbm(p * 0.9 + vec2(t * 0.05, -t * 0.04)) * 1.25 - 0.12, 0.0, 1.0);
    vec3 body = palette(k, c1, c2, c3, count);
    vec3 deep = body * 0.38;
    vec3 shallow = body * 1.05 + vec3(0.03, 0.05, 0.07);
    vec3 col = mix(deep, shallow, clamp(0.55 + hC * 0.55, 0.0, 1.0));

    // Light from the upper left, viewer straight above.
    vec3 L = normalize(vec3(-0.45, 0.55, 0.70));
    vec3 V = vec3(0.0, 0.0, 1.0);
    float diffuse = clamp(dot(n, L), 0.0, 1.0);
    col *= 0.62 + 0.48 * diffuse;

    // Caustics: bright filaments refracted onto the "floor", shifted by the surface slope.
    vec2 cp = p * 3.4 + n.xy * 0.9 + vec2(t * 0.12, t * 0.09);
    float ca = 1.0 - abs(sin(fbm(cp) * 6.2831 + t * 0.6));
    float cb = 1.0 - abs(sin(fbm(cp * 1.7 + 3.1) * 6.2831 - t * 0.5));
    float caustic = pow(ca, 7.0) * 0.55 + pow(cb, 9.0) * 0.35;
    col += mix(body, vec3(1.0), 0.55) * caustic * 0.32;

    // Specular glints and a broad sheen.
    vec3 R = reflect(-L, n);
    float rv = clamp(dot(R, V), 0.0, 1.0);
    col += vec3(1.0) * pow(rv, 120.0) * 0.85;
    col += mix(body, vec3(1.0), 0.6) * pow(rv, 14.0) * 0.12;

    // Fresnel-like brightening where the surface tilts away.
    float fres = pow(1.0 - clamp(n.z, 0.0, 1.0), 2.0);
    col += vec3(0.75, 0.85, 1.0) * fres * 0.9;

    // Soft edge so the water dissolves just inside the window border.
    vec2 edge = smoothstep(vec2(0.0), vec2(0.04), uv) * smoothstep(vec2(1.0), vec2(0.96), uv);
    float opacity = clamp(params[2].w, 0.0, 1.0) * edge.x * edge.y;
    opacity *= clamp(0.78 + caustic * 0.25 + pow(rv, 14.0) * 0.2, 0.0, 1.0);

    fragColor = vec4(clamp(col, 0.0, 1.0), clamp(opacity, 0.0, 1.0));
}
