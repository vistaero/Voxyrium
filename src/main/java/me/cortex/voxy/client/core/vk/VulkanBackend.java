package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.Logger;

/**
 * Capability detection + lifecycle for the optional Vulkan backend.
 * GL remains the default; VK is only used when (a) the config toggle asks for it,
 * (b) a suitable device with GL-interop extensions exists, and (c) an Iris
 * shaderpack is NOT active (Iris patches Voxy's GLSL fragment path, which the
 * phase-1 VK backend cannot honor, so it is explicitly gated out).
 */
public final class VulkanBackend {
    private static Boolean supported;
    private static VulkanContext context;
    private static String unsupportedReason = "not probed";

    public static synchronized boolean isSupported() {
        if (supported == null) {
            try {
                Class.forName("org.lwjgl.vulkan.VK10");
                var probe = new VulkanContext();
                context = probe;
                supported = true;
                unsupportedReason = null;
            } catch (Throwable t) {
                supported = false;
                unsupportedReason = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                Logger.info("Voxy Vulkan backend unavailable: " + unsupportedReason);
            }
        }
        return supported;
    }

    public static boolean shouldUseVulkan(boolean configWantsVulkan) {
        if (!configWantsVulkan) return false;
        if (IrisUtil.IRIS_INSTALLED && IrisUtil.irisShaderpackActiveSafe()) {
            Logger.info("Voxy: Vulkan requested but Iris shaderpack active -> staying on OpenGL");
            return false;
        }
        return isSupported();
    }

    public static synchronized VulkanContext context() {
        if (!isSupported()) throw new IllegalStateException("Vulkan not supported: " + unsupportedReason);
        return context;
    }

    public static String statusLine() {
        if (supported == null) return "vk: unprobed";
        return supported ? ("vk: " + context.deviceName) : ("vk: unavailable (" + unsupportedReason + ")");
    }

    public static synchronized void shutdown() {
        if (context != null) { context.destroy(); context = null; supported = null; }
    }

    private VulkanBackend() {}
}
