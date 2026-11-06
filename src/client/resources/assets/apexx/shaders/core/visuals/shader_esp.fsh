#version 330

// ShaderESP composite. Sampler0 is the vanilla entity-outline framebuffer, which holds the full
// rendered silhouette (skin, armor, items, elytra) of every masked entity in its ESP color.
// Sampler1 is that framebuffer's own depth, Sampler2 is a snapshot of the scene depth.

in vec2 v_texCoord;
in vec2 v_sourceUv;
out vec4 fragColor;

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

layout(std140) uniform ShaderEspParameters {
    vec4 Frame;       // x = width px, y = height px, z = time s, w = 1 when visible through walls
    vec4 Fill;        // x = enabled, y = alpha, z = style (0 solid, 1 gradient, 2 smoke), w = brightness
    vec4 Outline;     // x = enabled, y = width px, z = alpha, w = intensity
    vec4 Glow;        // x = enabled, y = radius px, z = alpha, w = intensity
    vec4 Smoke;       // x = scale, y = speed, z = octaves, w = contrast
    vec4 Secondary;   // rgb = gradient partner color, w = gradient angle (radians)
    vec4 FriendColor; // rgb = relation color kept solid
    vec4 EnemyColor;  // rgb = relation color kept solid
};

const float TAU = 6.28318530718;
const int TAPS = 16;

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(vec2 p, int octaves) {
    float v = 0.0;
    float a = 0.52;
    mat2 r = mat2(0.86, 0.50, -0.50, 0.86);
    for (int i = 0; i < 6; i++) {
        if (i >= octaves) break;
        v += a * noise(p);
        p = r * p * 2.03 + vec2(17.13, 9.27);
        a *= 0.5;
    }
    return v;
}

// Interleaved gradient noise: breaks the tap rings into fine dither instead of visible bands.
float ign(vec2 px) {
    return fract(52.9829189 * fract(dot(px, vec2(0.06711056, 0.00583715))));
}

// Returns the mask color (rgb) and coverage (a) at a texel, honoring the optional depth test.
vec4 maskAt(vec2 uv) {
    vec4 m = texture(Sampler0, uv);
    if (m.a < 0.5) return vec4(0.0);
    if (Frame.w < 0.5) {
        float entityDepth = texture(Sampler1, uv).r;
        float sceneDepth = texture(Sampler2, uv).r;
        if (entityDepth > sceneDepth + 0.00006) return vec4(0.0);
    }
    return vec4(m.rgb, 1.0);
}

bool isRelation(vec3 c) {
    return distance(c, FriendColor.rgb) < 0.02 || distance(c, EnemyColor.rgb) < 0.02;
}

vec3 gradientColor(vec3 base, vec2 px, float t) {
    if (isRelation(base)) {
        return base;
    }
    vec2 dir = vec2(cos(Secondary.w), sin(Secondary.w));
    float phase = dot(px, dir) * 0.012 + t * 0.9;
    float wave = 0.5 + 0.5 * sin(phase);
    return mix(base, Secondary.rgb, wave * 0.85);
}

vec3 smokeColor(vec3 base, vec2 px, float t, out float density) {
    float scale = max(Smoke.x, 0.05);
    int octaves = int(clamp(Smoke.z, 1.0, 6.0));
    vec2 p = px / (Frame.y * 0.22) * scale;
    float flow = t * Smoke.y;
    vec2 warp = vec2(fbm(p + vec2(flow * 0.7, 3.1), octaves), fbm(p * 0.9 - vec2(2.4, flow * 0.5), octaves));
    float n = fbm(p + (warp - 0.5) * 1.8 + vec2(0.0, -flow), octaves);
    n = clamp((n - 0.5) * Smoke.w + 0.5, 0.0, 1.0);
    density = n;
    vec3 partner = isRelation(base) ? base * 1.35 : Secondary.rgb;
    return mix(base * 0.45, mix(base, partner, n), smoothstep(0.0, 1.0, n));
}

