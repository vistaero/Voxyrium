package me.cortex.voxy.client.core.vk;

import org.lwjgl.vulkan.VkDevice;

public final class VulkanContext {
    public final VkDevice device = null; // The deterministic driver never dereferences this.
    public final org.lwjgl.vulkan.VkQueue queue = null;
    public final long commandPool = 1L;
    public int findMemoryType(int bits, int properties) {
        if (org.lwjgl.vulkan.VK10.failMemoryType) throw new IllegalStateException("injected memory-type failure");
        return 0;
    }
}
