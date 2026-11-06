#version 330

in vec2 v_texCoord;
out vec4 fragColor;

layout(std140) uniform UiParameters {
    vec4 params[16];
};

// Polished black granite with white marble veins and scattered gold dust.
// params[0]: target width, height, elapsed seconds, frame delta.
// params[1]: opacity, crystal detail, gold dust density, vein glow.
// params[2]: cursor UV, cursor light strength, cursor-depth toggle.
// params[3].rgb: vein colour, params[4].rgb: inclusion (dust and seam) colour, params[5].rgb: stone body
//   colour. All three zero keeps the original black granite (white veins, gold inclusions).
// params[6].x: cursor light mode (0 glow, 1 halo); params[6].y: pattern seed (0 for black granite).

float hash21(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec2 hash22(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash21(i), hash21(i + vec2(1.0, 0.0)), u.x),
               mix(hash21(i + vec2(0.0, 1.0)), hash21(i + vec2(1.0, 1.0)), u.x), u.y);
}

const mat2 ROT = mat2(0.80, 0.60, -0.60, 0.80);

float fbm(vec2 p) {
    float value = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < 5; i++) {
        value += amplitude * noise(p);
        p = ROT * p * 2.03;
        amplitude *= 0.5;
    }
    return value / 0.96875;
}

// Distance to the nearest Voronoi cell edge, used for crystalline feldspar grains.
vec2 voronoi(vec2 p) {
    vec2 cell = floor(p);
    vec2 f = fract(p);
    float nearest = 8.0;
    float second = 8.0;
    float id = 0.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 offset = vec2(float(x), float(y));
            vec2 point = offset + hash22(cell + offset);
            float d = dot(point - f, point - f);
            if (d < nearest) {
                second = nearest;
                nearest = d;
                id = hash21(cell + offset);
            } else if (d < second) {
                second = d;
            }
        }
    }
    return vec2(sqrt(second) - sqrt(nearest), id);
}

// Thin ridged vein: 1 on the seam, falling off with width.
float vein(float field, float width) {
    return 1.0 - smoothstep(0.0, width, abs(field));
}

