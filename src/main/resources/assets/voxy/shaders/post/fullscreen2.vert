#version 330 core

#import <voxy:util/depthutils.glsl>
#ifdef VOXY_VULKAN
#define VERT_ID gl_VertexIndex
#else
#define VERT_ID gl_VertexID
#endif
out vec2 UV;
void main() {
    gl_Position = vec4(vec2(VERT_ID&1, (VERT_ID>>1)&1) * 2 - 1, FAR, 1);
    UV = gl_Position.xy*0.5+0.5;
}