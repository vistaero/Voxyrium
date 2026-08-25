#version 330

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
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

in vec2 texCoord0;
in vec4 tintColor;
in vec4 vertexLighting;
flat in int modelId;
flat in int quadFlags;
in float sphericalDistance;
in float cylindricalDistance;

out vec4 fragColor;

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
    int face = (quadFlags >> 8) & 7;
    vec2 modelBase = vec2(modelId & 0xFF, (modelId >> 8) & 0xFF) / 256.0;
    vec2 faceBase = vec2(face >> 1, face & 1) / (vec2(3.0, 2.0) * 256.0);
    vec2 tile;
    vec2 repeatedUv = modf(texCoord0, tile);
    vec2 atlasScale = vec2(1.0) / (vec2(3.0, 2.0) * 256.0);
    vec2 atlasUv = modelBase + faceBase + repeatedUv * atlasScale;
    vec4 color = textureGrad(Sampler0, atlasUv,
            dFdx(texCoord0 * atlasScale), dFdy(texCoord0 * atlasScale));
    float mipZeroAlpha = textureLod(Sampler0, atlasUv, 0.0).a;

    bool translucent = ((quadFlags >> 14) & 1) != 0;
    bool useCutout = ((quadFlags >> 13) & 1) != 0;
    if ((translucent && mipZeroAlpha == 0.0) || (!translucent && useCutout && mipZeroAlpha <= 0.1)) {
        discard;
    }
    if (!translucent) {
        color.a = 1.0;
    }

    int tintState = (quadFlags >> 11) & 3;
    bool applyTint = tintState == 2;
    if (tintState == 1) {
        vec4 tintTest = textureLod(Sampler0, atlasUv, 0.0);
        applyTint = abs(tintTest.r - tintTest.g) < 0.02 && abs(tintTest.g - tintTest.b) < 0.02;
    }
    if (applyTint) {
        color *= tintColor;
    }
    color *= vertexLighting;
    // Render-distance fog is deliberately disabled for both Sodium and Voxy and applied once
    // from their combined depth. Environmental fog stays per geometry, matching Sodium.
    float fogValue = linearFogValue(
            sphericalDistance, FogEnvironmentalStart, FogEnvironmentalEnd);
    color.rgb = mix(color.rgb, FogColor.rgb, fogValue * FogColor.a);
    fragColor = color * ColorModulator;
}
