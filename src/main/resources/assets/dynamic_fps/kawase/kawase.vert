#version 330 core

out vec2 uv;

void main() {
    vec2 point = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    uv = vec2(point.x, 1.0 - point.y);
    gl_Position = vec4(point * 2.0 - 1.0, 0.0, 1.0);
}
