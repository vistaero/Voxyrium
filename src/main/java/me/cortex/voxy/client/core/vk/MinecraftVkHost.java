package me.cortex.voxy.client.core.vk;

/**
 * Adapter registry for the vanilla 26.2 Blaze3D Vulkan backend.
 *
 * The Blaze3D-VK adapter mixin ({@link MixinVulkanDevice}) calls {@link #register}
 * during render init and {@link #clear} on backend teardown. The mixin target
 * classes must be authored against the real 26.2 mappings in a dev environment
 * with the game jar, so are kept in the client mixin package.
 */
public final class MinecraftVkHost {
    private static volatile IVkHost host;

    public static void register(IVkHost h) { host = h; }
    public static void clear() { host = null; }
    /** Non-null only when MC itself is presenting through Vulkan and the adapter mixin is active. */
    public static IVkHost get() { return host; }

    //Whether MC is currently rendering through its own Vulkan backend.
    //Reads the live Blaze3D device so it reflects the Graphics API setting AFTER
    // MC's own capability fallback ladder has run, not merely the preference.
    public static boolean isMinecraftOnVulkan() {
        if (host != null) return true;//explicitly registered host is authoritative
        return detectActiveVulkan();
    }

    private static boolean detectActiveVulkan() {
        try {
            var device = com.mojang.blaze3d.systems.RenderSystem.tryGetDevice();
            if (device == null) return false;
            String backend = device.getDeviceInfo().backendName();
            return backend != null && backend.toLowerCase(java.util.Locale.ROOT).contains("vulkan");
        } catch (Throwable t) {
            return false;
        }
    }

    private MinecraftVkHost() {}
}
