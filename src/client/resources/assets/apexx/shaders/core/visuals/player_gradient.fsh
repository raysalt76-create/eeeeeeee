#version 330

in vec2 texCoord0;
in vec4 vertexColor;
out vec4 fragColor;

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

void main() {
    vec3 tint = max(vertexColor.rgb, vec3(0.0001));
    float vertical = clamp(texCoord0.y, 0.0, 1.0);

    // A three-stop enamel gradient: deep blue at the shadow edge, saturated cyan through
    // the body, and a restrained ice highlight. UV-space variation follows the skin/model,
    // so the fill reads as material rather than a flat screen-space wash.
    vec3 deep = mix(tint * vec3(0.18, 0.48, 0.78), vec3(0.025, 0.16, 0.30), 0.24);
    vec3 body = mix(tint, vec3(0.02, 0.76, 0.92), 0.42);
    vec3 ice = mix(vec3(0.54, 0.96, 1.0), tint * 1.35, 0.22);
    vec3 gradient = mix(deep, body, smoothstep(0.02, 0.62, vertical));
    gradient = mix(gradient, ice, smoothstep(0.58, 1.0, vertical) * 0.72);

    float sheen = pow(max(0.0, 1.0 - abs(fract(texCoord0.x * 3.0 + vertical * 0.42) - 0.5) * 2.0), 8.0);
    float grain = hash21(floor(texCoord0 * 420.0)) - 0.5;
    gradient *= 0.90 + sheen * 0.18 + grain * 0.025;

    fragColor = vec4(clamp(gradient, 0.0, 1.0), vertexColor.a);
}
