package me.cortex.voxy.client.core.vk.render;

import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

//Accessors for MC's live Vulkan frame resources at the render hook point:
// the world colour/depth attachment views MC is rendering into and the
// lightmap texture view. All calls are render-thread only.
public final class VkFrameHost {
    private VkFrameHost() {}

    /** VkImageView of MC's lightmap (bound as Voxy's terrain light sampler). */
    public static long lightmapView() {
        return ((VulkanGpuTextureView) Minecraft.getInstance().gameRenderer.levelLightmap()).vkImageView();
    }

    public static long vkView(GpuTextureView view) {
        return ((VulkanGpuTextureView) view).vkImageView();
    }

    public static int vkFormat(GpuTextureView view) {
        return VulkanConst.toVk(view.texture().getFormat());
    }

    //Layout-transition one of MC's own images (colour/depth attachment) with
    // scoped stage/access masks matching the actual producer/consumer.
    // Depth-stencil images transition both aspects together (DEPTH|STENCIL)
    // since Voxy never enables separateDepthStencilLayouts.
    public static void transitionMcImage(VkCommandBuffer cmd, GpuTextureView view,
                                          boolean depth, int oldLayout, int newLayout) {
        try (MemoryStack stack = stackPush()) {
            long image = ((VulkanGpuTexture) view.texture()).vkImage();
            //MC's depth attachment is written by the late fragment tests; MC's
            // colour by the fragment shader. Voxy reads both from the fragment
            // shader (sampler). Reverse transitions are FRAGMENT_SHADER read ->
            // late-fragment-tests write.
            int srcStage, srcAccess, dstStage, dstAccess;
            boolean toSampled = newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            if (depth) {
                if (toSampled) {
                    srcStage = VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT;
                    srcAccess = VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
                    dstStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
                    dstAccess = VK_ACCESS_SHADER_READ_BIT;
                } else {
                    srcStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
                    srcAccess = VK_ACCESS_SHADER_READ_BIT;
                    dstStage = VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT;
                    dstAccess = VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
                }
            } else {
                if (toSampled) {
                    srcStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
                    srcAccess = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
                    dstStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
                    dstAccess = VK_ACCESS_SHADER_READ_BIT;
                } else {
                    srcStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
                    srcAccess = VK_ACCESS_SHADER_READ_BIT;
                    dstStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
                    dstAccess = VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
                }
            }
            //Depth-stencil: transition both aspects together.
            int aspectMask = depth ? (VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT) : VK_IMAGE_ASPECT_COLOR_BIT;
            var imb = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(srcAccess).dstAccessMask(dstAccess)
                    .oldLayout(oldLayout).newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image);
            imb.subresourceRange()
                    .aspectMask(aspectMask)
                    .levelCount(VK_REMAINING_MIP_LEVELS)
                    .layerCount(VK_REMAINING_ARRAY_LAYERS);
            vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, imb);
        }
    }
}
