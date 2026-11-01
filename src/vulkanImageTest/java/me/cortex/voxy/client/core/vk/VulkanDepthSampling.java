package me.cortex.voxy.client.core.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.util.ArrayDeque;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.VK10.*;

/** Actual depth attachment writes -> compute samples -> attachment reuse.
 * Resources outlive submission; their owner closes only after queue idle. */
final class VulkanDepthSampling implements AutoCloseable {
    private final VkDevice device;
    private final ArrayDeque<Runnable> cleanup = new ArrayDeque<>();
    private long buffer, memory, renderPass, framebuffer, layout, pipeline, descriptor;

    private static void check(int result, String action) {
        if (result != VK_SUCCESS) throw new IllegalStateException(action + ": " + result);
    }

    VulkanDepthSampling(VkDevice device, VkPhysicalDevice physical, long image, int format) {
        this.device = device;
        try (MemoryStack s = stackPush()) {
            var out = s.mallocLong(1);
            var view = VkImageViewCreateInfo.calloc(s).sType$Default().image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
            view.subresourceRange().aspectMask(VulkanImageTest.aspect(format)).levelCount(1).layerCount(1);
            check(vkCreateImageView(device, view, null, out), "attachment view");
            long attachmentView = out.get(0); cleanup.addFirst(() -> vkDestroyImageView(device, attachmentView, null));
            view.subresourceRange().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT);
            check(vkCreateImageView(device, view, null, out), "sampled depth view");
            long sampleView = out.get(0); cleanup.addFirst(() -> vkDestroyImageView(device, sampleView, null));

            int attachmentLayout = VulkanImageTest.layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            var attachment = VkAttachmentDescription.calloc(1, s).format(format).samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(attachmentLayout).finalLayout(attachmentLayout);
            var reference = VkAttachmentReference.calloc(s).attachment(0).layout(attachmentLayout);
            var subpass = VkSubpassDescription.calloc(1, s).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).pDepthStencilAttachment(reference);
            check(vkCreateRenderPass(device, VkRenderPassCreateInfo.calloc(s).sType$Default().pAttachments(attachment).pSubpasses(subpass), null, out), "depth render pass");
            renderPass = out.get(0); cleanup.addFirst(() -> vkDestroyRenderPass(device, renderPass, null));
            check(vkCreateFramebuffer(device, VkFramebufferCreateInfo.calloc(s).sType$Default().renderPass(renderPass)
                    .pAttachments(s.longs(attachmentView)).width(16).height(16).layers(1), null, out), "depth framebuffer");
            framebuffer = out.get(0); cleanup.addFirst(() -> vkDestroyFramebuffer(device, framebuffer, null));

