#version 330 core

layout(binding = 0) uniform sampler2D depthTex;
#ifdef VOXY_VULKAN
layout(push_constant) uniform PushConstants { vec2 scaleFactor; };
#else
layout(location = 1) uniform vec2 scaleFactor;
#endif

#import <voxy:util/depthutils.glsl>

in vec2 UV;
void main() {
    gl_FragDepth = NEAR;
    if (texture(depthTex, UV*scaleFactor).r==FAR) {
        discard;
    }
}