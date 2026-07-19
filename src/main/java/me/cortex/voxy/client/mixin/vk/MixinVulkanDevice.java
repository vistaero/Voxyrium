package me.cortex.voxy.client.mixin.vk;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import me.cortex.voxy.client.core.vk.MinecraftVkHost;
import me.cortex.voxy.client.core.vk.MinecraftVkHostAdapter;
import me.cortex.voxy.common.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//The Blaze3D-VK adapter. When MC 26.2 initialises its own Vulkan device
// (Graphics API = Vulkan), this registers an IVkHost backed by that live
// device so Voxy adopts it (no second VkDevice) and records into MC's frame.
// Cleared when MC tears the device down (shutdown), after which Voxy has no
// host and is inactive until a device is registered again.
//
//If MC is on OpenGL this class is simply never instantiated, so the host stays
// unregistered and Voxy uses its OpenGL backend — no runtime cost on the GL path.
@Mixin(VulkanDevice.class)
public class MixinVulkanDevice {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void voxy$registerHost(CallbackInfo ci) {
        try {
            MinecraftVkHost.register(new MinecraftVkHostAdapter((VulkanDevice) (Object) this));
            Logger.info("Voxy: adopted Minecraft's Vulkan device (pure-VK host mode available)");
        } catch (Throwable t) {
            //Never let Voxy's adapter break MC's device creation. With no host
            // registered Voxy stays inactive.
            Logger.warn("Voxy: failed to register Vulkan host adapter, Voxy will be inactive: " + t);
            MinecraftVkHost.clear();
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void voxy$clearHost(CallbackInfo ci) {
        MinecraftVkHost.clear();
    }
}
