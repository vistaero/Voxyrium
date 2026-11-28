#version 330
//? if MC_26_3
#extension GL_ARB_separate_shader_objects : require
#include <voxy:blaze3d_fragment_discard.glsl>
#include <voxy:blaze3d_sodium_coverage.glsl>
//? else
#moj_import <voxy:blaze3d_fragment_discard.glsl>
#moj_import <voxy:blaze3d_sodium_coverage.glsl>
//? endif

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
//? if MC_26_3
    mat4 TextureMat;
//? endif
    vec4 ColorModulator;
    vec3 ModelOffset;
//? if MC_26_2
    mat4 TextureMat;
//? endif
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

//? if MC_26_3
layout(location = 0) in vec2 texCoord0;
layout(location = 1) flat in vec4 tintColor;
layout(location = 2) flat in vec4 vertexLighting;
layout(location = 3) flat in int modelId;
layout(location = 4) flat in int quadFlags;
layout(location = 5) in float sphericalDistance;
layout(location = 6) in vec2 worldXZ;
//? else
in vec2 texCoord0;
flat in vec4 tintColor;
flat in vec4 vertexLighting;
flat in int modelId;
flat in int quadFlags;
in float sphericalDistance;
in vec2 worldXZ;
//? endif

//? if MC_26_3
layout(location = 0) out vec4 fragColor;
//? else
out vec4 fragColor;
//? endif

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
    VOXY_INIT_FRAGMENT();
    int face = (quadFlags >> 8) & 7;
    vec2 modelBase = vec2(modelId & 0xFF, (modelId >> 8) & 0xFF) / 256.0;
    vec2 faceBase = vec2(face >> 1, face & 1) / (vec2(3.0, 2.0) * 256.0);
    vec2 tile;
    vec2 repeatedUv = modf(texCoord0, tile);
    // Geometry expands slightly to close seams. Edge UVs must sample this face,
    // rather than a neighbouring face/model tile, at every uploaded mip level.
    repeatedUv = clamp(repeatedUv, vec2(0.5 / 16.0), vec2(15.5 / 16.0));
    vec2 atlasScale = vec2(1.0) / (vec2(3.0, 2.0) * 256.0);
    vec2 atlasUv = modelBase + faceBase + repeatedUv * atlasScale;
    // All lanes compute derivatives before any Sodium/alpha rejection.
    vec2 dx = dFdx(texCoord0 * atlasScale);
    vec2 dy = dFdy(texCoord0 * atlasScale);
    if (sodiumDiscards(worldXZ, gl_FragCoord.xy)) VOXY_DISCARD_FRAGMENT();
    // Match Native: the seam expansion must not create a new repeated tile at
    // the outside of a merged/cropped quad, even when that face is fully solid.
    vec2 lastTile = vec2((quadFlags >> 15) & 15, (quadFlags >> 19) & 15);
    if (any(notEqual(tile, clamp(tile, vec2(0.0), lastTile)))) VOXY_DISCARD_FRAGMENT();
    vec4 color = textureGrad(Sampler0, atlasUv, dx, dy);
    bool translucent = ((quadFlags >> 14) & 1) != 0;
    bool useCutout = ((quadFlags >> 13) & 1) != 0;
    int tintState = (quadFlags >> 11) & 3;
    // Full opaque faces need only the filtered sample. Cutout/fluid/tint classification
    // shares one mip-zero fetch instead of fetching it again for tint detection.
    vec4 mipZero = vec4(1.0);
    if (translucent || useCutout || tintState == 1) mipZero = textureLod(Sampler0, atlasUv, 0.0);
    float mipZeroAlpha = mipZero.a;
    if ((translucent && mipZeroAlpha == 0.0) || (!translucent && useCutout && mipZeroAlpha <= 0.1)) {
        VOXY_DISCARD_FRAGMENT();
    }
    if (!translucent) {
        color.a = 1.0;
    }

    bool applyTint = tintState == 2;
    if (tintState == 1) {
        applyTint = mipZero.a > (254.5 / 255.0);//per-texel tint mask baked into alpha (MipGen.putTextures)
    }
    if (applyTint) {
        color *= tintColor;
    }
    color *= vertexLighting;
    // Match the 1.21.11 compositor: fog Voxy LoDs only, leaving the shared Minecraft scene intact.
    float environmentalFog = linearFogValue(
            sphericalDistance, FogEnvironmentalStart, FogEnvironmentalEnd);
    float renderDistanceFog = ModelOffset.z > ModelOffset.y
            ? linearFogValue(sphericalDistance, ModelOffset.y, ModelOffset.z) : 0.0;
    float fogValue = max(environmentalFog, renderDistanceFog);
    color.rgb = mix(color.rgb, FogColor.rgb, fogValue * FogColor.a);
    fragColor = color * ColorModulator;
}
