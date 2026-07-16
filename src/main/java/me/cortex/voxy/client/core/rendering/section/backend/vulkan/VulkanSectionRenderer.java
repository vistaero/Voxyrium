package me.cortex.voxy.client.core.rendering.section.backend.vulkan;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.model.ModelStore;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.core.vk.ShadercCompiler;
import me.cortex.voxy.client.core.vk.VulkanBackend;
import me.cortex.voxy.client.core.vk.VulkanContext;
import me.cortex.voxy.common.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.util.List;

import static me.cortex.voxy.client.core.vk.VkUtil.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRDrawIndirectCount.vkCmdDrawIndexedIndirectCountKHR;
import static org.lwjgl.vulkan.VK12.vkCmdDrawIndexedIndirectCount;
import static org.lwjgl.vulkan.VK10.*;

/**
 * PHASE-1 HYBRID Vulkan backend.
 *
 * What runs where:
 *  - hierarchical traversal, prep/cull/prefix/cmdgen compute: GL (unchanged, bit-exact),
 *    writing into VK-allocated SharedBuffers owned by {@link VulkanViewport}.
 *  - opaque/translucent terrain draws: VK, vkCmdDrawIndexedIndirectCount over the
 *    shared draw-call/draw-count buffers, into shared color/depth images.
 *  - composite: GL samples the shared images back into MC's framebuffer
 *    (semaphore-ordered: GL signal -> VK wait, VK signal -> GL wait).
 *
 * KNOWN PHASE BOUNDARY (guarded, not hidden): the vertex-pulling SSBOs
 * (geometry buffer, metadata, model store) are still plain GlBuffers today.
 * Until BasicSectionGeometryManager/ModelStore allocate through SharedBuffer
 * when this backend is active, the VK draw stage cannot see the geometry and
 * this renderer refuses to draw (logs once, renders nothing) instead of
 * corrupting. That wiring is the next incremental step and is deliberately not
 * faked here.
 */
public class VulkanSectionRenderer extends AbstractSectionRenderer<VulkanViewport, BasicSectionGeometryData> {
    public static final Factory<VulkanViewport, BasicSectionGeometryData> FACTORY =
            AbstractSectionRenderer.Factory.create(VulkanSectionRenderer.class);

    private final VulkanContext ctx = VulkanBackend.context();
    private final AbstractRenderPipeline pipeline;

    private final long vertShaderModule;
    private final long fragShaderModule;
    private long renderPass;
    private long pipelineLayout;
    private long graphicsPipeline;
    private long framebuffer;
    private int fbWidth = -1, fbHeight = -1;
    private final VkCommandBuffer cmd;
    private final long fence;
    private boolean geometryShared = false; //flipped when geometry SSBO sharing lands
    private boolean warnedNoGeometry = false;

