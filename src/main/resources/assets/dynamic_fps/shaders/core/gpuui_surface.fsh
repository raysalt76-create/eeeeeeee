#version 330

in vec2 v_texCoord;
out vec4 fragColor;

layout(std140) uniform UiParameters {
    vec4 params[16];
};

// Physically motivated window surfaces. One shader, one mode per surface, so every option shares the
// same lighting model: a soft key light up-left that the cursor can take over, a polished top edge,
// a vignette that keeps the panes legible and a dither that stops 8-bit banding in dark gradients.
//
// params[0]: framebuffer width, height, elapsed seconds, frame delta.
// params[1]: mode, opacity, detail (0..1), drift speed (0..2).
// params[2]: cursor UV (outside 0..1 means no pointer), cursor light (0..1), depth toggle (0/1).
// params[3]: tint rgb (the theme accent, used sparingly), grain strength (0..1).

const int FROSTED = 0;
const int SMOKED = 1;
const int OBSIDIAN = 2;
const int CARBON = 3;
const int TITANIUM = 4;
const int MARQUINA = 5;
const int AURORA = 6;
const int DEEPSEA = 7;
const int NEBULA = 8;
const int MESH = 9;

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec2 hash22(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    return mix(mix(hash12(i), hash12(i + vec2(1.0, 0.0)), u.x),
               mix(hash12(i + vec2(0.0, 1.0)), hash12(i + vec2(1.0, 1.0)), u.x), u.y);
}

const mat2 ROT = mat2(0.80, 0.60, -0.60, 0.80);

float fbm(vec2 p) {
    float f = 0.0;
    float a = 0.5;
    for (int i = 0; i < 5; i++) {
        f += a * vnoise(p);
        p = ROT * p * 2.03 + 11.7;
        a *= 0.5;
    }
    return f / 0.96875;
}

// Ridged noise: 1 on thin crests, falling away quickly. Mineral veins, fractures and light threads.
float ridge(vec2 p) {
    float f = 0.0;
    float a = 0.5;
    for (int i = 0; i < 5; i++) {
        f += a * (1.0 - abs(vnoise(p) * 2.0 - 1.0));
        p = ROT * p * 2.07 + 5.3;
        a *= 0.5;
    }
    return f / 0.96875;
}

// Distance to the nearest of a jittered grid of points, and to the second nearest.
vec2 voronoi(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    float d1 = 8.0;
    float d2 = 8.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 g = vec2(float(x), float(y));
            vec2 o = hash22(i + g);
            vec2 r = g + o - f;
            float d = dot(r, r);
            if (d < d1) { d2 = d1; d1 = d; }
            else if (d < d2) { d2 = d; }
        }
    }
    return sqrt(vec2(d1, d2));
}

float sat(float x) { return clamp(x, 0.0, 1.0); }

