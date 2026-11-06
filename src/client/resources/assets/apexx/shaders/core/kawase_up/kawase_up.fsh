#version 330

in vec2 TexCoord;
in vec4 FragColor;

uniform sampler2D Sampler0;
uniform vec2 Resolution;
uniform float Offset;
uniform float Saturation;
uniform float TintIntensity;
uniform vec3 TintColor;

out vec4 OutColor;

vec3 adjustSaturation(vec3 color, float saturation) {
    float gray = dot(color, vec3(0.299, 0.587, 0.114));
    return mix(vec3(gray), color, saturation);
}

void main() {
    vec2 uv = TexCoord;
    vec2 halfpixel = Resolution * 0.5 * Offset;

    vec4 sum = vec4(0.0);

    sum += texture(Sampler0, uv + vec2(-halfpixel.x * 2.0, 0.0));
    sum += texture(Sampler0, uv + vec2(-halfpixel.x, halfpixel.y)) * 2.0;
    sum += texture(Sampler0, uv + vec2(0.0, halfpixel.y * 2.0));
    sum += texture(Sampler0, uv + vec2(halfpixel.x, halfpixel.y)) * 2.0;
    sum += texture(Sampler0, uv + vec2(halfpixel.x * 2.0, 0.0));
    sum += texture(Sampler0, uv + vec2(halfpixel.x, -halfpixel.y)) * 2.0;
    sum += texture(Sampler0, uv + vec2(0.0, -halfpixel.y * 2.0));
    sum += texture(Sampler0, uv + vec2(-halfpixel.x, -halfpixel.y)) * 2.0;

    vec3 color = sum.rgb / 12.0;
    color = adjustSaturation(color, Saturation);
    color = mix(color, TintColor, TintIntensity);

    OutColor = vec4(color, 1.0);
}