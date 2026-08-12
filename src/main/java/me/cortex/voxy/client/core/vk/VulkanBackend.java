package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.common.Logger;

//Capability detection + lifecycle for the Vulkan backend.
//Voxy follows Minecraft's own graphics API: when MC runs on its 26.2 Vulkan
// backend every rendering mod draws through Blaze3D on Vulkan (no GL context
// exists in the process), so Voxy adopts MC's VkDevice/queue via IVkHost instead
// of creating its own. When MC is on OpenGL Voxy uses its OpenGL (MDIC) backend.
//There is no fallback either way: a GL context cannot exist while MC is on Vulkan.
public final class VulkanBackend {
    private static Boolean supported;
    private static VulkanContext context;
    private static String unsupportedReason = "not probed";


    //True when MC is on Vulkan AND the host adapter is registered AND the LWJGL
    // Vulkan bindings + MC's device could be adopted.
    public static boolean shouldUseVulkan() {
        if (!MinecraftVkHost.isMinecraftOnVulkan()) {
            return false;//MC is on OpenGL -> Voxy follows it onto OpenGL
        }
        if (MinecraftVkHost.get() == null) {
            //MC reports Vulkan but the Blaze3D-VK adapter has not registered a host yet
            Logger.info("Voxy: Minecraft on Vulkan but host adapter not yet registered");
            return false;
        }
        return isSupported();
    }

    public static synchronized boolean isSupported() {
        if (supported == null) {
            try {
                Class.forName("org.lwjgl.vulkan.VK10");
                var host = MinecraftVkHost.get();
                if (host == null) {
                    supported = false;
                    unsupportedReason = "no Minecraft Vulkan host adapter";
                } else {
                    context = VulkanContext.adopt(host);
                    supported = true;
                    unsupportedReason = null;
                    Logger.info("Voxy Vulkan backend adopting Minecraft's device: " + context.deviceName);
                }
            } catch (Throwable t) {
                supported = false;
                unsupportedReason = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                Logger.info("Voxy Vulkan backend unavailable: " + unsupportedReason);
            }
        }
        return supported;
    }

    public static synchronized VulkanContext context() {
        if (!isSupported()) throw new IllegalStateException("Vulkan not supported: " + unsupportedReason);
        return context;
    }

    public static String statusLine() {
        if (supported == null) return "vk: unprobed";
        return supported ? ("vk: host(" + context.deviceName + ")") : ("vk: unavailable (" + unsupportedReason + ")");
    }

    public static synchronized void shutdown() {
        //Host-adopted: do not destroy MC's device (VulkanContext.destroy handles this)
        if (context != null) { context.destroy(); context = null; }
        supported = null;
    }

    private VulkanBackend() {}
}