void main() {
    vec2 uv = v_sourceUv;
    vec2 texel = 1.0 / Frame.xy;
    vec2 px = uv * Frame.xy;
    float t = Frame.z;

    vec4 center = maskAt(uv);

    float outlineW = Outline.x > 0.5 ? max(Outline.y, 0.5) : 0.0;
    float glowR = Glow.x > 0.5 ? max(Glow.y, 1.0) : 0.0;
    float reach = max(outlineW, glowR);

    // ---- inside the silhouette: fill with a soft inner rim --------------------------------
    if (center.a > 0.5) {
        if (Fill.x < 0.5) discard;
        vec3 base = center.rgb;
        float spin = ign(gl_FragCoord.xy) * TAU;
        float open = 0.0;
        for (int i = 0; i < 8; i++) {
            float a = spin + float(i) * (TAU / 8.0);
            open += 1.0 - maskAt(uv + vec2(cos(a), sin(a)) * 3.0 * texel).a;
        }
        float rim = open / 8.0;

        vec3 col;
        float alpha = Fill.y;
        int style = int(Fill.z + 0.5);
        if (style == 2) {
            float density;
            col = smokeColor(base, px, t, density);
            alpha *= 0.35 + 0.65 * density;
        } else if (style == 1) {
            col = gradientColor(base, px, t);
        } else {
            col = base;
        }
        col *= Fill.w;
        col = mix(col, min(col * 1.6 + 0.08, vec3(1.0)), rim * 0.85);
        alpha = clamp(alpha + rim * 0.35 * Fill.y, 0.0, 1.0);
        if (alpha <= 0.002) discard;
        fragColor = vec4(col, alpha);
        return;
    }

    if (reach <= 0.0) discard;

    // ---- cheap rejection: nothing within reach of this pixel ------------------------------
    float spin = ign(gl_FragCoord.xy) * TAU;
    float coarse = 0.0;
    for (int i = 0; i < 8; i++) {
        float a = spin + float(i) * (TAU / 8.0);
        vec2 d = vec2(cos(a), sin(a));
        coarse += maskAt(uv + d * reach * texel).a;
        coarse += maskAt(uv + d * reach * 0.5 * texel).a;
    }
    if (coarse <= 0.0) discard;

    // ---- distance estimate + coverage over concentric rings -------------------------------
    float nearest = 1e9;
    vec3 nearColor = vec3(0.0);
    float glowAcc = 0.0;
    float glowNorm = 0.0;
    const int RINGS = 5;
    for (int r = 1; r <= RINGS; r++) {
        float frac = float(r) / float(RINGS);
        float rad = reach * frac;
        float ringCov = 0.0;
        float ringRot = spin + float(r) * 0.61;
        for (int i = 0; i < TAPS; i++) {
            float a = ringRot + float(i) * (TAU / float(TAPS));
            vec4 s = maskAt(uv + vec2(cos(a), sin(a)) * rad * texel);
            if (s.a > 0.5) {
                ringCov += 1.0;
                if (rad < nearest) {
                    nearest = rad;
                    nearColor = s.rgb;
                }
            }
        }
        ringCov /= float(TAPS);
        float w = 1.0 - frac * 0.85;
        glowAcc += ringCov * w;
        glowNorm += w;
    }
    if (nearest > 1e8) discard;

    vec3 tint = gradientColor(nearColor, px, t);

    float outlineA = 0.0;
    vec3 outlineC = vec3(0.0);
    if (outlineW > 0.0) {
        outlineA = (1.0 - smoothstep(outlineW - 1.0, outlineW + 0.75, nearest)) * Outline.z;
        outlineC = tint * Outline.w;
    }

    float glowA = 0.0;
    vec3 glowC = vec3(0.0);
    if (glowR > 0.0) {
        float cover = glowAcc / glowNorm;
        float falloff = 1.0 - clamp(nearest / glowR, 0.0, 1.0);
        glowA = clamp(cover * 1.9, 0.0, 1.0) * falloff * falloff * Glow.z;
        glowC = tint * Glow.w;
    }

    float a = outlineA + glowA * (1.0 - outlineA);
    if (a <= 0.002) discard;
    vec3 c = (outlineC * outlineA + glowC * glowA * (1.0 - outlineA)) / a;
    fragColor = vec4(min(c, vec3(1.0)), clamp(a, 0.0, 1.0));
}