            check(vkCreateBuffer(device, VkBufferCreateInfo.calloc(s).sType$Default().size(8).usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, out), "sample output buffer");
            buffer = out.get(0);
            var req = VkMemoryRequirements.calloc(s); vkGetBufferMemoryRequirements(device, buffer, req);
            var props = VkPhysicalDeviceMemoryProperties.calloc(s); vkGetPhysicalDeviceMemoryProperties(physical, props);
            int type = -1, flags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
            for (int i = 0; i < props.memoryTypeCount(); i++)
                if ((req.memoryTypeBits() & (1 << i)) != 0 && (props.memoryTypes(i).propertyFlags() & flags) == flags) { type = i; break; }
            if (type < 0) { vkDestroyBuffer(device, buffer, null); throw new IllegalStateException("No coherent readback memory"); }
            try {
                check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(s).sType$Default().allocationSize(req.size()).memoryTypeIndex(type), null, out), "output memory");
            } catch (RuntimeException e) { vkDestroyBuffer(device, buffer, null); throw e; }
            memory = out.get(0);
            cleanup.addFirst(() -> { vkDestroyBuffer(device, buffer, null); vkFreeMemory(device, memory, null); });
            check(vkBindBufferMemory(device, buffer, memory, 0), "bind output buffer");

            var bindings = VkDescriptorSetLayoutBinding.calloc(2, s);
            bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default().pBindings(bindings), null, out), "sample descriptor layout");
            long setLayout = out.get(0); cleanup.addFirst(() -> vkDestroyDescriptorSetLayout(device, setLayout, null));
            var sizes = VkDescriptorPoolSize.calloc(2, s);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(s).sType$Default().maxSets(1).pPoolSizes(sizes), null, out), "sample descriptor pool");
            long pool = out.get(0); cleanup.addFirst(() -> vkDestroyDescriptorPool(device, pool, null));
            check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(s).sType$Default().descriptorPool(pool).pSetLayouts(s.longs(setLayout)), out), "sample descriptors");
            descriptor = out.get(0);
            check(vkCreateSampler(device, VkSamplerCreateInfo.calloc(s).sType$Default().magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST).addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE), null, out), "depth sampler");
            long sampler = out.get(0); cleanup.addFirst(() -> vkDestroySampler(device, sampler, null));
            var imageInfo = VkDescriptorImageInfo.calloc(1, s).sampler(sampler).imageView(sampleView)
                    .imageLayout(VulkanImageTest.layout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
            var bufferInfo = VkDescriptorBufferInfo.calloc(1, s).buffer(buffer).range(8);
            var writes = VkWriteDescriptorSet.calloc(2, s);
            writes.get(0).sType$Default().dstSet(descriptor).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(imageInfo);
            writes.get(1).sType$Default().dstSet(descriptor).dstBinding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(bufferInfo);
            vkUpdateDescriptorSets(device, writes, null);
            var push = VkPushConstantRange.calloc(1, s).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).size(4);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(setLayout)).pPushConstantRanges(push), null, out), "sample pipeline layout");
            layout = out.get(0); cleanup.addFirst(() -> vkDestroyPipelineLayout(device, layout, null));
            long compiler = shaderc_compiler_initialize();
            if (compiler == 0) throw new IllegalStateException("shaderc initialization failed");
            long result = 0;
            try {
                result = shaderc_compile_into_spv(compiler, """
                        #version 450
                        layout(local_size_x=1) in;
                        layout(set=0,binding=0) uniform sampler2D sourceDepth;
                        layout(std430,set=0,binding=1) buffer Output { float value[2]; } outputData;
                        layout(push_constant) uniform Params { uint index; } params;
                        void main() { outputData.value[params.index] = texelFetch(sourceDepth, ivec2(0), 0).r; }
                        """, shaderc_compute_shader, "depth-sample.comp", "main", 0);
                if (result == 0 || shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success)
                    throw new IllegalStateException("depth shader: " + (result == 0 ? "no result" : shaderc_result_get_error_message(result)));
                check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(s).sType$Default().pCode(shaderc_result_get_bytes(result)), null, out), "depth shader module");
            } finally { if (result != 0) shaderc_result_release(result); shaderc_compiler_release(compiler); }
            long shader = out.get(0); cleanup.addFirst(() -> vkDestroyShaderModule(device, shader, null));
            var stage = VkPipelineShaderStageCreateInfo.calloc(s).sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(shader).pName(s.UTF8("main"));
            var pipelineInfo = VkComputePipelineCreateInfo.calloc(1, s).sType$Default().stage(stage).layout(layout);
            check(vkCreateComputePipelines(device, VK_NULL_HANDLE, pipelineInfo, null, out), "sample compute pipeline");
            pipeline = out.get(0); cleanup.addFirst(() -> vkDestroyPipeline(device, pipeline, null));
        } catch (RuntimeException | Error failure) { close(); throw failure; }
    }

    void record(VkCommandBuffer cmd) throws Exception {
        try (MemoryStack s = stackPush()) {
            var clear = VkClearValue.calloc(1, s);
            var begin = VkRenderPassBeginInfo.calloc(s).sType$Default().renderPass(renderPass).framebuffer(framebuffer).pClearValues(clear);
            begin.renderArea().extent().set(16, 16);
            for (int i = 0; i < 2; i++) {
                clear.get(0).depthStencil().depth(i == 0 ? 0.25f : 0.75f);
                vkCmdBeginRenderPass(cmd, begin, VK_SUBPASS_CONTENTS_INLINE); vkCmdEndRenderPass(cmd);
                VulkanImageTest.invokeTransition(cmd, true, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, s.longs(descriptor), null);
                vkCmdPushConstants(cmd, layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, s.ints(i));
                vkCmdDispatch(cmd, 1, 1, 1);
                if (i == 0) {
                    //SyncVal's descriptor heuristic tracks the whole SSBO, not
                    //the two independent float indices. Explicitly order its
                    //writes without adding an image-memory dependency.
                    var outputReady = VkBufferMemoryBarrier.calloc(1, s).sType$Default().buffer(buffer).offset(0).size(8)
                            .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                            .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_WRITE_BIT);
                    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            0, null, outputReady, null);
                }
                VulkanImageTest.invokeTransition(cmd, true, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
            }
            var ready = VkMemoryBarrier.calloc(1, s).sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, ready, null, null);
        }
    }

    void verify() {
        try (MemoryStack s = stackPush()) {
            var pointer = s.mallocPointer(1);
            check(vkMapMemory(device, memory, 0, 8, 0, pointer), "map samples");
            try {
                float first = org.lwjgl.system.MemoryUtil.memGetFloat(pointer.get(0));
                float second = org.lwjgl.system.MemoryUtil.memGetFloat(pointer.get(0) + 4);
                VulkanImageTest.require(Math.abs(first - 0.25f) < 0.001f && Math.abs(second - 0.75f) < 0.001f,
                        "GPU compute readback expected 0.25/0.75, got " + first + "/" + second);
                System.out.println("NATIVE_DEPTH_SAMPLED: " + VulkanImageTest.format + " values=" + first + "," + second);
            } finally { vkUnmapMemory(device, memory); }
        }
    }

    @Override public void close() { while (!cleanup.isEmpty()) cleanup.removeFirst().run(); }
}
