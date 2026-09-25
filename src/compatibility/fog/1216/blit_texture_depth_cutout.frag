#version 450 core

layout(binding = 0) uniform sampler2D colourTex;
layout(binding = 1) uniform sampler2D depthTex;
layout(location = 2) uniform mat4 invProjMat;
layout(location = 3) uniform mat4 projMat;
// Sodium FogParameters: environmental start/end, then render-distance start/end.
layout(location = 4) uniform vec4 fogParams;
layout(location = 5) uniform vec4 fogColour;
layout(location = 6) uniform vec4 fadeParams;

out vec4 colour;
in vec2 UV;

vec3 rev3d(vec3 clip) {
    vec4 view = invProjMat * vec4(clip * 2.0 - 1.0, 1.0);
    return view.xyz / view.w;
}

float projDepth(vec3 pos) {
    vec4 view = projMat * vec4(pos, 1.0);
    return view.z / view.w;
}

float linearFog(float vertexDistance, float start, float end) {
    if (vertexDistance <= start) return 0.0;
    if (vertexDistance >= end) return 1.0;
    return (vertexDistance - start) / (end - start);
}

void main() {
    colour = texture(colourTex, UV);
    if (colour.a == 0.0) discard;

    float depth = texture(depthTex, UV).r;
    if (depth == 0.0 || depth == 1.0) discard;

    vec3 point = rev3d(vec3(UV, depth));
    float sphericalDistance = length(point);
    if (fogColour.a > 0.0) {
        float cylindricalDistance = max(length(point.xz), abs(point.y));
        float fogAmount = max(
                linearFog(sphericalDistance, fogParams.x, fogParams.y),
                linearFog(cylindricalDistance, fogParams.z, fogParams.w));
        colour.rgb = mix(colour.rgb, fogColour.rgb, fogAmount * fogColour.a);
    }
    if (fadeParams.x > 0.0) {
        float fadeDistance = fadeParams.x > 1.5 ? sphericalDistance : length(point.xz);
        colour.a *= 1.0 - clamp(fma(fadeDistance, fadeParams.z, fadeParams.y), 0.0, 1.0);
    }

    depth = projDepth(point);
    depth = min(1.0 - (2.0 / ((1 << 24) - 1)), depth);
    depth = depth * 0.5 + 0.5;
    depth = gl_DepthRange.diff * depth + gl_DepthRange.near;
    gl_FragDepth = depth;
}
