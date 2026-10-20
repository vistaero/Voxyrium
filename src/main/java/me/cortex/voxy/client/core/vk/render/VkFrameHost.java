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

    //EXPERIMENTAL / UNTESTED. Some MC Vulkan builds appear to leave their images in
    // VK_IMAGE_LAYOUT_GENERAL instead of tracking attachment/sampled layouts. If
    // validation reports layout mismatches on MC-owned images, launch with
    // -Dvoxy.vk.mcImagesGeneral=true to make every Voxy access to an MC image use
    // GENERAL (barriers are still emitted for synchronisation). Default is off, which
    // keeps the original behaviour.
    private static final boolean MC_IMAGES_GENERAL = Boolean.getBoolean("voxy.vk.mcImagesGeneral");

    /** Maps a layout Voxy wants for an MC-owned image to the one actually used. */
    public static int mcLayout(int layout) {
        return MC_IMAGES_GENERAL && layout != VK_IMAGE_LAYOUT_UNDEFINED ? VK_IMAGE_LAYOUT_GENERAL : layout;
    }

    /** Layout for sampling an MC-owned image from a shader. */
    public static int mcSampledLayout() {
        return mcLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }

    private static boolean hasStencil(int vkFormat) {
        return vkFormat == VK_FORMAT_D16_UNORM_S8_UINT
                || vkFormat == VK_FORMAT_D24_UNORM_S8_UINT
                || vkFormat == VK_FORMAT_D32_SFLOAT_S8_UINT
                || vkFormat == VK_FORMAT_S8_UINT;
    }

    //Layout-transition one of MC's own images (colour/depth attachment) with
    // scoped stage/access masks matching the actual producer/consumer.
    // Combined depth-stencil images transition both aspects together (DEPTH|STENCIL)
    // since Voxy never enables separateDepthStencilLayouts; depth-only formats
    // (e.g. D32_SFLOAT) must use DEPTH alone (VUID-VkImageMemoryBarrier-image-03320).
    public static void transitionMcImage(VkCommandBuffer cmd, GpuTextureView view,
                                          boolean depth, int oldLayout, int newLayout) {
        //Direction is decided from the requested layout, before any GENERAL remap.
        final boolean toSampled = newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        oldLayout = mcLayout(oldLayout);
        newLayout = mcLayout(newLayout);
        try (MemoryStack stack = stackPush()) {
            long image = ((VulkanGpuTexture) view.texture()).vkImage();
            //MC's depth attachment is written by the late fragment tests; MC's
            // colour by the fragment shader. Voxy reads both from the fragment
            // shader (sampler). Reverse transitions are FRAGMENT_SHADER read ->
            // late-fragment-tests write.
            int srcStage, srcAccess, dstStage, dstAccess;
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
                        int aspectMask = !depth ? VK_IMAGE_ASPECT_COLOR_BIT
                    : hasStencil(vkFormat(view)) ? (VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT)
                    : VK_IMAGE_ASPECT_DEPTH_BIT;
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
