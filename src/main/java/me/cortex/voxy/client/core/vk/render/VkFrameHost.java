package me.cortex.voxy.client.core.vk.render;

//? if MC_26_3
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
//? else
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
//? endif
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
    // Combined depth-stencil images transition both aspects together since
    // Voxy never enables separateDepthStencilLayouts. Depth-only images must
    // not include a stencil aspect.
    public static void transitionMcImage(VkCommandBuffer cmd, GpuTextureView view,
                                          boolean depth, int oldLayout, int newLayout) {
        transitionMcImage(cmd, view, depth, oldLayout, newLayout, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
    }

    public static void transitionMcImage(VkCommandBuffer cmd, GpuTextureView view,
                                          boolean depth, int oldLayout, int newLayout, int sampledStage) {
        try (MemoryStack stack = stackPush()) {
            long image = ((VulkanGpuTexture) view.texture()).vkImage();
            //MC's depth attachment is written by the late fragment tests; MC's
            // colour by colour-attachment output. The compositor samples from
            // a fragment shader; SSAO samples from compute. Scope both the
            // forward and reverse dependency to the actual sampling stage.
            int srcStage, srcAccess, dstStage, dstAccess;
            boolean toSampled = newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            if (depth) {
                if (toSampled) {
                    srcStage = VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT;
                    srcAccess = VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
                    dstStage = sampledStage;
                    dstAccess = VK_ACCESS_SHADER_READ_BIT;
                } else {
                    srcStage = sampledStage;
                    srcAccess = VK_ACCESS_SHADER_READ_BIT;
                    dstStage = VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT;
                    dstAccess = VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
                }
            } else {
                if (toSampled) {
                    srcStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
                    srcAccess = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
                    dstStage = sampledStage;
                    dstAccess = VK_ACCESS_SHADER_READ_BIT;
                } else {
                    srcStage = sampledStage;
                    srcAccess = VK_ACCESS_SHADER_READ_BIT;
                    dstStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
                    dstAccess = VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
                }
            }
            int format = depth ? vkFormat(view) : VK_FORMAT_UNDEFINED;
            boolean stencil = format == VK_FORMAT_D16_UNORM_S8_UINT
                    || format == VK_FORMAT_D24_UNORM_S8_UINT
                    || format == VK_FORMAT_D32_SFLOAT_S8_UINT;
            int aspectMask = !depth ? VK_IMAGE_ASPECT_COLOR_BIT
                    : VK_IMAGE_ASPECT_DEPTH_BIT | (stencil ? VK_IMAGE_ASPECT_STENCIL_BIT : 0);
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
