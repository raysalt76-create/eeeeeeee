#version 330

in vec2 v_texCoord;
in vec2 v_sourceUv;
out vec4 fragColor;

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
layout(std140) uniform WorldVisualParameters {
    vec4 Frame;
    mat4 InverseProjection;
    mat4 InverseView;
    vec4 Fog;
    vec4 FogColor;
    vec4 Globe;
    vec4 Ground;
    vec4 HitPositionRadius;
    vec4 HitParams;
    vec4 MarkerInfo;
    vec4 MarkerPositionRadius[4];
    vec4 MarkerNormalIntensity[4];
    vec4 MarkerPrimary[4];
    vec4 MarkerSecondary[4];
};

const float TAU = 6.28318530718;

float hash31(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}

float hash21(vec2 p) {
    vec3 q = fract(vec3(p.xyx) * 0.1031);
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

vec3 cameraWorld() {
    return (InverseView * vec4(0.0, 0.0, 0.0, 1.0)).xyz + Globe.yzw;
}

float hash11(float p) {
    p = fract(p * 0.1031);
    p *= p + 33.33;
    p *= p + p;
    return fract(p);
}

float vnoise(vec3 p) {
    vec3 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float n000 = hash31(i), n100 = hash31(i + vec3(1, 0, 0));
    float n010 = hash31(i + vec3(0, 1, 0)), n110 = hash31(i + vec3(1, 1, 0));
    float n001 = hash31(i + vec3(0, 0, 1)), n101 = hash31(i + vec3(1, 0, 1));
    float n011 = hash31(i + vec3(0, 1, 1)), n111 = hash31(i + vec3(1, 1, 1));
    return mix(mix(mix(n000, n100, f.x), mix(n010, n110, f.x), f.y),
               mix(mix(n001, n101, f.x), mix(n011, n111, f.x), f.y), f.z);
}

vec3 worldAt(vec2 uv, float depth) {
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 view = InverseProjection * clip;
    view.xyz /= max(abs(view.w), 0.00001);
    vec4 world = InverseView * vec4(view.xyz, 1.0);
    // Globe.yzw is the camera world position when the view matrix is camera-relative, so every
    // reconstructed position below is world-stable and effects stay put while the player moves.
    return world.xyz / max(abs(world.w), 0.00001) + Globe.yzw;
}

// Physically-motivated aerial perspective: exponential height fog (denser in valleys and near the
// waterline, thinning with altitude) integrated analytically along the view ray, Beer-Lambert
// transmittance with slightly different extinction per channel, an in-scatter tint that brightens
// toward the horizon, and a horizon haze that blends distant terrain into the sky.
const float FOG_BASE_Y = 62.0;
const float FOG_HEIGHT_FALLOFF = 0.045;

float heightFogDepth(float camY, float dirY, float t0, float t1) {
    float k = FOG_HEIGHT_FALLOFF;
    float base = exp(-k * (camY - FOG_BASE_Y));
    if (abs(dirY * k) < 0.0004) return base * max(t1 - t0, 0.0);
    float kd = k * dirY;
    return base * (exp(-kd * t0) - exp(-kd * t1)) / kd;
}

vec3 fogScatterColor(vec3 dir) {
    float horizon = pow(1.0 - clamp(abs(dir.y), 0.0, 1.0), 3.0);
    vec3 zenith = FogColor.rgb * vec3(0.86, 0.92, 1.0);
    vec3 haze = mix(FogColor.rgb, vec3(1.0), 0.10);
    return mix(zenith, haze, horizon);
}

vec4 fogPass(vec2 uv, vec4 source, float depth) {
    if (Fog.w < 0.5) return source;
    vec3 camera = cameraWorld();
    float dither = (hash21(uv * Frame.xy) - 0.5) / 255.0;
    float strength = clamp(Fog.z * 40.0, 0.0, 1.0);

    if (depth >= 0.99999) {
        vec3 dir = normalize(worldAt(uv, 0.9999) - camera);
        float elevation = clamp(dir.y, -1.0, 1.0);
        float haze = pow(1.0 - smoothstep(0.0, 0.42, elevation), 2.2);
        haze *= mix(0.35, 0.92, strength);
        return vec4(mix(source.rgb, fogScatterColor(dir), haze) + dither, source.a);
    }

    vec3 world = worldAt(uv, depth);
    vec3 delta = world - camera;
    float dist = length(delta);
    vec3 dir = delta / max(dist, 0.0001);

    float t0 = Fog.x;
    float uniformPart = max(dist - t0, 0.0);
    float heightPart = heightFogDepth(camera.y, dir.y, min(t0, dist), dist);
    float optical = Fog.z * (0.30 * uniformPart + 1.15 * heightPart);

    vec3 extinction = vec3(0.90, 1.00, 1.12);
    vec3 transmittance = exp(-optical * extinction);
    // Fade fully into the haze approaching the configured end so the chunk border never pops.
    float edge = smoothstep(Fog.y * 0.72, max(Fog.y, Fog.x + 0.001), dist);
    transmittance = mix(transmittance, vec3(0.0), edge * 0.97);

    vec3 scatter = fogScatterColor(dir);
    // Slightly warmer, brighter in-scatter looking along the horizon, as with real atmospheric haze.
    scatter *= 1.0 + 0.06 * (1.0 - abs(dir.y));
    vec3 color = source.rgb * transmittance + scatter * (1.0 - transmittance);
    return vec4(color + dither, source.a);
}

// Soft luminous orbs anchored to fixed points in the world sky. Each grid cell may own one orb that
// only bobs gently around its anchor, so orbs hold their place while the player moves.
vec4 globePass(vec2 uv, vec4 source, float depth) {
    if (Globe.x <= 0.001) return source;
    vec3 camera = cameraWorld();
    vec3 endpoint = depth >= 0.99999 ? worldAt(uv, 0.9999) : worldAt(uv, depth);
    vec3 rayVector = endpoint - camera;
    float maxLen = depth >= 0.99999 ? 220.0 : min(length(rayVector), 220.0);
    vec3 ray = rayVector / max(length(rayVector), 0.0001);

    const float CELL = 34.0;
    const float MIN_Y = 92.0;
    const float MAX_Y = 230.0;
    vec3 cellPos = floor(camera / CELL);
    vec3 stepDir = sign(ray);
    vec3 invRay = 1.0 / max(abs(ray), vec3(0.00001));
    vec3 nextBoundary = (cellPos + max(stepDir, 0.0)) * CELL;
    vec3 tMax = abs(nextBoundary - camera) * invRay;
    vec3 tDelta = CELL * invRay;

    vec3 emission = vec3(0.0);
    float time = Frame.z;
    for (int i = 0; i < 9; i++) {
        float h = hash31(cellPos + 11.0);
        if (h > 0.42) {
            vec3 anchor = (cellPos + 0.2 + 0.6 * vec3(hash31(cellPos + 1.0), hash31(cellPos + 7.0), hash31(cellPos + 13.0))) * CELL;
            if (anchor.y > MIN_Y && anchor.y < MAX_Y) {
                float seed = hash31(cellPos + 29.0) * 100.0;
                vec3 bob = vec3(sin(time * 0.11 + seed), sin(time * 0.17 + seed * 1.7) * 0.7, cos(time * 0.09 + seed * 0.6)) * 1.6;
                vec3 center = anchor + bob;
                float radius = mix(1.1, 3.2, hash31(cellPos + 19.0));
                vec3 toCenter = center - camera;
                float tc = clamp(dot(toCenter, ray), 0.0, maxLen);
                float dmin = length(toCenter - ray * tc);
                float dist = length(toCenter);
                if (dmin < radius * 3.2 && dot(toCenter, ray) > -radius && dist - radius < maxLen) {
                    float q = dmin / radius;
                    float chord = 2.0 * sqrt(max(radius * radius - dmin * dmin, 0.0)) / radius;
                    float core = 1.0 - exp(-chord * 1.9);
                    float rim = pow(clamp(q, 0.0, 1.0), 3.0) * step(q, 1.0);
                    float halo = exp(-pow(dmin / (radius * 1.9), 2.0)) * 0.22;
                    float pulse = 0.88 + 0.12 * sin(time * (0.5 + 0.4 * hash11(seed)) + seed);
                    vec3 tint = mix(vec3(0.62, 0.84, 1.0), vec3(1.0, 0.86, 0.66), step(0.6, hash31(cellPos + 23.0)));
                    vec3 body = tint * (core * 0.85 + rim * 0.35) + vec3(1.0) * pow(core, 4.0) * 0.45;
                    float atmosphere = exp(-dist * 0.006);
                    emission += (body * pulse + tint * halo) * atmosphere;
                }
            }
        }
        // Advance the grid walk; stop once past the visible segment.
        float tNext = min(tMax.x, min(tMax.y, tMax.z));
        if (tNext > maxLen) break;
        if (tMax.x <= tMax.y && tMax.x <= tMax.z) { cellPos.x += stepDir.x; tMax.x += tDelta.x; }
        else if (tMax.y <= tMax.z) { cellPos.y += stepDir.y; tMax.y += tDelta.y; }
        else { cellPos.z += stepDir.z; tMax.z += tDelta.z; }
    }
    vec3 color = source.rgb + emission * Globe.x * 0.9;
    return vec4(color, source.a);
}

// Thin-film sheen over every block surface: a very thin, slowly varying interference layer whose
// thickness is stable per block, shifting the surface hue and lifting saturation without hiding the texture.
vec3 thinFilmColor(float thickness, float cosTheta) {
    float path = thickness / max(cosTheta, 0.2);
    return 0.5 + 0.5 * cos(TAU * (path * vec3(1.00, 1.14, 1.32) + vec3(0.0, 0.33, 0.67)));
}

vec4 groundPass(vec2 uv, vec4 source, float depth) {
    if (Ground.x <= 0.001 || depth >= 0.99999) return source;
    vec3 p = worldAt(uv, depth);
    vec2 texel = 1.0 / max(Frame.xy, vec2(1.0));
    float dxDepth = texture(Sampler1, uv + vec2(texel.x, 0.0)).r;
    float dyDepth = texture(Sampler1, uv + vec2(0.0, texel.y)).r;
    if (dxDepth >= 0.99999 || dyDepth >= 0.99999) return source;
    vec3 px = worldAt(uv + vec2(texel.x, 0.0), dxDepth);
    vec3 py = worldAt(uv + vec2(0.0, texel.y), dyDepth);
    vec3 normal = normalize(cross(px - p, py - p));
    vec3 camera = cameraWorld();
    vec3 viewDir = normalize(camera - p);
    if (dot(normal, viewDir) < 0.0) normal = -normal;

    float block = max(Ground.y, 0.25);
    float top = smoothstep(0.55, 0.95, normal.y);
    float surface = mix(0.38, 1.0, top);
    vec3 cell = floor((p - normal * 0.02) * block);
    float tile = hash31(cell);
    // Thickness drifts slowly across the surface and differs a little from block to block.
    float drift = vnoise(vec3(p.x * 0.21, p.y * 0.21 + p.z * 0.05, p.z * 0.21) * block + vec3(0.0, Frame.z * 0.035, 0.0));
    float thickness = 0.30 + 0.45 * drift + 0.18 * tile;
    float cosTheta = clamp(dot(normal, viewDir), 0.0, 1.0);
    vec3 film = thinFilmColor(thickness, cosTheta);

    float dist = length(p - camera);
    float distanceFade = 1.0 - smoothstep(48.0, 128.0, dist);
    float fresnel = pow(1.0 - cosTheta, 3.0);
    float amount = Ground.x * surface * distanceFade;

    float lum = dot(source.rgb, vec3(0.299, 0.587, 0.114));
    vec3 dilated = mix(vec3(lum), source.rgb, 1.0 + 0.55 * amount);
    vec3 tinted = dilated * mix(vec3(1.0), film * 1.55, 0.30 * amount);
    tinted += film * fresnel * 0.16 * amount;
    return vec4(mix(source.rgb, tinted, clamp(amount * 1.15, 0.0, 1.0)), source.a);
}

vec4 ripplePass(vec2 uv, vec4 source, float depth) {
    if (HitParams.y <= 0.001 || HitPositionRadius.w <= 0.001 || depth >= 0.99999) return source;
    vec3 p = worldAt(uv, depth);
    vec3 delta = p - HitPositionRadius.xyz;
    float distanceToHit = length(delta);
    float age = HitParams.x;
    float radius = HitPositionRadius.w + age * 6.2;
    float envelope = exp(-age * 3.6) * (1.0 - smoothstep(2.4, 5.2, age));
    float shell = exp(-pow((distanceToHit - radius) / max(0.08, 0.24 + age * 0.10), 2.0));
    float local = exp(-distanceToHit * 2.0) * envelope;
    vec2 radial = normalize(uv - 0.5 + vec2(0.00001));
    float pixels = shell * envelope * HitParams.y * 0.0035;
    vec2 refractedUv = clamp(uv + radial * pixels, vec2(0.001), vec2(0.999));
    vec3 refracted = texture(Sampler0, refractedUv).rgb;
    float rim = shell * envelope * HitParams.y;
    vec3 highlight = vec3(HitParams.z, HitParams.w, 1.0) * (rim * 0.34 + local * 0.10);
    return vec4(mix(source.rgb, refracted + highlight, clamp(rim * 0.78 + local * 0.12, 0.0, 0.72)), source.a);
}

float band(float x, float center, float halfWidth, float aa) {
    return 1.0 - smoothstep(halfWidth, halfWidth + aa, abs(x - center));
}

// Terrain-projected landing decal. Each marker is evaluated in the plane perpendicular to its hit
// normal against the reconstructed world position of the pixel, so rings wrap over slabs, stairs and
// uneven ground instead of floating on a flat quad. Radii are normalized so r == 1 is the main ring.
vec4 markerPass(vec2 uv, vec4 source, float depth) {
    int count = int(MarkerInfo.x + 0.5);
    if (count <= 0 || depth >= 0.99999) return source;
    vec3 p = worldAt(uv, depth);
    vec3 camera = cameraWorld();
    float pixelAngle = 2.0 * abs(InverseProjection[1][1]) / max(Frame.y, 1.0);
    float footprint = length(p - camera) * pixelAngle;
    float t = MarkerInfo.y;
    int style = int(MarkerInfo.z + 0.5);
    vec3 color = source.rgb;
    for (int i = 0; i < 4; i++) {
        if (i >= count) break;
        vec3 n = normalize(MarkerNormalIntensity[i].xyz);
        float radius = max(MarkerPositionRadius[i].w, 0.05);
        vec3 d = p - MarkerPositionRadius[i].xyz;
        float height = dot(d, n);
        float slab = 1.0 - smoothstep(0.30, 1.05, abs(height));
        if (slab <= 0.0) continue;
        vec3 planar = d - n * height;
        float r = length(planar) / radius;
        if (r > 1.75) continue;

        vec3 helper = abs(n.y) > 0.9 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0);
        vec3 tangent = normalize(cross(helper, n));
        vec3 bitangent = cross(n, tangent);
        float angle = atan(dot(planar, bitangent), dot(planar, tangent));
        float turn = angle / TAU + 0.5;
        float aa = clamp(footprint / radius * 1.5, 0.004, 0.25);

        vec3 primary = MarkerPrimary[i].rgb;
        vec3 secondary = MarkerSecondary[i].rgb;
        float intensity = MarkerNormalIntensity[i].w * slab;

        float ring = band(r, 1.0, 0.035, aa);
        float halo = exp(-pow((r - 1.0) / 0.16, 2.0)) * 0.45;
        float center = 1.0 - smoothstep(0.055, 0.055 + aa, r);
        float fill = (1.0 - smoothstep(0.0, 1.0, r)) * 0.22;
        float pulseRadius = fract(t * 0.55) * 1.35;
        float pulse = band(r, pulseRadius, 0.018, aa) * (1.0 - pulseRadius / 1.35) * 0.9;

        float dashes = smoothstep(0.42 - aa * 4.0, 0.42, fract(turn * 18.0 + t * 0.30))
                * (1.0 - smoothstep(0.92, 0.92 + aa * 4.0, fract(turn * 18.0 + t * 0.30)));
        float innerDashes = band(r, 0.64, 0.016, aa) * dashes;

        float arcPhase = fract(turn * 3.0 - t * 0.22);
        float arcs = band(r, 1.24, 0.022, aa) * smoothstep(0.0, 0.05, arcPhase) * (1.0 - smoothstep(0.52, 0.57, arcPhase));

        float tickPhase = fract(turn * 4.0 + 0.125);
        float ticks = band(tickPhase, 0.5, 0.025, aa) * band(r, 1.24, 0.17, aa);

        vec3 emission;
        float shade;
        if (style == 2) {
            emission = primary * (ring + halo * 0.6 + center) + secondary * pulse * 0.6;
            shade = fill * 0.35;
        } else if (style == 1) {
            emission = primary * (ring + center + ticks * 0.9 + halo * 0.5) + secondary * (arcs + innerDashes * 0.8);
            shade = fill * 0.25;
        } else {
            emission = primary * (ring + halo + center + pulse * 0.7) + secondary * (arcs * 0.85 + innerDashes * 0.75 + fill * 0.55);
            shade = fill;
        }
        color = mix(color, color * 0.72, clamp(shade * intensity, 0.0, 0.5));
        color += emission * intensity;
    }
    return vec4(color, source.a);
}

void main() {
    vec2 uv = v_sourceUv;
    float depth = texture(Sampler1, uv).r;
    vec4 source = texture(Sampler0, uv);
    int operation = int(Frame.w + 0.5);
    if (operation == 0) fragColor = fogPass(uv, source, depth);
    else if (operation == 1) fragColor = globePass(uv, source, depth);
    else if (operation == 2) fragColor = groundPass(uv, source, depth);
    else if (operation == 3) fragColor = ripplePass(uv, source, depth);
    else fragColor = markerPass(uv, source, depth);
}
