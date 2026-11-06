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
// UV1/UV2 are SHORT + Usage.UV, which 1.21.11 binds with glVertexAttribIPointer:
// they must be ivec2 (a vec2 here reads garbage, ~0 on NVIDIA, and erases every rect).
in ivec2 UV1;
in float LineWidth;
in ivec2 UV2;

out vec4 vertexColor;
out vec2 localPosition;
flat out vec2 rectangleSize;
flat out float cornerRadius;
flat out vec2 effectData;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
    localPosition = UV0;
    rectangleSize = vec2(UV1);
    cornerRadius = LineWidth;
    effectData = vec2(UV2);
}
