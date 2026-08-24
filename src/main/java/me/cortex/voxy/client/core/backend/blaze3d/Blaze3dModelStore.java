package me.cortex.voxy.client.core.backend.blaze3d;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import me.cortex.voxy.client.core.model.IModelStore;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.client.core.rendering.util.IDeviceBuffer;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * CPU mirror of Cortex's model store plus a Blaze3D-owned copy of its baked model atlas.
 * RenderDataFactory still owns model selection and quad generation; this class only makes its
 * already-computed model metadata and texture tiles available to the Blaze3D expansion step.
 */
final class Blaze3dModelStore implements IModelStore {
    private static final int MODEL_CAPACITY = 1 << 16;
    private static final int MODEL_INTS = IModelStore.MODEL_SIZE / Integer.BYTES;
    private static final int COLOUR_CAPACITY = 1 << 16;
    private static final int ATLAS_WIDTH = ModelFactory.MODEL_TEXTURE_SIZE * 3 * 256;
    private static final int ATLAS_HEIGHT = ModelFactory.MODEL_TEXTURE_SIZE * 2 * 256;

    private final int[] modelData = new int[MODEL_CAPACITY * MODEL_INTS];
    private final int[] colourData = new int[COLOUR_CAPACITY];
    private final AtomicIntegerArray readyModels = new AtomicIntegerArray(MODEL_CAPACITY);
    private final AtomicIntegerArray colourIndices = new AtomicIntegerArray(MODEL_CAPACITY);
    private final AtomicLongArray modelTextureVersions = new AtomicLongArray(MODEL_CAPACITY);
    private final ConcurrentLinkedQueue<TextureUpload> pendingTextures = new ConcurrentLinkedQueue<>();
    private final AtomicLong stagedTextureVersion = new AtomicLong();
    private volatile long uploadedTextureVersion;
    private final GpuTexture atlas;
    private final GpuTextureView atlasView;
    private volatile boolean freed;

    Blaze3dModelStore() {
        RenderSystem.assertOnRenderThread();
        this.atlas = RenderSystem.getDevice().createTexture(
                "Voxy Blaze3D baked model atlas",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM,
                ATLAS_WIDTH,
                ATLAS_HEIGHT,
                1,
                ModelFactory.LAYERS);
        this.atlasView = RenderSystem.getDevice().createTextureView(this.atlas);
    }

    @Override
    public void stageModelData(int modelId, MemoryBuffer model, int biomeUploadIndex,
                               @Nullable MemoryBuffer biomeUpload, MemoryBuffer texture) {
        if (this.freed) {
            return;
        }
        ByteBuffer modelBytes = model.asByteBuffer().duplicate().order(ByteOrder.nativeOrder());
        modelBytes.asIntBuffer().get(this.modelData, modelId * MODEL_INTS, MODEL_INTS);
        if (biomeUploadIndex != -1 && biomeUpload != null) {
            copyColours(biomeUpload, biomeUploadIndex);
        }
        this.colourIndices.set(modelId, this.modelData[modelId * MODEL_INTS + 7]);

        byte[] textureBytes = new byte[(int) texture.size];
        texture.asByteBuffer().duplicate().get(textureBytes);
        long textureVersion = this.stagedTextureVersion.incrementAndGet();
        this.pendingTextures.add(new TextureUpload(modelId, textureVersion, textureBytes));
        this.modelTextureVersions.set(modelId, textureVersion);
        // Atomic publication gives mesh workers an acquire edge for modelData and colourData.
        this.readyModels.set(modelId, 1);
    }

    @Override
    public void stageBiomeData(MemoryBuffer biomeColourBuffer, MemoryBuffer modelBiomeIndexPairs) {
        if (this.freed) {
            return;
        }
        copyColours(biomeColourBuffer, 0);
        long pointer = modelBiomeIndexPairs.address;
        for (long offset = 0; offset < modelBiomeIndexPairs.size; offset += Long.BYTES) {
            long pair = MemoryUtil.memGetLong(pointer + offset);
            int modelId = (int) pair;
            int colourIndex = (int) (pair >>> 32);
            this.colourIndices.set(modelId, colourIndex);
        }
    }

