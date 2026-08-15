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
in vec2 UV0;
in ivec2 UV2;
in vec4 Color;

uniform sampler2D Sampler2;

out vec2 texCoord0;
out vec4 vertexColor;
out float sphericalDistance;
out float cylindricalDistance;

void main() {
    vec4 viewPosition = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPosition;
    texCoord0 = UV0;
    vec2 lightUv = clamp((vec2(UV2) / 256.0) + (0.5 / 16.0), vec2(0.5 / 16.0), vec2(15.5 / 16.0));
    vertexColor = Color * texture(Sampler2, lightUv);
    sphericalDistance = length(viewPosition.xyz);
    cylindricalDistance = max(length(viewPosition.xz), abs(viewPosition.y));
}
