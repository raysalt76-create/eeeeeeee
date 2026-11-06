#version 330

uniform sampler2D Sampler0;

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

// Apexx Chams: a flat colour cut to the entity texture's alpha, so transparent skin-layer pixels
// (hat, sleeves) stay see-through instead of drawing as solid boxes.
void main() {
    if (texture(Sampler0, texCoord0).a < 0.1) {
        discard;
    }
    fragColor = vertexColor;
}