    private void copyColours(MemoryBuffer source, int destinationIndex) {
        int count = (int) (source.size / Integer.BYTES);
        source.asByteBuffer().duplicate().order(ByteOrder.nativeOrder()).asIntBuffer()
                .get(this.colourData, destinationIndex, count);
    }

    int faceData(int modelId, int face) {
        assertReady(modelId);
        return this.modelData[modelId * MODEL_INTS + face];
    }

    int modelFlags(int modelId) {
        assertReady(modelId);
        return this.modelData[modelId * MODEL_INTS + 6];
    }

    int tintColour(int modelId, int biomeId) {
        assertReady(modelId);
        int base = modelId * MODEL_INTS;
        int flags = this.modelData[base + 6];
        int colour = this.colourIndices.get(modelId);
        if ((flags & 2) != 0) {
            int index = colour + biomeId;
            return index >= 0 && index < this.colourData.length ? this.colourData[index] : -1;
        }
        return colour;
    }

    private void assertReady(int modelId) {
        if (modelId < 0 || modelId >= MODEL_CAPACITY || this.readyModels.get(modelId) == 0) {
            throw new IllegalStateException("Cortex model data was not staged for model " + modelId);
        }
    }

    void uploadPendingTextures(CommandEncoder encoder) {
        RenderSystem.assertOnRenderThread();
        TextureUpload upload;
        while ((upload = this.pendingTextures.poll()) != null) {
            ByteBuffer data = MemoryUtil.memAlloc(upload.data().length);
            try {
                data.put(upload.data()).flip();
                int x = (upload.modelId() & 0xFF) * ModelFactory.MODEL_TEXTURE_SIZE * 3;
                int y = ((upload.modelId() >> 8) & 0xFF) * ModelFactory.MODEL_TEXTURE_SIZE * 2;
                int offset = 0;
                for (int level = 0; level < ModelFactory.LAYERS; level++) {
                    int width = (ModelFactory.MODEL_TEXTURE_SIZE * 3) >> level;
                    int height = (ModelFactory.MODEL_TEXTURE_SIZE * 2) >> level;
                    int bytes = width * height * Integer.BYTES;
                    ByteBuffer mip = data.duplicate();
                    mip.position(offset).limit(offset + bytes);
                    encoder.writeToTexture(this.atlas, mip.slice(), level, 0,
                            x >> level, y >> level, width, height);
                    offset += bytes;
                }
                this.uploadedTextureVersion = upload.version();
            } finally {
                MemoryUtil.memFree(data);
            }
        }
    }

    GpuTextureView atlasView() {
        return this.atlasView;
    }

    long textureVersion(int modelId) {
        assertReady(modelId);
        return this.modelTextureVersions.get(modelId);
    }

    boolean isTextureVersionUploaded(long version) {
        return this.uploadedTextureVersion >= version;
    }

    GpuSampler atlasSampler() {
        return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST, true);
    }

    @Override
    public void uploadModelData(int modelId, MemoryBuffer model, int biomeUploadIndex,
                                @Nullable MemoryBuffer biomeUpload, MemoryBuffer texture) {
        // stageModelData already published both the CPU mirror and queued Blaze3D texture copy.
    }

    @Override
    public void uploadBiomeData(MemoryBuffer biomeColourBuffer, MemoryBuffer modelBiomeIndexPairs) {
        // stageBiomeData already updated the CPU mirror.
    }

    @Override
    public void finishUploads() {
    }

    @Override
    public IDeviceBuffer modelBufferHandle() {
        throw new UnsupportedOperationException("Blaze3D model metadata is CPU-backed");
    }

    @Override
    public IDeviceBuffer colourBufferHandle() {
        throw new UnsupportedOperationException("Blaze3D model colours are CPU-backed");
    }

    @Override
    public void beginTextureUploads() {
    }

    @Override
    public void uploadModelTexture(int modelId, MemoryBuffer texture) {
    }

    @Override
    public void endTextureUploads() {
    }

    @Override
    public void free() {
        RenderSystem.assertOnRenderThread();
        this.freed = true;
        this.pendingTextures.clear();
        this.atlasView.close();
        this.atlas.close();
    }

    private record TextureUpload(int modelId, long version, byte[] data) {
    }
}
