package me.cortex.voxy.client.core.vk.render;

import me.cortex.voxy.client.core.model.IModelStore;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.client.core.model.ModelStore;
import me.cortex.voxy.client.core.rendering.util.IDeviceBuffer;
import me.cortex.voxy.client.core.vk.VkBuffer;
import me.cortex.voxy.client.core.vk.VkFrameCtx;
import me.cortex.voxy.client.core.vk.VkImage2D;
import me.cortex.voxy.client.core.vk.VkUploadStream;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import static me.cortex.voxy.client.core.vk.VkUtil.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

//Pure-VK model store: block-model data + biome colour VkBuffers and the baked
// model texture atlas as a mipped RGBA8 VkImage. Texture tiles stream through
// the upload staging buffer with vkCmdCopyBufferToImage per mip; the batch is
// bracketed by TRANSFER_DST <-> SHADER_READ_ONLY transitions.
public class VkModelStore implements IModelStore {
    private final VkFrameCtx ctx;
    private final VkUploadStream uploadStream;
    final VkBuffer modelBuffer;
    final VkBuffer modelColourBuffer;
    final VkImage2D atlas;
    public final long atlasSampler;
    private boolean inUploadBatch;

    public VkModelStore(VkFrameCtx ctx, VkUploadStream uploadStream) {
        this.ctx = ctx;
        this.uploadStream = uploadStream;
        this.modelBuffer = new VkBuffer(ctx, ModelStore.MODEL_SIZE * (1L << 16)).zero();
        this.modelColourBuffer = new VkBuffer(ctx, 4L * (1 << 16)).zero();
        this.atlas = new VkImage2D(ctx,
                ModelFactory.MODEL_TEXTURE_SIZE * 3 * 256,
                ModelFactory.MODEL_TEXTURE_SIZE * 2 * 256,
                ModelFactory.LAYERS,
                VK_FORMAT_R8G8B8A8_UNORM,
                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                VK_IMAGE_ASPECT_COLOR_BIT, false);
        //Start life in shader-read so the first frame can bind it even with no uploads yet
        this.atlas.transition(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        ctx.flushImmediate();

        try (MemoryStack stack = stackPush()) {
            //Mirror the GL sampler: nearest mag, nearest-within-mip + linear-between-mips min
            var sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK_FILTER_NEAREST)
                    .minFilter(VK_FILTER_NEAREST)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .minLod(0).maxLod(ModelFactory.LAYERS - 1);
            var pSampler = stack.mallocLong(1);
            check(vkCreateSampler(ctx.vk().device, sci, null, pSampler), "vkCreateSampler(modelAtlas)");
            this.atlasSampler = pSampler.get(0);
        }
    }

    @Override
    public IDeviceBuffer modelBufferHandle() {
        return this.modelBuffer;
    }

    @Override
    public IDeviceBuffer colourBufferHandle() {
        return this.modelColourBuffer;
    }

    @Override
    public void beginTextureUploads() {
        this.atlas.transition(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
        this.inUploadBatch = true;
    }

    @Override
    public void uploadModelTexture(int modelId, MemoryBuffer texture) {
        if (!this.inUploadBatch) throw new IllegalStateException("Texture upload outside batch");
        final int TS = ModelFactory.MODEL_TEXTURE_SIZE;
        int X = (modelId & 0xFF) * TS * 3;
        int Y = ((modelId >> 8) & 0xFF) * TS * 2;

        //Stage the full mip chain in one staging allocation
        int totalBytes = 0;
        for (int lvl = 0; lvl < ModelFactory.LAYERS; lvl++) {
            totalBytes += (TS * TS * 3 * 2 * 4) >> (lvl << 1);
        }
        long stageOff = this.uploadStream.rawUploadAddress(totalBytes);
        MemoryUtil.memCopy(texture.address, this.uploadStream.getBaseAddress() + stageOff, totalBytes);

        var cmd = this.ctx.cmd();
        try (MemoryStack stack = stackPush()) {
            var regions = VkBufferImageCopy.calloc(ModelFactory.LAYERS, stack);
            long srcOff = stageOff;
            for (int lvl = 0; lvl < ModelFactory.LAYERS; lvl++) {
                final int flvl = lvl;
                var r = regions.get(lvl);
                r.bufferOffset(srcOff).bufferRowLength(0).bufferImageHeight(0);
                r.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(lvl).baseArrayLayer(0).layerCount(1);
                r.imageOffset(o -> o.x(X >> flvl).y(Y >> flvl).z(0));
                r.imageExtent(e -> e.width((TS * 3) >> flvl).height((TS * 2) >> flvl).depth(1));
                srcOff += (TS * TS * 3 * 2 * 4) >> (lvl << 1);
            }
            vkCmdCopyBufferToImage(cmd, this.uploadStream.stagingBufferHandle(), this.atlas.image,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, regions);
        }
    }

    @Override
    public void endTextureUploads() {
        this.atlas.transition(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        this.inUploadBatch = false;
    }

    @Override
    public void free() {
        vkDestroySampler(this.ctx.vk().device, this.atlasSampler, null);
        this.modelBuffer.free();
        this.modelColourBuffer.free();
        this.atlas.free();
    }
}
