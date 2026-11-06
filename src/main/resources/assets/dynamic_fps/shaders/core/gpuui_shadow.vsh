#version 330

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in float LineWidth;
in ivec2 UV2;

out vec4 vertexColor;
out vec2 localPosition;
flat out vec2 rectangleSize;
flat out float cornerRadius;
flat out vec2 shadowEffects;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
    localPosition = UV0;
    rectangleSize = vec2(UV1);
    cornerRadius = LineWidth;
    shadowEffects = vec2(UV2) / 256.0;
}
