#version 330 core

// Multi-channel signed distance field decode.
//
// The atlas stores, per channel, a signed distance to the nearest glyph edge. Taking the
// MEDIAN of the three channels recovers a single distance that preserves sharp corners —
// which is the whole reason MSDF beats a plain SDF for type. Because the edge is
// reconstructed analytically rather than sampled, the glyph stays crisp at ANY size: no
// per-size atlas, no mip blur, no bitmap font stepping.
uniform sampler2D tex;
uniform float pxRange;   // must match "distanceRange" in the atlas JSON (8 for ours)

in vec2 vUV;
in vec4 vColor;

out vec4 fragColor;

float median(float r, float g, float b) {
    return max(min(r, g), min(max(r, g), b));
}

// Convert the distance from atlas units into SCREEN pixels, so the antialiased edge is
// always ~1px wide no matter how far the text is scaled up or down. fwidth() gives the
// per-pixel UV derivative, i.e. how much of the atlas one screen pixel covers.
float screenPxRange() {
    vec2 unitRange = vec2(pxRange) / vec2(textureSize(tex, 0));
    vec2 screenTexSize = vec2(1.0) / fwidth(vUV);
    return max(0.5 * dot(unitRange, screenTexSize), 1.0);
}

void main() {
    vec3 msd = texture(tex, vUV).rgb;
    float sd = median(msd.r, msd.g, msd.b);
    float screenPxDistance = screenPxRange() * (sd - 0.5);
    float alpha = clamp(screenPxDistance + 0.5, 0.0, 1.0);

    if (alpha <= 0.01) discard;
    fragColor = vec4(vColor.rgb, vColor.a * alpha);
}
