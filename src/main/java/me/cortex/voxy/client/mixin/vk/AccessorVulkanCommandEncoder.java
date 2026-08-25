package me.cortex.voxy.client.mixin.vk;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

//Exposes the command buffer MC's Vulkan encoder is currently recording into, so
// Voxy (in pure-VK host mode) can record its LOD draws into MC's frame instead
// of submitting a separate one. Null while MC has no open command buffer —
// callers must treat that as "not at a valid injection point yet".
@Mixin(VulkanCommandEncoder.class)
public interface AccessorVulkanCommandEncoder {
    @Accessor("currentCommandBuffer")
    VkCommandBuffer voxy$currentCommandBuffer();
}
