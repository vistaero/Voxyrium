package me.cortex.voxy.client.core.vk;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import me.cortex.voxy.client.core.model.bakery.IAtlasTextureReader;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

//Vulkan block-atlas readback: copies MC's stitched atlas image (mip 0) into a
// host-visible staging buffer via vkCmdCopyImageToBuffer and reads it into an
// int[] in the same RGBA8 byte order (R,G,B,A) the GL path produced. The atlas
// is VK_FORMAT_R8G8B8A8_UNORM, so the raw texel bytes match GL RGBA/UNSIGNED_BYTE
// one-for-one and the software rasterizer sees identical data.
//
//Runs once at model-bakery construction, outside any frame, through the frame
// ctx's immediate command buffer (submitted + waited synchronously). Assumes
// MC creates the atlas with TRANSFER_SRC usage and keeps it in
// SHADER_READ_ONLY_OPTIMAL between frames (validation layers flag both).
public final class VkAtlasTextureReader extends IAtlasTextureReader {
    private final VkFrameCtx frameCtx;

    public VkAtlasTextureReader(VkFrameCtx frameCtx) {
        this.frameCtx = frameCtx;
    }

    @Override
    public int[] read(GpuTexture atlas, int width, int height) {
        long image = ((VulkanGpuTexture) atlas).vkImage();
        long size = (long) width * height * 4;
        var staging = new VkBuffer(this.frameCtx, size, VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
        try {
            var cmd = this.frameCtx.cmd();
            //MC keeps the sampled atlas in SHADER_READ_ONLY_OPTIMAL; move mip 0 to
            // TRANSFER_SRC for the copy, then restore it so MC's sampling is unaffected.
            transition(cmd, image, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            try (MemoryStack stack = stackPush()) {
                var region = VkBufferImageCopy.calloc(1, stack)
                        .bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.imageOffset().set(0, 0, 0);
                region.imageExtent().set(width, height, 1);
                vkCmdCopyImageToBuffer(cmd, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, staging.buffer, region);
            }
            transition(cmd, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            this.frameCtx.flushImmediate();

            var out = new int[width * height];
            long ptr = staging.map();
            MemoryUtil.memIntBuffer(ptr, out.length).get(out);
            return out;
        } finally {
            //Deferred destroy; vkFreeMemory implicitly unmaps the staging map above.
            staging.free();
        }
    }

    private static void transition(VkCommandBuffer cmd, long image, int oldLayout, int newLayout) {
        try (MemoryStack stack = stackPush()) {
            var imb = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_MEMORY_READ_BIT)
                    .dstAccessMask(VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_MEMORY_READ_BIT)
                    .oldLayout(oldLayout).newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image);
            imb.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(VK_REMAINING_MIP_LEVELS)
                    .baseArrayLayer(0).layerCount(VK_REMAINING_ARRAY_LAYERS);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    0, null, null, imb);
        }
    }
}
