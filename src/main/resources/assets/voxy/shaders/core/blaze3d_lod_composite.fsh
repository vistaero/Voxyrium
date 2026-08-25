#version 330

layout(std140) uniform DynamicTransforms {
    // The Java side stores the inverse Voxy projection here for reconstruction.
    mat4 ModelViewMat;
    vec4 ColorModulator;
    // x is 1 when NDC depth already uses Vulkan's zero-to-one range; yz contain
    // the start/end distances of the Sodium-to-Voxy transition in blocks.
    vec3 ModelOffset;
    // Minecraft/Sodium projection used to produce the destination depth.
    mat4 TextureMat;
};

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    float lodDepth = texture(Sampler1, texCoord).r;
    if (lodDepth <= 0.0) {
        discard;
    }

    float lodNdcDepth = ModelOffset.x > 0.5 ? lodDepth : lodDepth * 2.0 - 1.0;
    vec4 viewPosition = ModelViewMat * vec4(texCoord * 2.0 - 1.0, lodNdcDepth, 1.0);
    viewPosition /= viewPosition.w;

    // Sodium's chunk horizon is horizontal. Using spherical XYZ distance would make Voxy enter
    // prematurely when looking down a slope, from a tower, or into a cave.
    float fragmentDistance = length(viewPosition.xz);
    if (fragmentDistance < ModelOffset.y) {
        discard;
    }
    if (ModelOffset.z > ModelOffset.y && fragmentDistance < ModelOffset.z) {
        // Stable 4x4 Bayer ordered dithering. Voxy gains coverage gradually in the final
        // Sodium chunk ring without alpha-blending two nearly coincident terrain surfaces.
        int x = int(gl_FragCoord.x) & 3;
        int y = int(gl_FragCoord.y) & 3;
        int bayer = ((x & 1) << 1) | (y & 1);
        bayer = (bayer << 2) | (((x >> 1) & 1) << 1) | ((y >> 1) & 1);
        float threshold = (float(bayer) + 0.5) / 16.0;
        float coverage = smoothstep(ModelOffset.y, ModelOffset.z, fragmentDistance);
        if (coverage <= threshold) {
            discard;
        }
    }

    vec4 minecraftClip = TextureMat * viewPosition;
    float minecraftDepth = minecraftClip.z / minecraftClip.w;
    if (ModelOffset.x <= 0.5) {
        minecraftDepth = minecraftDepth * 0.5 + 0.5;
    }

    // Minecraft 26.2 uses reverse-Z. Geometry beyond its own far plane maps below zero.
    // Reserve a very small depth band above the clear value for that terrain. Scaling the
    // original LoD depth within the band preserves ordering between LoD ground, water and glass,
    // while every real positive Sodium depth remains in front of it.
    float distantDepth = lodDepth / 1048576.0 + 2.0 / 16777215.0;
    // Pull Voxy a tiny amount away from the camera. Exact and near-exact Sodium surfaces then
    // win deterministically instead of alternating because of reconstruction/rounding noise.
    float sodiumBiasedDepth = max(0.0, minecraftDepth - 4.0 / 16777215.0);
    gl_FragDepth = minecraftDepth > 0.0 ? sodiumBiasedDepth : distantDepth;
    fragColor = texture(Sampler0, texCoord) * ColorModulator;
}
