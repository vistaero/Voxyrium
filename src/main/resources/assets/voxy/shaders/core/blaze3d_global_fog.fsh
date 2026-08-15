#version 330

layout(std140) uniform DynamicTransforms {
    // Reconstructs camera-relative world position from Voxy's extended depth.
    mat4 ModelViewMat;
    vec4 ColorModulator;
    // x selects zero-to-one NDC; yz contain vanilla fog start and Voxy fog end.
    vec3 ModelOffset;
    // Projects a camera-relative world position into Minecraft's target depth.
    mat4 TextureMat;
};
layout(std140) uniform Projection {
    // Reconstructs camera-relative world position from Minecraft's target depth.
    mat4 ProjMat;
};
layout(std140) uniform Fog {
    vec4 FogColor;
    float FogEnvironmentalStart;
    float FogEnvironmentalEnd;
    float FogRenderDistanceStart;
    float FogRenderDistanceEnd;
    float FogSkyEnd;
    float FogCloudsEnd;
};

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

in vec2 texCoord;

out vec4 fragColor;

const float DEPTH_UNIT = 1.0 / 16777215.0;

float toNdcDepth(float screenDepth) {
    return ModelOffset.x > 0.5 ? screenDepth : screenDepth * 2.0 - 1.0;
}

vec3 reconstructPosition(mat4 inverseProjection, float screenDepth) {
    vec4 position = inverseProjection * vec4(texCoord * 2.0 - 1.0, toNdcDepth(screenDepth), 1.0);
    return position.xyz / position.w;
}

float projectMinecraftDepth(vec3 position) {
    vec4 clip = TextureMat * vec4(position, 1.0);
    float depth = clip.z / clip.w;
    return ModelOffset.x > 0.5 ? depth : depth * 0.5 + 0.5;
}

float compositeLodDepth(float lodDepth, vec3 lodPosition) {
    float minecraftDepth = projectMinecraftDepth(lodPosition);
    float distantDepth = lodDepth / 1048576.0 + 2.0 * DEPTH_UNIT;
    float sodiumBiasedDepth = max(0.0, minecraftDepth - 4.0 * DEPTH_UNIT);
    return minecraftDepth > 0.0 ? sodiumBiasedDepth : distantDepth;
}

float linearFogValue(float fogDistance, float start, float end) {
    if (fogDistance <= start) {
        return 0.0;
    }
    if (fogDistance >= end) {
        return 1.0;
    }
    return (fogDistance - start) / (end - start);
}

void main() {
    float targetDepth = texture(Sampler0, texCoord).r;
    if (targetDepth <= 0.0 || FogColor.a <= 0.0) {
        discard;
    }

    float lodDepth = texture(Sampler1, texCoord).r;
    vec3 position;
    if (lodDepth > 0.0) {
        vec3 lodPosition = reconstructPosition(ModelViewMat, lodDepth);
        float expectedDepth = compositeLodDepth(lodDepth, lodPosition);
        // The opaque composite writes this exact biased value. A small depth-unit tolerance
        // absorbs target-format rounding without mistaking ordinary Sodium terrain for LoD.
        bool lodIsVisible = abs(targetDepth - expectedDepth) <= 8.0 * DEPTH_UNIT;
        position = lodIsVisible ? lodPosition : reconstructPosition(ProjMat, targetDepth);
    } else {
        position = reconstructPosition(ProjMat, targetDepth);
    }

    float environmentalFog = linearFogValue(
            length(position), FogEnvironmentalStart, FogEnvironmentalEnd);
    float renderFog = linearFogValue(
            max(length(position.xz), abs(position.y)), ModelOffset.y, ModelOffset.z);

    // Environmental fog has already been applied per geometry. Blend only the additional
    // opacity needed to reach Sodium's max(environmental, render-distance) result.
    float existingOpacity = environmentalFog * FogColor.a;
    float desiredOpacity = max(environmentalFog, renderFog) * FogColor.a;
    float remainingOpacity = 1.0 - existingOpacity;
    if (desiredOpacity <= existingOpacity || remainingOpacity <= 0.000001) {
        discard;
    }

    float overlayOpacity = (desiredOpacity - existingOpacity) / remainingOpacity;
    fragColor = vec4(FogColor.rgb, clamp(overlayOpacity, 0.0, 1.0));
}
