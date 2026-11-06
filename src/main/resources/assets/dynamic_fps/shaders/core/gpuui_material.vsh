#version 330

in vec3 Position;
in vec2 UV0;

out vec2 v_texCoord;
out vec2 v_sourceUv;

void main() {
    gl_Position = vec4(Position.xy, 0.0, 1.0);
    // UI coordinates are top-left based; source UVs retain native framebuffer orientation.
    v_texCoord = UV0;
    v_sourceUv = vec2(UV0.x, 1.0 - UV0.y);
}
