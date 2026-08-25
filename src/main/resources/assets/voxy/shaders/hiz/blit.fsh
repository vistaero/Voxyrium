#version 450


layout(location = 0) in vec2 uv;
layout(binding = 0) uniform sampler2D depthTex;
void main() {
    vec4 depths = textureGather(depthTex, uv, 0); // Get depth values from all surrounding texels.

    //TODO: fully fix this system

    //TODO, do it so that for the first 2,3 levels if 1 (or maybe even 2 (on the first layer)) pixels are air, just ignore that
    // this is to stop issues with 1 pixel gaps
    bvec4 cv = lessThanEqual(vec4(0.99999999), depths);
    if (any(cv) && !all(cv)) {
        depths = mix(vec4(1.0f), depths, cv);
    }
    gl_FragDepth = max(max(depths.x, depths.y), max(depths.z, depths.w)); // Write conservative depth.
}
