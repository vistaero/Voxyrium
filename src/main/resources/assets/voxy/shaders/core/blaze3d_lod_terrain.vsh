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
out vec4 tintColor;
out vec4 vertexLighting;
flat out int modelId;
flat out int quadFlags;
out float sphericalDistance;
out float cylindricalDistance;

void main() {
    vec4 viewPosition = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPosition;
    texCoord0 = UV0;
    modelId = UV2.x & 0xFFFF;
    quadFlags = UV2.y;
    int packedLight = quadFlags & 0xFF;
    vec2 lightUv = clamp(vec2((packedLight >> 4) & 0xF, packedLight & 0xF) / 16.0
            + (0.5 / 16.0), vec2(0.5 / 16.0), vec2(15.5 / 16.0));
    float directionalShade = Color.a;
    tintColor = vec4(Color.rgb, 1.0);
    vertexLighting = texture(Sampler2, lightUv) * vec4(vec3(directionalShade), 1.0);
    sphericalDistance = length(viewPosition.xyz);
    cylindricalDistance = max(length(viewPosition.xz), abs(viewPosition.y));
}