    public VulkanSectionRenderer(AbstractRenderPipeline pipeline, ModelStore modelStore, BasicSectionGeometryData geometryData) {
        super(pipeline.properties, modelStore, geometryData);
        this.pipeline = pipeline;

        //Single-source shaders: same GLSL assets as the GL path, VOXY_VULKAN defined.
        String vertex = ShaderLoader.parse("voxy:lod/gl46/quads3.vert");
        String frag = ShaderLoader.parse("voxy:lod/gl46/quads.frag");
        this.vertShaderModule = createModule(ShadercCompiler.compile(vertex, ShaderType.VERTEX, "quads3.vert"));
        this.fragShaderModule = createModule(ShadercCompiler.compile(frag, ShaderType.FRAGMENT, "quads.frag"));

        try (MemoryStack stack = stackPush()) {
            var cbai = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(this.ctx.commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            var pCmd = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(this.ctx.device, cbai, pCmd), "vkAllocateCommandBuffers");
            this.cmd = new VkCommandBuffer(pCmd.get(0), this.ctx.device);
            var fci = VkFenceCreateInfo.calloc(stack).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT);
            var pFence = stack.mallocLong(1);
            check(vkCreateFence(this.ctx.device, fci, null, pFence), "vkCreateFence");
            this.fence = pFence.get(0);
        }
        Logger.info("VulkanSectionRenderer initialized on " + this.ctx.deviceName + " (phase-1 hybrid)");
    }

    private long createModule(ByteBuffer spirv) {
        try (MemoryStack stack = stackPush()) {
            var smci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
            var pModule = stack.mallocLong(1);
            check(vkCreateShaderModule(this.ctx.device, smci, null, pModule), "vkCreateShaderModule");
            return pModule.get(0);
        }
    }

    private void ensurePipeline(VulkanViewport viewport) {
        if (!viewport.ensureTargets() && this.graphicsPipeline != 0
                && this.fbWidth == viewport.width && this.fbHeight == viewport.height) return;
        destroyPipelineObjects();
        try (MemoryStack stack = stackPush()) {
            //Render pass: color RGBA8 + depth D32, cleared each frame; VK depth range is 0..1 which
            //matches properties.isZero2One() on the GL side via the reverse-Z aware compare below.
            var attachments = VkAttachmentDescription.calloc(2, stack);
            attachments.get(0).format(viewport.color.vkFormat).samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            attachments.get(1).format(viewport.depth.vkFormat).samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            var colorRef = VkAttachmentReference.calloc(1, stack).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            var depthRef = VkAttachmentReference.calloc(stack).attachment(1).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            var subpass = VkSubpassDescription.calloc(1, stack)
                    .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(1).pColorAttachments(colorRef).pDepthStencilAttachment(depthRef);
            var rpci = VkRenderPassCreateInfo.calloc(stack).sType$Default()
                    .pAttachments(attachments).pSubpasses(subpass);
            var pRp = stack.mallocLong(1);
            check(vkCreateRenderPass(this.ctx.device, rpci, null, pRp), "vkCreateRenderPass");
            this.renderPass = pRp.get(0);

            var fbci = VkFramebufferCreateInfo.calloc(stack).sType$Default()
                    .renderPass(this.renderPass)
                    .pAttachments(stack.longs(viewport.color.vkView, viewport.depth.vkView))
                    .width(viewport.width).height(viewport.height).layers(1);
            var pFb = stack.mallocLong(1);
            check(vkCreateFramebuffer(this.ctx.device, fbci, null, pFb), "vkCreateFramebuffer");
            this.framebuffer = pFb.get(0);

            //Pipeline layout: descriptor sets for the vertex-pulling SSBOs are wired in the
            //geometry-sharing step; until then an empty layout is sufficient for pipeline creation.
            var plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default();
            var pPl = stack.mallocLong(1);
            check(vkCreatePipelineLayout(this.ctx.device, plci, null, pPl), "vkCreatePipelineLayout");
            this.pipelineLayout = pPl.get(0);

            var stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(this.vertShaderModule).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(this.fragShaderModule).pName(stack.UTF8("main"));

            var vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();//vertex pulling: no attributes
            var inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
                    .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
            var viewportState = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default()
                    .viewportCount(1).pViewports(VkViewport.calloc(1, stack)
                            .x(0).y(0).width(viewport.width).height(viewport.height).minDepth(0).maxDepth(1))
                    .scissorCount(1).pScissors(VkRect2D.calloc(1, stack)
                            .extent(e -> e.width(viewport.width).height(viewport.height)));
            var raster = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                    .polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE)//MDIC disables cull face
                    .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1);
            var msaa = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
            var depthState = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
                    .depthTestEnable(true).depthWriteEnable(true)
                    .depthCompareOp(this.properties.isReverseZ() ? VK_COMPARE_OP_GREATER_OR_EQUAL : VK_COMPARE_OP_LESS_OR_EQUAL);
            var blendAttach = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                    .colorWriteMask(0xF).blendEnable(false);
            var blend = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(blendAttach);

            var gpci = VkGraphicsPipelineCreateInfo.calloc(1, stack).sType$Default()
                    .pStages(stages)
                    .pVertexInputState(vertexInput)
                    .pInputAssemblyState(inputAssembly)
                    .pViewportState(viewportState)
                    .pRasterizationState(raster)
                    .pMultisampleState(msaa)
                    .pDepthStencilState(depthState)
                    .pColorBlendState(blend)
                    .layout(this.pipelineLayout)
                    .renderPass(this.renderPass).subpass(0);
            var pPipe = stack.mallocLong(1);
            check(vkCreateGraphicsPipelines(this.ctx.device, VK_NULL_HANDLE, gpci, null, pPipe), "vkCreateGraphicsPipelines");
            this.graphicsPipeline = pPipe.get(0);
            this.fbWidth = viewport.width; this.fbHeight = viewport.height;
        }
    }

    @Override
    public void buildDrawCalls(VulkanViewport viewport) {
        //Phase-1: command generation stays on the GL path, writing SharedBuffers.
        //Deliberately empty here; the GL compute passes are dispatched by the shared
        //pipeline code exactly as for MDIC once the cmdgen wiring is routed through
        //the viewport's shared buffers (next step alongside geometry sharing).
    }

    @Override
    public void renderOpaque(VulkanViewport viewport) {
        if (this.geometryManager.getSectionCount() == 0) return;
        if (!this.geometryShared) {
            if (!this.warnedNoGeometry) {
                this.warnedNoGeometry = true;
                Logger.warn("Voxy VK backend active but geometry SSBO sharing not yet wired -> drawing nothing (GL fallback recommended)");
            }
            return;
        }
        this.ensurePipeline(viewport);
        try (MemoryStack stack = stackPush()) {
            check(vkWaitForFences(this.ctx.device, this.fence, true, Long.MAX_VALUE), "vkWaitForFences");
            check(vkResetFences(this.ctx.device, this.fence), "vkResetFences");
            var begin = VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(vkBeginCommandBuffer(this.cmd, begin), "vkBeginCommandBuffer");

            var clears = VkClearValue.calloc(2, stack);
            clears.get(0).color().float32(0, 0).float32(1, 0).float32(2, 0).float32(3, 0);
            clears.get(1).depthStencil().depth(this.properties.isReverseZ() ? 0f : 1f);
            var rpbi = VkRenderPassBeginInfo.calloc(stack).sType$Default()
                    .renderPass(this.renderPass).framebuffer(this.framebuffer)
                    .renderArea(a -> a.extent(e -> e.width(viewport.width).height(viewport.height)))
                    .pClearValues(clears);
            vkCmdBeginRenderPass(this.cmd, rpbi, VK_SUBPASS_CONTENTS_INLINE);
            vkCmdBindPipeline(this.cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, this.graphicsPipeline);
            //Mirror of MDIC renderOpaque: offset 0, count at byte 12, same max-draw clamp, stride 20.
            int maxDraw = Math.min((int) (this.geometryManager.getSectionCount() * 4.4 + 128),
                    me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer.OPAQUE_DRAW_COUNT);
            if (this.ctx.hasDrawIndirectCount) {
                vkCmdDrawIndexedIndirectCount(this.cmd,
                        viewport.drawCallBuffer.vkBuffer, 0,
                        viewport.drawCountCallBuffer.vkBuffer, 4 * 3,
                        maxDraw, 5 * 4);
            } else {
                //MoltenVK/macOS fallback: no GPU-sourced draw count. The cmdgen compute
                //zero-fills unused command slots (instanceCount=0 draws are no-ops), so a
                //fixed-count multi-draw over the clamped max is correct, just less tight.
                vkCmdDrawIndexedIndirect(this.cmd, viewport.drawCallBuffer.vkBuffer, 0, maxDraw, 5 * 4);
            }
            vkCmdEndRenderPass(this.cmd);
            check(vkEndCommandBuffer(this.cmd), "vkEndCommandBuffer");

            //GL has signalled glDone after cmdgen; wait it, signal vkDone for the GL composite.
            var submit = VkSubmitInfo.calloc(stack).sType$Default()
                    .waitSemaphoreCount(1)
                    .pWaitSemaphores(stack.longs(viewport.glDone.vkSemaphore))
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT))
                    .pCommandBuffers(stack.pointers(this.cmd))
                    .pSignalSemaphores(stack.longs(viewport.vkDone.vkSemaphore));
            check(vkQueueSubmit(this.ctx.queue, submit, this.fence), "vkQueueSubmit");
        }
    }

    @Override
    public void renderTranslucent(VulkanViewport viewport) {
        //Phase-1: translucent pass deferred until opaque parity is verified on hardware.
    }

    @Override
    public void renderTemporal(VulkanViewport viewport) {
        //Phase-1: temporal pass deferred (GL path keeps it; VK renders without TAA reuse).
    }

    @Override
    public VulkanViewport createViewport() {
        return new VulkanViewport(this.properties, this.geometryManager.getSectionCount() == 0
                ? (int) Math.min(this.geometryManager.getMaxCapacity(), Integer.MAX_VALUE)
                : this.geometryManager.getSectionCount());
    }

    @Override
    public void addDebug(List<String> lines) {
        lines.add("VK backend (phase-1 hybrid): " + VulkanBackend.statusLine()
                + (this.geometryShared ? "" : " [geometry sharing pending -> not drawing]"));
    }

    private void destroyPipelineObjects() {
        if (this.graphicsPipeline != 0) vkDestroyPipeline(this.ctx.device, this.graphicsPipeline, null);
        if (this.pipelineLayout != 0) vkDestroyPipelineLayout(this.ctx.device, this.pipelineLayout, null);
        if (this.framebuffer != 0) vkDestroyFramebuffer(this.ctx.device, this.framebuffer, null);
        if (this.renderPass != 0) vkDestroyRenderPass(this.ctx.device, this.renderPass, null);
        this.graphicsPipeline = 0; this.pipelineLayout = 0; this.framebuffer = 0; this.renderPass = 0;
    }

    @Override
    public void free() {
        vkDeviceWaitIdle(this.ctx.device);
        destroyPipelineObjects();
        vkDestroyFence(this.ctx.device, this.fence, null);
        vkDestroyShaderModule(this.ctx.device, this.vertShaderModule, null);
        vkDestroyShaderModule(this.ctx.device, this.fragShaderModule, null);
    }
}
