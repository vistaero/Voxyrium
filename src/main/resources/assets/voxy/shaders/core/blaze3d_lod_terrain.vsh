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

// Corner advances per vertex. The remaining attributes advance once per quad instance.
in vec2 Corner;
in vec3 SectionOrigin;
in uvec2 QuadData;
in uint FaceData;
in vec4 Color;
in uint Material;

uniform sampler2D Sampler2;

out vec2 texCoord0;
flat out vec4 tintColor;
flat out vec4 vertexLighting;
flat out int modelId;
flat out int quadFlags;
out float sphericalDistance;

float fluidHeightOffset(int face, int axis, ivec2 corner, uint heights) {
    int index = -1;
    if (face == 1) index = (corner.x << 1) | corner.y;
    else if (axis == 1 && corner.y == 1) index = (corner.x << 1) | (face & 1);
    else if (axis == 2 && corner.x == 1) index = ((face & 1) << 1) | corner.y;
    return index < 0 ? 0.0 : float(((heights >> uint(index * 3)) & 7u) + 1u) / 8.0 - 1.0;
}

void main() {
    int face = int(QuadData.x & 7u);
    int axis = face >> 1;
    bool fluid = (Material & 16u) != 0u;
    float scale = float(1u << ((Material >> 8u) & 7u));
    vec2 textureMin = vec2(FaceData & 15u, (FaceData >> 8u) & 15u) / 16.0 - 0.00005;
    vec2 textureEnd = vec2((FaceData >> 4u) & 15u, (FaceData >> 12u) & 15u) / 16.0 + 1.0 / 16.0;
    vec2 size = fluid ? vec2(1.0) : vec2((QuadData.x >> 3u) & 15u, (QuadData.x >> 7u) & 15u) + 1.0;
    vec2 textureSize = textureEnd - textureMin + size - 1.0;
    vec2 geometryMin = fluid ? vec2(0.0) : textureMin;
    vec2 geometrySize = fluid ? vec2(1.0) : textureSize;
    uint encodedDepth = (FaceData >> 16u) & 63u;
    if (encodedDepth == 63u) encodedDepth = 64u;
    float depth = fluid ? 0.0 : float(encodedDepth) / 64.0;
    if ((face & 1) != 0) depth = 1.0 - depth;

    vec3 position = SectionOrigin + vec3((QuadData.x >> 21u) & 31u,
            (QuadData.x >> 16u) & 31u, (QuadData.x >> 11u) & 31u) * scale;
    vec2 offset = geometrySize * Corner * scale;
    if (axis == 0) {
        position += vec3(geometryMin.x, depth, geometryMin.y) * scale;
        position += vec3(offset.x, 0.0, offset.y);
    } else if (axis == 1) {
        position += vec3(geometryMin.x, geometryMin.y, depth) * scale;
        position += vec3(offset.x, offset.y, 0.0);
    } else {
        position += vec3(depth, geometryMin.x, geometryMin.y) * scale;
        position += vec3(0.0, offset.x, offset.y);
    }
    if (fluid) {
        uint heights = ((QuadData.x >> 3u) & 255u) | (((QuadData.y >> 10u) & 15u) << 8u);
        position.y += fluidHeightOffset(face, axis, ivec2(Corner), heights) * scale;
    }
    vec4 viewPosition = ModelViewMat * vec4(position, 1.0);
    gl_Position = ProjMat * viewPosition;
    texCoord0 = textureMin + textureSize * Corner;
    modelId = int((QuadData.x >> 26u) | ((QuadData.y & 1023u) << 6u));
    quadFlags = int((QuadData.y >> 23u) & 255u) | (face << 8)
            | (int((FaceData >> 24u) & 3u) << 11);
    if (((FaceData >> 22u) & 1u) != 0u
            || (((FaceData >> 23u) & 1u) != 0u && (size.x > 1.0 || size.y > 1.0))) {
        quadFlags |= 1 << 13;
    }
    if ((Material & 4u) != 0u) quadFlags |= 1 << 14;
    int packedLight = quadFlags & 0xFF;
    vec2 lightUv = clamp(vec2((packedLight >> 4) & 0xF, packedLight & 0xF) / 16.0
            + (0.5 / 16.0), vec2(0.5 / 16.0), vec2(15.5 / 16.0));
    float directionalShade = Color.a;
    tintColor = vec4(Color.rgb, 1.0);
    vertexLighting = texture(Sampler2, lightUv) * vec4(vec3(directionalShade), 1.0);
    sphericalDistance = length(viewPosition.xyz);
}
