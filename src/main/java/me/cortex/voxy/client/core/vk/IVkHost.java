package me.cortex.voxy.client.core.vk;

import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;

/**
 * The PURE-VK integration seam. When Minecraft 26.2+ itself runs on its
 * experimental Vulkan backend ("Prefer Vulkan" Graphics API setting), Voxy must
 * not create a second device: it adopts the game's device and records into the
 * game's frame. An adapter implements this against Blaze3D's Vulkan internals.
 *
 * This is also THE macOS path: MoltenVK has no VK_KHR_external_memory_fd, so
 * the GL-interop hybrid can never run there — on Mac, Voxy-on-Vulkan requires
 * MC-on-Vulkan (which vanilla 26.2 officially supports via MoltenVK).
 */
public interface IVkHost {
    VkInstance instance();
    VkPhysicalDevice physicalDevice();
    VkDevice device();
    VkQueue graphicsQueue();
    int graphicsQueueFamily();

    /** Command buffer currently recording for this frame's world rendering, at the LOD injection point. */
    VkCommandBuffer frameCommandBuffer();
}
