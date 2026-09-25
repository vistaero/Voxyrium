package me.cortex.voxy.client.core.backend.blaze3d;

import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.world.level.CardinalLighting;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Packs one backend-neutral instance per Cortex quad; the vertex shader expands its corners. */
final class Blaze3dSectionMesh implements AutoCloseable {
    static final int QUAD_STRIDE = Blaze3dQuadEncoder.STRIDE;

    private final long position;
    private final @Nullable ByteBuffer instances;
    private final int opaqueQuadCount;
    private final int translucentQuadCount;
    private final long requiredTextureVersion;

    private Blaze3dSectionMesh(long position, @Nullable ByteBuffer instances,
                              int opaqueQuadCount, int translucentQuadCount, long requiredTextureVersion) {
        this.position = position;
        this.instances = instances;
        this.opaqueQuadCount = opaqueQuadCount;
        this.translucentQuadCount = translucentQuadCount;
        this.requiredTextureVersion = requiredTextureVersion;
    }

    static Blaze3dSectionMesh pack(BuiltSection section, Blaze3dModelStore models, CardinalLighting lighting) {
        if (section.isEmpty()) {
            return new Blaze3dSectionMesh(section.position, null, 0, 0, 0L);
        }

        int totalQuads = (int) (section.geometryBuffer.size / Long.BYTES);
        int translucentQuads = section.offsets[1] - section.offsets[0];
        int opaqueQuads = totalQuads - translucentQuads;
        ByteBuffer instances = null;
        try {
            instances = allocateInstances(totalQuads);
            long source = section.geometryBuffer.address;
            long requiredTextureVersion = 0L;
            int level = WorldEngine.getLevel(section.position);
            float sectionSize = 32.0f * (1 << level);
            float originX = WorldEngine.getX(section.position) * sectionSize;
            float originY = WorldEngine.getY(section.position) * sectionSize;
            float originZ = WorldEngine.getZ(section.position) * sectionSize;
            int previousModel = -1;
            int previousBiome = -1;
            int modelFlags = 0;
            int tint = -1;
            for (int index = 0; index < totalQuads; index++) {
                long quad = MemoryUtil.memGetLong(source + (long) index * Long.BYTES);
                int face = (int) (quad & 7L);
                int modelId = (int) ((quad >>> 26) & 0xFFFFL);
                int biomeId = (int) ((quad >>> 46) & 0x1FFL);
                if (modelId != previousModel) {
                    modelFlags = models.modelFlags(modelId);
                    requiredTextureVersion = Math.max(requiredTextureVersion, models.textureVersion(modelId));
                    previousModel = modelId;
                    previousBiome = -1;
                }
                if (biomeId != previousBiome) {
                    tint = models.tintColour(modelId, biomeId);
                    if (tint == -1) tint = 0xFFFF_FFFF;
                    previousBiome = biomeId;
                }
                int shade = Math.clamp(Math.round(directionalTint(lighting, (modelFlags & 8) != 0, face)
                        * 255.0f), 0, 255);
                Blaze3dQuadEncoder.put(instances,
                        originX, originY, originZ, quad, models.faceData(modelId, face),
                        tint, shade, modelFlags, level);
            }
            if (instances != null) instances.flip();
            return new Blaze3dSectionMesh(section.position, instances, opaqueQuads, translucentQuads,
                    requiredTextureVersion);
        } catch (RuntimeException | OutOfMemoryError exception) {
            free(instances);
            throw exception;
        }
    }

    private static @Nullable ByteBuffer allocateInstances(int quadCount) {
        if (quadCount == 0) {
            return null;
        }
        long byteCount = (long) quadCount * QUAD_STRIDE;
        if (byteCount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Packed LoD section is too large: " + byteCount + " bytes");
        }
        return MemoryUtil.memAlloc((int) byteCount).order(ByteOrder.nativeOrder());
    }

    private static float directionalTint(CardinalLighting lighting, boolean shaded, int face) {
        if (!shaded) return lighting.up();
        return switch (face >> 1) {
            case 1 -> lighting.north();
            case 2 -> lighting.east();
            default -> face == 1 ? lighting.up() : lighting.down();
        };
    }

    long position() {
        return this.position;
    }

    @Nullable ByteBuffer instances() {
        return this.instances;
    }

    int opaqueQuadCount() {
        return this.opaqueQuadCount;
    }

    int translucentQuadCount() {
        return this.translucentQuadCount;
    }

    long requiredTextureVersion() {
        return this.requiredTextureVersion;
    }

    long geometryBytes() {
        return (long) (this.opaqueQuadCount + this.translucentQuadCount)
                * QUAD_STRIDE;
    }

    boolean isEmpty() {
        return this.opaqueQuadCount == 0 && this.translucentQuadCount == 0;
    }

    @Override
    public void close() {
        free(this.instances);
    }

    private static void free(@Nullable ByteBuffer buffer) {
        if (buffer != null) {
            MemoryUtil.memFree(buffer);
        }
    }
}
