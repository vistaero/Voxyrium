package me.cortex.voxy.client.core.vk;

/**
 * Adapter registry for the vanilla 26.2 Blaze3D Vulkan backend.
 *
 * STATUS: intentionally an unwired registration point. Binding this requires
 * mixing into MC 26.2's obfuscated Blaze3D-Vulkan classes (device holder, frame
 * command-buffer, swapchain attachments); those mixin targets must be authored
 * against the actual 26.2 mappings in a dev environment with the game jar —
 * guessing class/method names here would produce fiction, not integration.
 * The mixin, once written, calls {@link #register(IVkHost)} during render init
 * and {@link #clear()} on backend teardown/API switch.
 */
public final class MinecraftVkHost {
    private static volatile IVkHost host;

    public static void register(IVkHost h) { host = h; }
    public static void clear() { host = null; }
    /** Non-null only when MC itself is presenting through Vulkan and the adapter mixin is active. */
    public static IVkHost get() { return host; }
    public static boolean isMinecraftOnVulkan() { return host != null; }

    private MinecraftVkHost() {}
}