void main() {
    vec2 uv = v_texCoord;
    float aspect = max(0.25, params[0].x / max(params[0].y, 1.0));
    float time = params[0].z * (0.15 + 0.85 * params[1].w);
    int mode = int(params[1].x + 0.5);
    float opacity = clamp(params[1].y, 0.0, 1.0);
    float detail = clamp(params[1].z, 0.0, 1.0);
    vec3 accent = params[3].rgb;
    float grainAmount = clamp(params[3].w, 0.0, 1.0);

    // Aspect-corrected working space, centred so features grow from the middle of the window.
    vec2 p = (uv - 0.5) * vec2(aspect, 1.0);

    // Key light: fixed up-left, handed to the pointer when it is over the window.
    vec2 cursor = params[2].xy;
    float inside = step(0.0, cursor.x) * step(cursor.x, 1.0) * step(0.0, cursor.y) * step(cursor.y, 1.0);
    float follow = clamp(params[2].z, 0.0, 1.0) * inside;
    vec2 lightUv = mix(vec2(0.20, -0.10), cursor, follow);
    vec2 toLight = (uv - lightUv) * vec2(aspect, 1.0);
    float lightDist = length(toLight);
    float key = exp(-lightDist * lightDist * 3.2);
    float wide = exp(-lightDist * 1.35);

    // Parallax: the surface drifts a hair against the pointer, which is what sells depth.
    float depth = clamp(params[2].w, 0.0, 1.0) * inside;
    vec2 parallax = (cursor - 0.5) * 0.016 * depth;
    vec2 q = p - parallax;

    vec3 col = vec3(0.0);
    float alpha = 1.0;

    if (mode == FROSTED) {
        // Etched glass: a cool grey body that lets the blurred scene through, a coarse frost cloud and a
        // fine tooth that catches the light. No pattern, which is what makes it read as a material.
        float frost = fbm(q * 5.5 + 3.1);
        float tooth = hash12(floor(gl_FragCoord.xy) + 17.0);
        float body = 0.125 + 0.022 * frost;
        col = vec3(body * 0.96, body * 1.0, body * 1.08);
        col += vec3(0.85, 0.92, 1.0) * (tooth - 0.5) * 0.018 * (0.5 + detail);
        col += vec3(0.80, 0.88, 1.0) * key * 0.115;
        // Sheen: one broad diagonal band of reflected sky, softened at both ends.
        float diag = dot(uv - vec2(0.5), normalize(vec2(1.0, -0.62)));
        float band = exp(-pow((diag - 0.12) * 4.2, 2.0)) * 0.060 + exp(-pow((diag + 0.34) * 9.0, 2.0)) * 0.025;
        col += vec3(0.88, 0.94, 1.0) * band;
        alpha = 0.46 + 0.16 * frost;
    } else if (mode == SMOKED) {
        // Tinted automotive glass: dense at the bottom, thinning toward the light, two clean reflections.
        float grade = mix(0.78, 0.40, smoothstep(1.0, 0.0, uv.y));
        float haze = fbm(q * 2.4 + vec2(0.0, time * 0.012));
        float body = 0.030 + 0.020 * haze;
        col = vec3(body * 0.95, body * 1.02, body * 1.12);
        col += vec3(0.62, 0.74, 0.92) * wide * 0.050;
        float d1 = dot(uv - vec2(0.5), normalize(vec2(1.0, -0.78)));
        float refl = exp(-pow((d1 - 0.20) * 7.5, 2.0)) * 0.085 + exp(-pow((d1 - 0.30) * 18.0, 2.0)) * 0.040;
        col += vec3(0.72, 0.82, 1.0) * refl * (1.0 - 0.5 * uv.y);
        alpha = grade * 0.92 + 0.06;
    } else if (mode == OBSIDIAN) {
        // Polished black glass. Nothing inside it; everything you see is the room reflected in the
        // surface: two soft boxes whose position follows the light, a falloff toward the edges and a
        // trace of micro-scratching that only shows where a reflection crosses it.
        vec2 w = q * 1.4;
        float body = fbm(w * 1.2 + 3.0);
        col = vec3(0.012, 0.013, 0.017) + vec3(0.008, 0.009, 0.013) * body;
        // One broad sweep of reflected sky and one thin strip-light, both with long soft shoulders.
        float along = dot((uv - lightUv) * vec2(aspect, 1.0), normalize(vec2(0.82, -0.57)));
        float sweep = smoothstep(-0.58, -0.02, along) * (1.0 - smoothstep(0.02, 0.62, along));
        float strip = exp(-pow((along - 0.66) * 11.0, 2.0));
        float fall = 1.0 - 0.45 * smoothstep(0.1, 1.0, uv.y);
        float scratch = pow(vnoise(vec2(dot(uv * vec2(aspect, 1.0), vec2(0.8, 0.6)) * 240.0, dot(uv, vec2(-0.6, 0.8)) * 5.0)), 4.0);
        float refl = (sweep * 0.115 + strip * 0.075) * fall;
        col += vec3(0.58, 0.66, 0.86) * refl * (0.80 + 0.20 * body);
        col += vec3(0.70, 0.78, 0.95) * scratch * refl * 0.22 * (0.3 + detail);
        col += vec3(0.50, 0.56, 0.72) * wide * 0.040;
        alpha = 0.97 * opacity + 0.03;
    } else if (mode == CARBON) {
        // 2x2 twill under clearcoat. Each strand is a cylinder lit along its own axis, so the weave
        // flips between bright and dark as the light moves, which is the whole carbon-fibre look.
        float cells = 44.0 + 22.0 * (1.0 - detail);
        vec2 g = uv * vec2(aspect, 1.0) * cells + parallax * cells;
        vec2 id = floor(g);
        vec2 f = fract(g);
        float over = mod(id.x + id.y, 4.0) < 2.0 ? 1.0 : 0.0;
        float dirH = over;
        float across = dirH > 0.5 ? f.y : f.x;
        float along = dirH > 0.5 ? f.x : f.y;
        float roundness = sqrt(max(0.0, 1.0 - pow(across * 2.0 - 1.0, 2.0)));
        vec2 axis = dirH > 0.5 ? vec2(1.0, 0.0) : vec2(0.0, 1.0);
        vec2 ldir = normalize(toLight + vec2(0.0001));
        float aniso = pow(sat(1.0 - abs(dot(ldir, axis))), 1.6);
        float spec = aniso * roundness * (0.35 + 0.65 * key + 0.25 * wide);
        float fibre = hash12(vec2(id.x * 0.37 + floor(f.x * 6.0), id.y * 0.91 + floor(f.y * 6.0)));
        float shade = 0.016 + 0.040 * roundness + 0.010 * fibre;
        shade *= 0.55 + 0.45 * sin((along + id.x * 0.5 + id.y * 0.5) * 3.1416);
        col = vec3(shade) * vec3(0.95, 1.0, 1.08);
        col += vec3(0.72, 0.80, 0.95) * spec * 0.095;
        // Gaps between strands.
        float gap = smoothstep(0.0, 0.10, across) * smoothstep(1.0, 0.90, across);
        col *= 0.35 + 0.65 * gap;
        // Clearcoat reflection over the weave.
        float d = dot(uv - vec2(0.5), normalize(vec2(1.0, -0.7)));
        col += vec3(0.78, 0.86, 1.0) * exp(-pow((d - 0.16) * 5.0, 2.0)) * 0.040;
        alpha = 0.96 * opacity + 0.04;
    } else if (mode == TITANIUM) {
        // Bead-blasted, linear-brushed titanium: streaks stretched along x, one anisotropic band of
        // light across the middle that slides with the cursor, warm-cool split in the highlight.
        float streak = vnoise(vec2(uv.x * 3.0 * aspect + parallax.x * 6.0, uv.y * 520.0));
        float streak2 = vnoise(vec2(uv.x * 11.0 * aspect, uv.y * 190.0 + 7.0));
        float grainy = mix(streak, streak2, 0.45) - 0.5;
        float bandCentre = mix(0.30, lightUv.y, follow);
        float band = exp(-pow((uv.y - bandCentre) * 3.1, 2.0));
        float across = exp(-pow((uv.x - mix(0.5, lightUv.x, follow)) * 1.4 / aspect * 1.2, 2.0));
        float base = 0.060 + 0.020 * (1.0 - uv.y);
        float lum = base + grainy * (0.030 + 0.035 * detail) + band * across * 0.085 + band * 0.025;
        col = vec3(lum) * vec3(1.02, 1.00, 0.97);
        col += vec3(0.95, 0.88, 0.78) * pow(band, 3.0) * across * 0.040;
        col += vec3(0.78, 0.86, 1.0) * key * 0.030;
        alpha = 0.97 * opacity + 0.03;
    } else if (mode == MARQUINA) {
        // Nero Marquina: polished black marble with a few long, sharp white veins and a pale halo.
        vec2 m = q * 2.6;
        vec2 warp = vec2(fbm(m * 0.7 + 1.9), fbm(m * 0.7 + 8.4));
        vec2 mw = m + (warp - 0.5) * 2.4;
        float cloud = fbm(mw * 0.8 + 3.0);
        float r = ridge(mw * 0.85);
        float vein = pow(r, 15.0);
        float halo = pow(r, 4.0);
        float fine = pow(ridge(mw * 2.1 + 17.0), 18.0) * smoothstep(0.45, 0.8, fbm(mw * 1.3 + 4.0));
        col = vec3(0.016, 0.017, 0.020) + vec3(0.020, 0.021, 0.025) * cloud;
        col += vec3(0.60, 0.63, 0.68) * halo * 0.060 * (0.5 + detail);
        col += vec3(0.92, 0.93, 0.96) * vein * (0.34 + 0.26 * detail);
        col += vec3(0.80, 0.82, 0.88) * fine * 0.20;
        // Polish: a broad soft reflection, not a spot.
        col += vec3(0.60, 0.66, 0.78) * pow(sat(1.0 - lightDist * 0.85), 2.4) * 0.065;
        alpha = 0.97 * opacity + 0.03;
    } else if (mode == AURORA) {
        // Curtains of ionised oxygen folded across a clear night sky: each ribbon has a bright lower
        // hem that undulates, striations that run up from it, a green core and a violet crown.
        vec3 sky = mix(vec3(0.004, 0.008, 0.020), vec3(0.014, 0.030, 0.056), pow(uv.y, 1.3));
        float lean = p.x * 1.35 + (0.5 - uv.y) * 0.34;
        float slow = time * 0.018;
        float fold = fbm(vec2(lean * 0.9 + slow, 3.1));
        float hem = 0.70 + 0.17 * (fold - 0.5) * 2.0 + 0.05 * sin(lean * 3.1 + time * 0.09);
        float presence = smoothstep(0.34, 0.66, fbm(vec2(lean * 1.15 - slow * 0.6, 8.4)));
        float below = smoothstep(hem + 0.045, hem - 0.015, uv.y);
        float height = exp(-max(hem - uv.y, 0.0) * 3.4);
        float stria = 0.50 + 0.50 * vnoise(vec2(lean * 46.0 + fold * 6.0, uv.y * 2.0 + time * 0.05));
        float stria2 = 0.60 + 0.40 * vnoise(vec2(lean * 19.0 - fold * 4.0, 1.3));
        float inten = presence * below * height * mix(0.62, 1.0, stria * stria2);
        vec3 green = vec3(0.10, 0.88, 0.46);
        vec3 teal = vec3(0.06, 0.58, 0.74);
        vec3 violet = vec3(0.46, 0.20, 0.82);
        float h = clamp((hem - uv.y) / 0.55, 0.0, 1.0);
        vec3 aur = mix(green, teal, smoothstep(0.0, 0.5, h));
        aur = mix(aur, violet, smoothstep(0.45, 1.0, h));
        aur = mix(aur, accent * 1.5, 0.10);
        col = sky + aur * inten * (0.46 + 0.26 * detail);
        col += aur * 0.020 * presence * smoothstep(0.0, hem, uv.y);
        vec2 sg = uv * vec2(aspect, 1.0) * 70.0;
        vec2 sc = floor(sg);
        float star = step(0.9965, hash12(sc)) * smoothstep(0.35, 0.0, length(fract(sg) - 0.5));
        col += vec3(0.8, 0.88, 1.0) * star * 0.5 * (1.0 - clamp(inten * 2.2, 0.0, 1.0)) * (0.65 + 0.35 * sin(time * 1.3 + hash12(sc) * 40.0));
        alpha = 0.97 * opacity + 0.03;
    } else if (mode == DEEPSEA) {
        // Looking up through clear water: a green-blue column darkening with depth, an interference
        // caustic on the surface above (light focused by moving swell), shafts and drifting silt.
        float depthT = uv.y;
        vec3 water = mix(vec3(0.020, 0.160, 0.200), vec3(0.002, 0.022, 0.046), pow(depthT, 0.75));
        float tt = time * 0.45;
        // The interference pattern is periodic, so a slow swell warps the lookup and breaks the repeat.
        vec2 swell = vec2(fbm(q * 1.3 + vec2(time * 0.02, 4.0)), fbm(q * 1.3 + vec2(9.0, -time * 0.017)));
        vec2 pp = q * 2.7 * 6.2831853 + (swell - 0.5) * 11.0 - 250.0;
        vec2 i = pp;
        float c = 1.0;
        const float inten = 0.0055;
        for (int n = 0; n < 4; n++) {
            float t2 = tt * (1.0 - 3.2 / float(n + 1));
            i = pp + vec2(cos(t2 - i.x) + sin(t2 + i.y), sin(t2 - i.y) + cos(t2 + i.x));
            float sx = sin(i.x + t2);
            float cy = cos(i.y + t2);
            float ax = pp.x * inten * cy;
            float ay = pp.y * inten * sx;
            c += abs(sx * cy) / max(sqrt(ax * ax + ay * ay), 1.0e-4);
        }
        c /= 4.0;
        c = 1.17 - pow(max(c, 0.0), 1.4);
        float caustic = pow(abs(c), 8.0);
        caustic = clamp(caustic, 0.0, 1.0) * pow(1.0 - depthT, 1.15);
        float shafts = fbm(vec2(p.x * 2.6 + p.y * 1.7 + time * 0.02, 2.0));
        shafts = smoothstep(0.42, 0.85, shafts) * pow(1.0 - depthT, 1.2);
        col = water + vec3(0.42, 0.95, 0.95) * caustic * (0.22 + 0.22 * detail) + vec3(0.16, 0.50, 0.55) * shafts * 0.15;
        float silt = step(0.992, hash12(floor(uv * vec2(aspect, 1.0) * 90.0 + vec2(0.0, time * 0.3))));
        col += vec3(0.5, 0.8, 0.85) * silt * 0.06;
        alpha = 0.97 * opacity + 0.03;
    } else if (mode == NEBULA) {
        // Emission nebula: layered dust lanes in front of glowing gas, two temperature zones, stars.
        vec2 n = q * 1.9;
        vec2 warp = vec2(fbm(n + vec2(time * 0.006, 0.0)), fbm(n + vec2(6.1, 2.2) - vec2(0.0, time * 0.005)));
        float gas = fbm(n * 1.1 + 2.4 * warp);
        float dust = fbm(n * 1.9 + 3.0 * warp + 12.0);
        float shape = smoothstep(0.30, 0.78, gas) * (1.0 - 0.75 * smoothstep(0.50, 0.82, dust));
        vec3 hot = mix(vec3(0.85, 0.20, 0.35), accent * 1.4, 0.45);
        vec3 cool = vec3(0.14, 0.30, 0.78);
        vec3 gasCol = mix(cool, hot, smoothstep(0.35, 0.80, fbm(n * 0.7 + 21.0)));
        col = vec3(0.006, 0.007, 0.016) + gasCol * shape * (0.20 + 0.18 * detail);
        vec2 sg = uv * vec2(aspect, 1.0) * 85.0;
        vec2 cell = floor(sg);
        float h = hash12(cell);
        float star = step(0.985, h) * smoothstep(0.42, 0.0, length(fract(sg) - 0.5)) * (0.4 + 0.6 * hash12(cell + 3.0));
        col += mix(vec3(0.7, 0.8, 1.0), vec3(1.0, 0.85, 0.7), hash12(cell + 8.0)) * star * 0.8;
        alpha = 0.97 * opacity + 0.03;
    } else {
        // Mesh gradient: four soft blobs in accent-derived hues over graphite, with film grain. The
        // current standard for premium dark interfaces.
        float tt = time * 0.05;
        vec2 b1 = vec2(0.18 + 0.05 * sin(tt), 0.22 + 0.06 * cos(tt * 1.3));
        vec2 b2 = vec2(0.82 + 0.05 * cos(tt * 0.9), 0.30 + 0.06 * sin(tt));
        vec2 b3 = vec2(0.58 + 0.06 * sin(tt * 1.1), 0.92 + 0.04 * cos(tt));
        vec2 b4 = vec2(0.30 + 0.05 * cos(tt * 0.7), 0.85);
        vec3 hueA = mix(vec3(0.22, 0.30, 0.95), accent, 0.35);
        vec3 hueB = mix(vec3(0.62, 0.20, 0.80), accent, 0.20);
        vec3 hueC = mix(vec3(0.05, 0.62, 0.74), accent, 0.40);
        vec3 hueD = mix(vec3(0.90, 0.35, 0.40), accent, 0.30);
        vec2 s = vec2(aspect, 1.0);
        float w1 = exp(-dot((uv - b1) * s, (uv - b1) * s) * 3.4);
        float w2 = exp(-dot((uv - b2) * s, (uv - b2) * s) * 3.6);
        float w3 = exp(-dot((uv - b3) * s, (uv - b3) * s) * 3.9);
        float w4 = exp(-dot((uv - b4) * s, (uv - b4) * s) * 5.0);
        col = vec3(0.030, 0.031, 0.038) + hueA * w1 * 0.50 + hueB * w2 * 0.42 + hueC * w3 * 0.46 + hueD * w4 * 0.30;
        alpha = 0.97 * opacity + 0.03;
    }

    // Vignette: corners fall away so the panes keep their contrast.
    vec2 vv = (uv - 0.5) * vec2(1.0, 1.15);
    float vignette = 1.0 - 0.32 * smoothstep(0.30, 0.95, length(vv) * 1.35);
    col *= vignette;

    // Polished top edge: a one-pixel-ish lit bevel for every surface.
    float bevel = smoothstep(0.012, 0.0, uv.y) * 0.07;
    col += vec3(0.85, 0.90, 1.0) * bevel * (0.6 + 0.4 * key);

    // Dither: breaks up banding in the dark gradients; strength follows the Grain setting.
    float n = hash12(gl_FragCoord.xy + fract(params[0].z * 7.13) * 91.7) + hash12(gl_FragCoord.xy * 1.37 + 5.0) - 1.0;
    col += n * (1.0 / 255.0) * (1.0 + 5.0 * grainAmount);

    fragColor = vec4(max(col, 0.0), clamp(alpha, 0.0, 1.0));
}
