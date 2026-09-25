package me.cortex.voxy.client.core.backend.blaze3d;

import java.nio.ByteBuffer;

/** Binary contract shared by the instance vertex format and blaze3d_lod_terrain.vsh. */
final class Blaze3dQuadEncoder {
    static final int STRIDE = 32;

    private Blaze3dQuadEncoder() {}

    static void put(ByteBuffer out, float x, float y, float z, long quad, int faceData,
                    int tint, int shade, int modelFlags, int level) {
        out.putFloat(x).putFloat(y).putFloat(z);               // RGB32_FLOAT SectionOrigin
        out.putInt((int) quad).putInt((int) (quad >>> 32));   // RG32_UINT QuadData
        out.putInt(faceData);                               // R32_UINT FaceData
        out.put((byte) (tint >>> 16)).put((byte) (tint >>> 8)) // RGBA8_UNORM Color
                .put((byte) tint).put((byte) shade);
        out.putInt((modelFlags & 31) | (level << 8));         // R32_UINT Material
    }
}
