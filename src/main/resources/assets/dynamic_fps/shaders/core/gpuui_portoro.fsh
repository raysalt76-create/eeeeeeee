#version 330

in vec2 v_texCoord;
out vec4 fragColor;

layout(std140) uniform UiParameters {
    vec4 params[16];
};

// params[0]: framebuffer width, height, elapsed seconds, frame delta.
// params[1]: opacity, surface detail, gold vein strength, glint speed.
// params[2]: cursor UV, cursor light, cursor depth toggle.

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float noise(vec2 p) {
    vec2 cell = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);

    float a = hash21(cell);
    float b = hash21(cell + vec2(1.0, 0.0));
    float c = hash21(cell + vec2(0.0, 1.0));
    float d = hash21(cell + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float segmentDistance(vec2 point, vec2 start, vec2 end) {
    vec2 segment = end - start;
    float t = clamp(dot(point - start, segment) / max(dot(segment, segment), 0.00001), 0.0, 1.0);
    return length(point - (start + segment * t));
}

void main() {
    vec2 uv = v_texCoord;
    float aspect = max(0.25, params[0].x / max(params[0].y, 1.0));
    vec2 cursor = params[2].xy;
    float cursorInside = step(0.0, cursor.x) * step(cursor.x, 1.0)
            * step(0.0, cursor.y) * step(cursor.y, 1.0);
    vec2 cursorDelta = (uv - cursor) * vec2(aspect, 1.0);
    float cursorDistanceSq = dot(cursorDelta, cursorDelta);
    float depth = clamp(params[2].w, 0.0, 1.0) * cursorInside;
    vec2 depthOffset = (vec2(0.5) - cursor) * (0.010 * depth)
            + (uv - cursor) * (0.008 * exp(-cursorDistanceSq * 12.0) * depth);

    vec2 stone = (uv + depthOffset - 0.5) * vec2(aspect, 1.0) * 6.6;
    // Broad, low-frequency warping gives the fractured seams the long bends of natural stone.
    vec2 broadWarp = vec2(
            noise(stone * 0.30 + vec2(2.7, 8.1)),
            noise(stone * 0.30 + vec2(12.4, 1.9)));
    vec2 fineWarp = vec2(
            noise(stone * 0.67 + broadWarp * 1.25 + vec2(7.3, 3.2)),
            noise(stone * 0.67 + broadWarp * 1.25 + vec2(1.4, 11.8)));
    vec2 warped = stone + (broadWarp - 0.5) * 3.0 + (fineWarp - 0.5) * 1.0;

    float cloud = noise(warped * 0.54 + vec2(9.7, 4.2));
    float mineral = noise(warped * 2.2 + vec2(5.3, 14.6));
    float detail = clamp(params[1].y, 0.0, 1.0);
    float grain = mineral - 0.5;

    // Explicit, irregular fault paths keep the gold veins legible instead of relying on noise alone.
    vec2 pathWarp = vec2(
            noise(warped * 0.24 + vec2(18.2, 5.7)),
            noise(warped * 0.24 + vec2(3.1, 16.4))) - 0.5;
    vec2 veinPoint = (uv + depthOffset + pathWarp * 0.018) * vec2(aspect, 1.0);
    vec2 a = vec2(0.02 * aspect, 0.82);
    vec2 b = vec2(0.15 * aspect, 0.70);
    vec2 c = vec2(0.22 * aspect, 0.57);
    vec2 d = vec2(0.39 * aspect, 0.51);
    vec2 e = vec2(0.56 * aspect, 0.55);
    vec2 f = vec2(0.68 * aspect, 0.42);
    vec2 g = vec2(0.84 * aspect, 0.36);
    vec2 h = vec2(0.98 * aspect, 0.18);
    float primaryVein = min(min(min(segmentDistance(veinPoint, a, b), segmentDistance(veinPoint, b, c)),
            min(segmentDistance(veinPoint, c, d), segmentDistance(veinPoint, d, e))),
            min(min(segmentDistance(veinPoint, e, f), segmentDistance(veinPoint, f, g)),
                    segmentDistance(veinPoint, g, h)));

    vec2 i = vec2(0.03 * aspect, 0.18);
    vec2 j = vec2(0.17 * aspect, 0.30);
    vec2 k = vec2(0.34 * aspect, 0.33);
    vec2 l = vec2(0.45 * aspect, 0.26);
    vec2 m = vec2(0.61 * aspect, 0.32);
    vec2 n = vec2(0.75 * aspect, 0.47);
    vec2 o = vec2(0.98 * aspect, 0.55);
    float crossVein = min(
            min(min(segmentDistance(veinPoint, i, j), segmentDistance(veinPoint, j, k)),
                    min(segmentDistance(veinPoint, k, l), segmentDistance(veinPoint, l, m))),
            min(segmentDistance(veinPoint, m, n), segmentDistance(veinPoint, n, o)));
    float mainDistance = min(primaryVein, crossVein);

    float branchGate = 0.68 + 0.32 * smoothstep(0.26, 0.78, mineral * 0.68 + cloud * 0.32);
    float branchDistance = min(min(segmentDistance(veinPoint, d, vec2(0.33 * aspect, 0.40)),
            segmentDistance(veinPoint, vec2(0.33 * aspect, 0.40), vec2(0.25 * aspect, 0.37))),
            min(segmentDistance(veinPoint, e, vec2(0.56 * aspect, 0.68)),
                    min(segmentDistance(veinPoint, vec2(0.56 * aspect, 0.68), vec2(0.66 * aspect, 0.78)),
                            segmentDistance(veinPoint, g, vec2(0.82 * aspect, 0.22)))));

    float mainHalo = 1.0 - smoothstep(0.012, 0.060, mainDistance);
    float mainBody = 1.0 - smoothstep(0.003, 0.024, mainDistance);
    float mainCore = 1.0 - smoothstep(0.0008, 0.007, mainDistance);
    float branchBody = (1.0 - smoothstep(0.002, 0.018, branchDistance)) * branchGate;
    float branchCore = (1.0 - smoothstep(0.0007, 0.006, branchDistance)) * branchGate;

    // Nero Portoro's charcoal matrix carries restrained warm mineral variation and a polished sheen.
    float polish = pow(clamp(1.0 - abs(cloud - 0.52) * 2.2, 0.0, 1.0), 5.0);
    vec3 color = vec3(0.010, 0.011, 0.013)
            + vec3(0.030, 0.025, 0.018) * (cloud - 0.42)
            + vec3(0.010, 0.008, 0.005) * (mineral - 0.5)
            + vec3(0.016, 0.014, 0.011) * polish
            + vec3(0.006, 0.005, 0.004) * grain * detail;
    color = max(color, vec3(0.004, 0.005, 0.006));

    float veinStrength = clamp(params[1].z, 0.0, 1.0);
    float mineralVariation = 0.76 + 0.24 * mineral;
    float halo = clamp((mainHalo * 0.12 + branchBody * 0.055) * veinStrength, 0.0, 0.22);
    float body = clamp((mainBody * 0.72 + branchBody * 0.24) * mineralVariation * veinStrength,
            0.0, 0.78);
    float core = clamp((mainCore * 0.78 + branchCore * 0.23) * mineralVariation * veinStrength,
            0.0, 0.82);
    color = mix(color, vec3(0.20, 0.125, 0.052), halo);
    color = mix(color, vec3(0.54, 0.35, 0.14), body * 0.72);
    color = mix(color, vec3(0.92, 0.73, 0.39), core * 0.78);

    // Small, slow glints add depth without turning the veins into neon streaks.
    float alongVein = dot(warped, vec2(0.78, -0.62));
    float glintPhase = fract(alongVein * 0.16 - params[0].z * clamp(params[1].w, 0.0, 1.0) * 0.035);
    float glint = 1.0 - smoothstep(0.015, 0.14, abs(glintPhase - 0.5));
    color += vec3(0.095, 0.061, 0.025) * core * glint * 0.22;

    float hoverField = (1.0 - smoothstep(0.035, 0.25, sqrt(cursorDistanceSq))) * cursorInside;
    float hoverGold = clamp(params[2].z, 0.0, 1.0) * hoverField;
    color += vec3(0.13, 0.086, 0.034) * (body * hoverGold * 0.34);

    // Alpha preserves translucency so the material naturally layers over the captured scene.
    fragColor = vec4(color, clamp(params[1].x, 0.0, 1.0));
}
