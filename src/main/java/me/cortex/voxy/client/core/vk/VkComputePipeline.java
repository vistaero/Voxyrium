package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.client.core.gl.shader.ShaderType;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;

import static me.cortex.voxy.client.core.vk.VkUtil.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Compute pipeline for the pure-VK path: prep/cull/prefixsum/cmdgen and,
 * phase-3, the hierarchical traversal itself — the same GLSL compute sources
 * as GL, compiled via shaderc. Descriptors: one set of N storage buffers +
 * one UBO at binding 0, mirroring the GL binding-base layout so the shared
 * shader sources keep a single binding scheme under VOXY_VULKAN.
 */
public final class VkComputePipeline {
    private final VulkanContext ctx;
    public final long descriptorSetLayout;
    public final long pipelineLayout;
    public final long pipeline;
    private final long shaderModule;

    public VkComputePipeline(VulkanContext ctx, String glslSource, String name, int uboCount, int ssboCount) {
        this.ctx = ctx;
        ByteBuffer spv = ShadercCompiler.compile(glslSource, ShaderType.COMPUTE, name);
        try (MemoryStack stack = stackPush()) {
            var smci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spv);
            var pMod = stack.mallocLong(1);
            check(vkCreateShaderModule(ctx.device, smci, null, pMod), "vkCreateShaderModule(" + name + ")");
            this.shaderModule = pMod.get(0);

            var bindings = VkDescriptorSetLayoutBinding.calloc(uboCount + ssboCount, stack);
            for (int i = 0; i < uboCount; i++) {
                bindings.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                        .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            for (int i = 0; i < ssboCount; i++) {
                bindings.get(uboCount + i).binding(uboCount + i)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            var dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings);
            var pDsl = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(ctx.device, dslci, null, pDsl), "vkCreateDescriptorSetLayout(" + name + ")");
            this.descriptorSetLayout = pDsl.get(0);

            var plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(this.descriptorSetLayout));
            var pPl = stack.mallocLong(1);
            check(vkCreatePipelineLayout(ctx.device, plci, null, pPl), "vkCreatePipelineLayout(" + name + ")");
            this.pipelineLayout = pPl.get(0);

            var cpci = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default()
                    .layout(this.pipelineLayout);
            cpci.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(this.shaderModule).pName(stack.UTF8("main"));
            var pPipe = stack.mallocLong(1);
            check(vkCreateComputePipelines(ctx.device, VK_NULL_HANDLE, cpci, null, pPipe), "vkCreateComputePipelines(" + name + ")");
            this.pipeline = pPipe.get(0);
        }
    }

    public void bindAndDispatch(VkCommandBuffer cmd, long descriptorSet, int gx, int gy, int gz) {
        try (MemoryStack stack = stackPush()) {
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipelineLayout,
                    0, stack.longs(descriptorSet), null);
            vkCmdDispatch(cmd, gx, gy, gz);
        }
    }

    public void free() {
        vkDestroyPipeline(this.ctx.device, this.pipeline, null);
        vkDestroyPipelineLayout(this.ctx.device, this.pipelineLayout, null);
        vkDestroyDescriptorSetLayout(this.ctx.device, this.descriptorSetLayout, null);
        vkDestroyShaderModule(this.ctx.device, this.shaderModule, null);
    }
}
