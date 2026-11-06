#version 330

in vec2 v_texCoord;
out vec4 fragColor;

layout(std140) uniform UiParameters {
    vec4 params[16];
};

// Layered volumetric mist: several depth slices of defined, self-shadowed vapor, composited front to back.
// params[0]: target width, height, elapsed seconds, frame delta.
// params[1]: opacity, drift speed, scale, parallax depth.
// params[2]: cursor UV (outside 0..1 disables parallax).
// params[3].rgb: mist tint.

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
    float f = 0.5 * noise(p); p = ROT * p * 2.02;
    f += 0.25 * noise(p);     p = ROT * p * 2.03;
    f += 0.125 * noise(p);    p = ROT * p * 2.01;
    f += 0.0625 * noise(p);
    return f / 0.9375;
}

// Density of one slice: warped fbm pushed through a steep ramp so the vapor has a readable edge.
float density(vec2 p, vec2 warpSeed, float t) {
    vec2 q = vec2(fbm(p * 0.7 + warpSeed + vec2(t * 0.6, 0.0)),
                  fbm(p * 0.7 - warpSeed + vec2(0.0, t * 0.5)));
    float d = fbm(p + 1.8 * (q - 0.5) + vec2(t, -t * 0.4));
    return smoothstep(0.40, 0.78, d);
}

void main() {
    vec2 uv = v_texCoord;
    vec2 size = max(params[0].xy, vec2(1.0));
    float aspect = max(0.25, size.x / size.y);
    float time = params[0].z * clamp(params[1].y, 0.0, 2.0) * 0.06;
    float scale = max(0.5, params[1].z);
    float depthAmount = clamp(params[1].w, 0.0, 1.0);
    vec3 tint = params[3].rgb;

    vec2 cursor = params[2].xy;
    float cursorInside = step(0.0, cursor.x) * step(cursor.x, 1.0) * step(0.0, cursor.y) * step(cursor.y, 1.0);
    vec2 shift = (vec2(0.5) - cursor) * cursorInside;

    // Light comes from the upper left; shading samples the same slice a short step toward it.
    vec2 lightStep = vec2(-0.045, -0.060) / scale;

    vec3 accum = vec3(0.0);
    float transmittance = 1.0;

    // Far to near: far slices are broad, slow, dim and cool; near slices are tight, quick, bright and warm.
    for (int i = 0; i < 5; i++) {
        float z = float(i) / 4.0;                       // 0 far, 1 near
        float slicePar = mix(0.012, 0.075, z) * depthAmount;
        vec2 suv = uv + shift * slicePar;
        vec2 p = vec2(suv.x * aspect, suv.y) * scale * mix(0.75, 1.55, z) + vec2(float(i) * 7.3, float(i) * 3.1);
        float t = time * mix(0.45, 1.25, z);

        float d = density(p, vec2(1.7 + float(i), 9.2 - float(i)), t);
        float dl = density(p + lightStep, vec2(1.7 + float(i), 9.2 - float(i)), t);

        // Lit crests, shadowed undersides: the density gradient toward the light reads as volume.
        float lit = clamp(0.55 + (d - dl) * 2.6, 0.0, 1.0);
        float core = smoothstep(0.55, 1.0, d);          // thick cores glow slightly from in-scatter
        vec3 shadowCol = tint * 0.28;
        vec3 litCol = mix(tint, vec3(0.96, 0.97, 0.98), 0.55);
        vec3 col = mix(shadowCol, litCol, lit) * (0.80 + 0.35 * core);
        col = mix(col, col * vec3(0.82, 0.90, 1.06), 1.0 - z);

        float a = d * mix(0.20, 0.34, z);
        accum += transmittance * a * col;
        transmittance *= 1.0 - a;
    }

    float alpha = 1.0 - transmittance;
    vec3 color = accum / max(alpha, 1e-4);

    // Dissipate before the window border so mist never hard-clips against the frame.
    vec2 fade = smoothstep(vec2(0.0), vec2(0.07), uv) * smoothstep(vec2(1.0), vec2(0.93), uv);
    alpha *= fade.x * fade.y * clamp(params[1].x, 0.0, 1.0);

    fragColor = vec4(color, clamp(alpha, 0.0, 0.62));
}
