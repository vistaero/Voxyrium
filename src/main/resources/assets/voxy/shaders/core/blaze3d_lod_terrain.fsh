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
in vec4 vertexColor;
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
    vec4 color = texture(Sampler0, texCoord0) * vertexColor;
    if (color.a == 0.0) {
        discard;
    }
    // Render-distance fog is deliberately disabled for both Sodium and Voxy and applied once
    // from their combined depth. Environmental fog stays per geometry, matching Sodium.
    float fogValue = linearFogValue(
            sphericalDistance, FogEnvironmentalStart, FogEnvironmentalEnd);
    color.rgb = mix(color.rgb, FogColor.rgb, fogValue * FogColor.a);
    fragColor = color * ColorModulator;
}