void main() {
    vec2 uv = v_texCoord;
    vec2 size = max(params[0].xy, vec2(1.0));
    float aspect = max(0.25, size.x / size.y);
    float time = params[0].z;

    vec2 cursor = params[2].xy;
    float cursorInside = step(0.0, cursor.x) * step(cursor.x, 1.0)
            * step(0.0, cursor.y) * step(cursor.y, 1.0);
    float depth = clamp(params[2].w, 0.0, 1.0) * cursorInside;
    vec2 parallax = (vec2(0.5) - cursor) * (0.010 * depth);
    vec2 p = (uv + parallax - 0.5) * vec2(aspect, 1.0);
    // Each stone is a different slab: params[6].y shifts the whole vein pattern.
    p += vec2(params[6].y, params[6].y * 0.73);

    float detail = clamp(params[1].y, 0.0, 1.0);
    float dustDensity = clamp(params[1].z, 0.0, 1.0);
    float veinGlow = clamp(params[1].w, 0.0, 1.0);

    // Base stone: near-black with cool and warm mineral clouds.
    float cloud = fbm(p * 2.2 + vec2(3.1, 7.7));
    float cloudFine = fbm(p * 7.5 + vec2(11.4, 1.9));
    bool custom = dot(params[3].rgb + params[4].rgb + params[5].rgb, vec3(1.0)) > 0.001;
    vec3 bodyHigh = custom ? params[5].rgb : vec3(0.052, 0.050, 0.054);
    vec3 bodyLow = custom ? params[5].rgb * 0.38 : vec3(0.020, 0.021, 0.024);
    vec3 bodyDeep = custom ? params[5].rgb * 0.20 : vec3(0.010, 0.010, 0.012);
    vec3 grainTint = custom ? clamp(params[5].rgb / vec3(0.052, 0.050, 0.054), 0.4, 3.0) : vec3(1.0);
    vec3 stone = mix(bodyLow, bodyHigh, cloud);
    stone = mix(stone, bodyDeep, smoothstep(0.55, 0.85, cloudFine) * 0.6);

    // Crystalline grains: dark mica, grey quartz, and rare smoky feldspar.
    vec2 grains = voronoi(p * 34.0);
    float grainEdge = 1.0 - smoothstep(0.0, 0.08, grains.x);
    float grainTone = grains.y;
    vec3 grainColor = grainTone < 0.55 ? vec3(0.012, 0.012, 0.014)
            : grainTone < 0.88 ? vec3(0.070, 0.070, 0.075)
            : vec3(0.120, 0.112, 0.105);
    stone = mix(stone, grainColor * grainTint, 0.40 * detail);
    stone *= 1.0 - grainEdge * 0.12 * detail;
    float speck = hash21(floor(uv * size * 0.5));
    stone += vec3(step(0.985, speck) * 0.05 * detail);

    // Marble veins: long fractured seams running diagonally, warped by ridged noise,
    // over a fine crackle network like Nero Marquina stone.
    vec2 warp = vec2(fbm(p * 1.1 + vec2(1.7, 9.2)), fbm(p * 1.1 + vec2(8.3, 2.8)));
    float jag = abs(fbm(p * 6.0 + 3.0) - 0.5) * 2.0;
    vec2 q = p + (warp - 0.5) * 0.9;

    float primaryPhase = dot(q, vec2(0.80, -0.60)) * 1.15 + fbm(q * 1.6 + 5.0) * 1.6 + jag * 0.06;
    float primaryDist = abs(fract(primaryPhase) - 0.5);
    float thickness = mix(0.35, 1.25, fbm(q * 2.2 + 11.0));
    float primaryCore = 1.0 - smoothstep(0.0035 * thickness, 0.0095 * thickness, primaryDist);
    float primaryHalo = 1.0 - smoothstep(0.01, 0.07 * thickness, primaryDist);
    float primaryPresence = smoothstep(0.30, 0.55, fbm(q * 0.9 + vec2(floor(primaryPhase) * 3.7, 2.0)));
    primaryCore *= primaryPresence;
    primaryHalo *= primaryPresence;

    float branchPhase = dot(q, vec2(0.35, 0.94)) * 2.3 + fbm(q * 2.8 + 17.0) * 2.2 + jag * 0.10;
    float branchDist = abs(fract(branchPhase) - 0.5);
    float branchMask = smoothstep(0.52, 0.70, fbm(q * 1.9 + vec2(floor(branchPhase) * 5.1, 9.0)));
    float branchCore = (1.0 - smoothstep(0.002, 0.006, branchDist)) * branchMask;
    float branchHalo = (1.0 - smoothstep(0.006, 0.035, branchDist)) * branchMask;

    float crackle = 1.0 - abs(fbm(q * 5.5 + 23.0) * 2.0 - 1.0);
    float crackMask = smoothstep(0.45, 0.80, fbm(q * 1.7 + 31.0));
    float crackCore = smoothstep(0.955, 0.985, crackle) * crackMask;

    vec3 marble = custom ? params[3].rgb : vec3(0.90, 0.905, 0.91);
    float veinMix = clamp(primaryCore * 0.96 + branchCore * 0.70 + crackCore * 0.40, 0.0, 1.0);
    float haloMix = clamp(primaryHalo * 0.16 + branchHalo * 0.08 + crackCore * 0.05, 0.0, 0.28);
    stone = mix(stone, marble * 0.29, haloMix);
    stone = mix(stone, marble, veinMix);

    // Gold inclusions trace parts of the main seams and drift as dust around them.
    float goldStretch = smoothstep(0.60, 0.78, fbm(q * 2.4 + 13.0));
    float goldVein = primaryCore * goldStretch;
    vec3 gold = custom ? params[4].rgb : vec3(0.93, 0.73, 0.39);
    stone = mix(stone, gold, goldVein * 0.80);

    vec2 dustPixel = uv * size;
    vec2 dustCell = floor(dustPixel / 5.0);
    vec2 dustLocal = fract(dustPixel / 5.0);
    float dustRoll = hash21(dustCell + 17.0);
    float drift = smoothstep(0.50, 0.85, fbm(p * 2.6 + 21.0));
    float nearVein = clamp(primaryHalo * 1.4 + branchHalo * 0.6, 0.0, 1.0);
    float dustChance = dustDensity * (0.010 + 0.16 * drift + 0.30 * nearVein);
    float isDust = step(1.0 - dustChance, dustRoll);
    vec2 flakeCenter = 0.2 + 0.6 * hash22(dustCell + 3.0);
    float flakeRadius = mix(0.16, 0.38, hash21(dustCell + 9.0));
    float flake = 1.0 - smoothstep(flakeRadius * 0.4, flakeRadius, length(dustLocal - flakeCenter));
    float twinkle = 0.45 + 0.55 * pow(0.5 + 0.5 * sin(time * (0.8 + 2.2 * hash21(dustCell)) + dustRoll * 40.0), 3.0);
    float dust = isDust * flake;
    stone = mix(stone, gold * (0.70 + 0.55 * twinkle), dust * 0.90);

    // Polished surface: soft cursor specular, lifted veins, and a gentle top sheen.
    vec2 cursorDelta = (uv - cursor) * vec2(aspect, 1.0);
    float cursorDistanceSq = dot(cursorDelta, cursorDelta);
    float light = clamp(params[2].z, 0.0, 1.0) * cursorInside;
    float specular = exp(-cursorDistanceSq * 18.0) * light;
    if (params[6].x >= 0.5) {
        float r = sqrt(cursorDistanceSq);
        specular = (exp(-pow((r - 0.085) * 26.0, 2.0)) + 0.35 * exp(-pow((r - 0.17) * 12.0, 2.0))
                + 0.25 * exp(-cursorDistanceSq * 18.0)) * light;
    }
    stone += vec3(0.10, 0.10, 0.105) * specular;
    stone += marble * veinMix * specular * 0.25 * (0.4 + veinGlow);
    stone += gold * (dust * 0.6 + goldVein) * specular * (0.6 + veinGlow);
    stone += vec3(0.020) * smoothstep(0.6, 0.0, uv.y) * (1.0 - uv.x * 0.5);

    // Edge vignette keeps the frame reading as a single slab.
    vec2 edge = uv * (1.0 - uv);
    float vignette = clamp(pow(edge.x * edge.y * 16.0, 0.18), 0.0, 1.0);
    stone *= mix(0.70, 1.0, vignette);

    fragColor = vec4(stone, clamp(params[1].x, 0.0, 1.0));
}
