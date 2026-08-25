package me.cortex.voxy.client.core.vk;

import org.lwjgl.vulkan.VK10;

public final class VkUtil {
    private VkUtil() {}
    public static int check(int vkResult, String what) {
        if (vkResult != VK10.VK_SUCCESS) {
            throw new IllegalStateException("Vulkan call failed: " + what + " -> " + vkResult);
        }
        return vkResult;
    }
}
