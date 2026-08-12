package me.cortex.voxy.client.core.model;

import me.cortex.voxy.client.core.rendering.util.IDeviceBuffer;
import me.cortex.voxy.common.util.MemoryBuffer;

//Backend-neutral model store: the block-model data buffer, the biome/colour
// buffer, and the baked model texture atlas. Implemented by ModelStore (GL)
// and VkModelStore (pure Vulkan) so ModelFactory's CPU-side baking pipeline is
// shared verbatim; only the atlas texture upload differs per API.
public interface IModelStore {
    IDeviceBuffer modelBufferHandle();

    IDeviceBuffer colourBufferHandle();

    /** Called once before a batch of texture uploads (GL: unpack-state reset; VK: layout transition). */
    void beginTextureUploads();

    //Upload one baked model's mip chain into its atlas slot. Layout of texture:
    // LAYERS consecutive RGBA8 mip images of a (MODEL_TEXTURE_SIZE*3 x
    // MODEL_TEXTURE_SIZE*2) tile, tightly packed.
    void uploadModelTexture(int modelId, MemoryBuffer texture);

    /** Called once after a batch of texture uploads (VK: transition back to sampled). */
    void endTextureUploads();

    void free();
}
