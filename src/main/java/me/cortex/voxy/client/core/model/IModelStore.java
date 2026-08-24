package me.cortex.voxy.client.core.model;

import me.cortex.voxy.client.core.rendering.util.IDeviceBuffer;
import me.cortex.voxy.client.core.rendering.util.AbstractUploadStream;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

//Backend-neutral model store: the block-model data buffer, the biome/colour
// buffer, and the baked model texture atlas. Implemented by ModelStore (GL)
// and VkModelStore (pure Vulkan) so ModelFactory's CPU-side baking pipeline is
// shared verbatim; only the atlas texture upload differs per API.
public interface IModelStore {
    int MODEL_SIZE = 64;

    IDeviceBuffer modelBufferHandle();

    IDeviceBuffer colourBufferHandle();

    /** Called once before a batch of texture uploads (GL: unpack-state reset; VK: layout transition). */
    void beginTextureUploads();

    //Upload one baked model's mip chain into its atlas slot. Layout of texture:
    // LAYERS consecutive RGBA8 mip images of a (MODEL_TEXTURE_SIZE*3 x
    // MODEL_TEXTURE_SIZE*2) tile, tightly packed.
    void uploadModelTexture(int modelId, MemoryBuffer texture);

    /**
     * Publishes the CPU-side result before ModelFactory exposes the model id to mesh builders.
     * Native stores keep using their device buffers; backend-neutral renderers can mirror the
     * exact Cortex model data here without adding a second model-baking implementation.
     */
    default void stageModelData(int modelId, MemoryBuffer model, int biomeUploadIndex,
                                @Nullable MemoryBuffer biomeUpload, MemoryBuffer texture) {
    }

    /** Publishes biome-colour changes to CPU mirrors before their queued device upload. */
    default void stageBiomeData(MemoryBuffer biomeColourBuffer, MemoryBuffer modelBiomeIndexPairs) {
    }

    /** Performs the normal native upload. CPU-backed stores may override this as a no-op. */
    default void uploadModelData(int modelId, MemoryBuffer model, int biomeUploadIndex,
                                 @Nullable MemoryBuffer biomeUpload, MemoryBuffer texture) {
        model.cpyTo(AbstractUploadStream.INSTANCE().upload(
                this.modelBufferHandle(), (long) modelId * MODEL_SIZE, MODEL_SIZE));
        if (biomeUploadIndex != -1 && biomeUpload != null) {
            biomeUpload.cpyTo(AbstractUploadStream.INSTANCE().upload(
                    this.colourBufferHandle(), biomeUploadIndex * 4L, biomeUpload.size));
        }
        this.uploadModelTexture(modelId, texture);
    }

    /** Performs the normal native biome upload. CPU-backed stores may override this as a no-op. */
    default void uploadBiomeData(MemoryBuffer biomeColourBuffer, MemoryBuffer modelBiomeIndexPairs) {
        biomeColourBuffer.cpyTo(AbstractUploadStream.INSTANCE().upload(
                this.colourBufferHandle(), 0, biomeColourBuffer.size));
        long ptr = modelBiomeIndexPairs.address;
        for (long offset = 0; offset < modelBiomeIndexPairs.size; offset += 8) {
            long value = MemoryUtil.memGetLong(ptr);
            ptr += 8;
            MemoryUtil.memPutInt(AbstractUploadStream.INSTANCE().upload(
                            this.modelBufferHandle(),
                            MODEL_SIZE * (value & 0xFFFF_FFFFL) + 4L * 6 + 4,
                            4),
                    (int) (value >>> 32));
        }
    }

    default void finishUploads() {
        AbstractUploadStream.INSTANCE().commit();
    }

    /** Called once after a batch of texture uploads (VK: transition back to sampled). */
    void endTextureUploads();

    void free();
}
