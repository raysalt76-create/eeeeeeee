#version 330

in vec2 v_texCoord;
out vec4 fragColor;

layout(std140) uniform UiParameters {
    vec4 params[16];
};

// Brushed-metal theme surface: tinted anodized metal with a fine brushed grain and a colored sheen.
// params[0]: target width, height, elapsed seconds, frame delta.
// params[1]: opacity, brushed grain strength, silver sheen strength, sheen drift speed.
// params[2]: cursor UV, cursor light strength, cursor-depth toggle.
// params[6].x: cursor light mode (0 glow, 1 halo).
// params[3].rgb: base color at the lit corner. params[4].rgb: base color in shadow.
// params[5].rgb: sheen and cursor-light color (the Apexx silver is 0.105, 0.110, 0.118).

float hash11(float p) {
    p = fract(p * 0.1031);
    p *= p + 33.33;
    p *= p + p;
    return fract(p);
}

float hash21(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash21(i), hash21(i + vec2(1.0, 0.0)), u.x),
               mix(hash21(i + vec2(0.0, 1.0)), hash21(i + vec2(1.0, 1.0)), u.x), u.y);
}

float fbm(vec2 p) {
    float value = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < 4; i++) {
        value += amplitude * noise(p);
        p = p * 2.07 + vec2(1.3, 7.1);
        amplitude *= 0.5;
    }
    return value / 0.9375;
}

// Horizontal brushing: per-row streak noise stretched along X.
float brushed(vec2 pixel) {
    float row = floor(pixel.y);
    float streak = noise(vec2(pixel.x * 0.012 + hash11(row) * 50.0, row * 0.73));
    float fine = hash11(row * 1.7 + floor(pixel.x * 0.02));
    return mix(streak, fine, 0.25);
}

void main() {
    vec2 uv = v_texCoord;
    vec2 size = max(params[0].xy, vec2(1.0));
    float aspect = max(0.25, size.x / size.y);
    float time = params[0].z * clamp(params[1].w, 0.0, 2.0);

    vec2 cursor = params[2].xy;
    float cursorInside = step(0.0, cursor.x) * step(cursor.x, 1.0)
            * step(0.0, cursor.y) * step(cursor.y, 1.0);
    float depth = clamp(params[2].w, 0.0, 1.0) * cursorInside;
    vec2 parallax = (vec2(0.5) - cursor) * (0.012 * depth);
    vec2 suv = uv + parallax;

    vec3 sheenColor = params[5].rgb;
    float grain = clamp(params[1].y, 0.0, 1.0);
    float sheen = clamp(params[1].z, 0.0, 1.0);

    // Base: deep graphite falling to pure black at the lower right.
    float diagonal = dot(suv, vec2(0.55, 0.45));
    vec3 color = mix(params[3].rgb, params[4].rgb, smoothstep(0.0, 1.0, diagonal));
    color += vec3(0.012) * (fbm(suv * vec2(aspect, 1.0) * 3.0) - 0.5);

    // Brushed grain in framebuffer pixels so it stays crisp at any GUI scale.
    float brush = brushed(uv * size);
    color *= 1.0 + (brush - 0.5) * 0.22 * grain;

    // Slow anisotropic silver band sweeping across the surface.
    float bandCenter = 0.25 + 0.15 * sin(time * 0.15);
    float band = exp(-pow((suv.y - bandCenter + (suv.x - 0.5) * 0.35) * 3.2, 2.0));
    float bandStreak = 0.65 + 0.35 * brush;
    color += sheenColor * band * bandStreak * sheen;

    // Top-left silver bloom, like light catching a bevel.
    vec2 corner = (suv - vec2(0.05, 0.0)) * vec2(aspect * 0.8, 1.0);
    color += sheenColor * 0.72 * exp(-dot(corner, corner) * 3.5) * sheen;

    // Cursor light: a wide anisotropic specular stretched along the brushing direction.
    vec2 cursorDelta = (uv - cursor) * vec2(aspect, 1.0);
    vec2 aniso = cursorDelta * vec2(0.45, 2.4);
    float light = clamp(params[2].z, 0.0, 1.0) * cursorInside;
    float spec = exp(-dot(aniso, aniso) * 9.0) * light;
    float soft = exp(-dot(cursorDelta, cursorDelta) * 6.0) * light;
    if (params[6].x < 0.5) {
        color += sheenColor * 5.9 * spec * (0.45 + 0.55 * brush) * 0.55;
        color += sheenColor * 0.48 * soft;
    } else {
        // Halo: a thin lit ring that trails the cursor with a dim core, like a lens catching the brushing.
        float r = length(cursorDelta);
        float ring = exp(-pow((r - 0.085) * 26.0, 2.0)) * light;
        float outer = exp(-pow((r - 0.17) * 12.0, 2.0)) * light * 0.35;
        color += sheenColor * 3.2 * (ring + outer) * (0.55 + 0.45 * brush);
        color += sheenColor * 0.10 * soft;
    }

    // Edge vignette for a machined, inset feel.
    vec2 edge = uv * (1.0 - uv);
    float vignette = clamp(pow(edge.x * edge.y * 16.0, 0.20), 0.0, 1.0);
    color *= mix(0.62, 1.0, vignette);

    // Ordered dither prevents banding across the very dark gradient.
    color += (hash21(uv * size) - 0.5) / 255.0;

    fragColor = vec4(max(color, vec3(0.0)), clamp(params[1].x, 0.0, 1.0));
}
