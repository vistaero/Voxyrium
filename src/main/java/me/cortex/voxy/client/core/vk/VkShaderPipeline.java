package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.client.core.gl.shader.ShaderType;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static me.cortex.voxy.client.core.vk.VkUtil.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR;
import static org.lwjgl.vulkan.KHRPushDescriptor.vkCmdPushDescriptorSetKHR;
import static org.lwjgl.vulkan.VK10.*;

//Unified pipeline wrapper for the pure-VK path: an explicit binding table
// (set 0, push descriptors — required by MC 26.2's own Vulkan backend, so
// always present on an adopted device), an optional push-constant range, and
// either a compute stage or a vert+frag pair with dynamic rendering.
//
//Binding numbers mirror the (possibly remapped) layout(binding=N) declarations
// of the VK shader variants; see assets/voxy/shaders/lod/vk/.
public final class VkShaderPipeline {
    public static final int T_UBO = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    public static final int T_SSBO = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    public static final int T_SAMPLER = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    public static final int T_IMAGE = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;

    //Interned descriptorSetLayout cache keyed by (device, binding-hash) so
    // pipelines sharing a binding table reuse one layout handle (and skip
    // re-creating + later destroying it). Lives for the device lifetime.
    private static final java.util.Map<Long, java.util.Map<Long, Long>> LAYOUT_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private final VkFrameCtx ctx;
    public final long descriptorSetLayout;
    public final long pipelineLayout;
    public final long pipeline;
    private final long[] modules;
    private final boolean compute;
    private final int pushStages;

    public record Binding(int binding, int type) {}

    public static Binding ubo(int b) { return new Binding(b, T_UBO); }
    public static Binding ssbo(int b) { return new Binding(b, T_SSBO); }
    public static Binding sampler(int b) { return new Binding(b, T_SAMPLER); }
    public static Binding image(int b) { return new Binding(b, T_IMAGE); }

    //=============================== Compute ===============================

    public VkShaderPipeline(VkFrameCtx ctx, String name, String computeGlsl, int pushConstantBytes, List<Binding> bindings) {
        this.ctx = ctx;
        this.compute = true;
        this.pushStages = VK_SHADER_STAGE_COMPUTE_BIT;
        var vctx = ctx.vk();
        try (MemoryStack stack = stackPush()) {
            long module = createModule(vctx, ShadercCompiler.compile(computeGlsl, ShaderType.COMPUTE, name), stack);
            this.modules = new long[]{module};
            this.descriptorSetLayout = createSetLayout(vctx, stack, bindings, VK_SHADER_STAGE_COMPUTE_BIT);
            this.pipelineLayout = createPipelineLayout(vctx, stack, this.descriptorSetLayout, pushConstantBytes, VK_SHADER_STAGE_COMPUTE_BIT);

            var cpci = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().layout(this.pipelineLayout);
            cpci.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            var pPipe = stack.mallocLong(1);
            check(vkCreateComputePipelines(vctx.device, VK_NULL_HANDLE, cpci, null, pPipe), "vkCreateComputePipelines(" + name + ")");
            this.pipeline = pPipe.get(0);
        }
    }

    //=============================== Graphics ===============================

    public static final class GfxDesc {
        public String name;
        public String vertGlsl, fragGlsl;
        public int pushConstantBytes;
        public List<Binding> bindings = new ArrayList<>();
        public int colorFormat;           //VK_FORMAT_UNDEFINED for depth-only
        public int depthFormat;           //VK_FORMAT_UNDEFINED for no depth attachment
        public int stencilFormat;         //VK_FORMAT_UNDEFINED unless depth-stencil has stencil
        public boolean depthTest = true, depthWrite = true;
        public int depthCompare = VK_COMPARE_OP_LESS_OR_EQUAL;
        public boolean blend = false;      //standard alpha blend when true
        public boolean colorWrite = true;
        public boolean stencilTestEqual1 = false;//stencil func EQUAL ref=1, keep (LOD terrain masking)
        public boolean stencilWriteAlways1 = false;//stencil ALWAYS -> write stencilWriteRef (depth setup pass)
        public int stencilWriteRef = 1;
        public int topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    }

    private static boolean isStripTopology(int topology) {
        return topology == VK_PRIMITIVE_TOPOLOGY_LINE_STRIP
                || topology == VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP
                || topology == VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN
                || topology == VK_PRIMITIVE_TOPOLOGY_LINE_STRIP_WITH_ADJACENCY
                || topology == VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP_WITH_ADJACENCY;
    }

    public VkShaderPipeline(VkFrameCtx ctx, GfxDesc d) {
        this.ctx = ctx;
        this.compute = false;
        this.pushStages = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
        var vctx = ctx.vk();
        try (MemoryStack stack = stackPush()) {
            long vertModule = createModule(vctx, ShadercCompiler.compile(d.vertGlsl, ShaderType.VERTEX, d.name + ".vert"), stack);
            long fragModule = createModule(vctx, ShadercCompiler.compile(d.fragGlsl, ShaderType.FRAGMENT, d.name + ".frag"), stack);
            this.modules = new long[]{vertModule, fragModule};
            this.descriptorSetLayout = createSetLayout(vctx, stack, d.bindings, this.pushStages);
            this.pipelineLayout = createPipelineLayout(vctx, stack, this.descriptorSetLayout, d.pushConstantBytes, this.pushStages);

            var stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertModule).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragModule).pName(stack.UTF8("main"));

            var vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();//vertex pulling
            //MoltenVK/Metal cannot disable primitive restart for strip/fan topologies (VK_ERROR_FEATURE_NOT_PRESENT),
            //so enable it for those. It must stay VK_FALSE for list topologies (spec VUID-...-topology-06252 without
            //primitiveTopologyListRestart). Restart has no effect on our non-indexed strip draws, so this is safe.
            var inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
                    .topology(d.topology)
                    .primitiveRestartEnable(isStripTopology(d.topology));
            //Dynamic viewport+scissor: one pipeline survives resizes
            var dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                    .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));
            var viewportState = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default()
                    .viewportCount(1).scissorCount(1);
            var raster = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                    .polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE)//GL path disables cull face
                    .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1);
            var msaa = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

            var depthState = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
                    .depthTestEnable(d.depthTest).depthWriteEnable(d.depthWrite).depthCompareOp(d.depthCompare);
            if (d.stencilTestEqual1 || d.stencilWriteAlways1) {
                depthState.stencilTestEnable(true);
                var op = depthState.front();
                if (d.stencilWriteAlways1) {
                    op.failOp(VK_STENCIL_OP_KEEP).passOp(VK_STENCIL_OP_REPLACE).depthFailOp(VK_STENCIL_OP_KEEP)
                            .compareOp(VK_COMPARE_OP_ALWAYS).compareMask(0xFF).writeMask(0xFF).reference(d.stencilWriteRef);
                } else {
                    op.failOp(VK_STENCIL_OP_KEEP).passOp(VK_STENCIL_OP_KEEP).depthFailOp(VK_STENCIL_OP_KEEP)
                            .compareOp(VK_COMPARE_OP_EQUAL).compareMask(0xFF).writeMask(0x00).reference(1);
                }
                depthState.back(depthState.front());
            }

            var blendAttach = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                    .colorWriteMask(d.colorWrite ? 0xF : 0)
                    .blendEnable(d.blend);
            if (d.blend) {
                blendAttach.srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
                        .dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                        .colorBlendOp(VK_BLEND_OP_ADD)
                        .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                        .dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                        .alphaBlendOp(VK_BLEND_OP_ADD);
            }
            var blend = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default();
            if (d.colorFormat != VK_FORMAT_UNDEFINED) {
                blend.pAttachments(blendAttach);
            }

            var rendering = VkPipelineRenderingCreateInfoKHR.calloc(stack).sType$Default()
                    .depthAttachmentFormat(d.depthFormat)
                    .stencilAttachmentFormat(d.stencilFormat);
            if (d.colorFormat != VK_FORMAT_UNDEFINED) {
                rendering.colorAttachmentCount(1).pColorAttachmentFormats(stack.ints(d.colorFormat));
            }

            var gpci = VkGraphicsPipelineCreateInfo.calloc(1, stack).sType$Default()
                    .pNext(rendering)
                    .pStages(stages)
                    .pVertexInputState(vertexInput)
                    .pInputAssemblyState(inputAssembly)
                    .pViewportState(viewportState)
                    .pRasterizationState(raster)
                    .pMultisampleState(msaa)
                    .pDepthStencilState(depthState)
                    .pColorBlendState(blend)
                    .pDynamicState(dynamicState)
                    .layout(this.pipelineLayout);
            var pPipe = stack.mallocLong(1);
            check(vkCreateGraphicsPipelines(vctx.device, VK_NULL_HANDLE, gpci, null, pPipe), "vkCreateGraphicsPipelines(" + d.name + ")");
            this.pipeline = pPipe.get(0);
        }
    }

    //=============================== Shared ===============================

    private static long createModule(VulkanContext vctx, ByteBuffer spv, MemoryStack stack) {
        var smci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spv);
        var pMod = stack.mallocLong(1);
        check(vkCreateShaderModule(vctx.device, smci, null, pMod), "vkCreateShaderModule");
        return pMod.get(0);
    }

    private static long createSetLayout(VulkanContext vctx, MemoryStack stack, List<Binding> bindings, int stages) {
        //Intern by (bindings hash + stages): pipelines with identical binding
        // tables (e.g. terrainOpaque and terrainTranslucent share the same
        // bindings list) reuse one VkDescriptorSetLayout handle instead of each
        // building + destroying its own.
        long key = stages;
        for (var b : bindings) {
            key = key * 31L + b.binding() * 7L + b.type();
        }
        long deviceAddr = vctx.device.address();
        var perDevice = LAYOUT_CACHE.computeIfAbsent(deviceAddr, k -> new java.util.concurrent.ConcurrentHashMap<>());
        Long cached = perDevice.get(key);
        if (cached != null) return cached;

        var lb = VkDescriptorSetLayoutBinding.calloc(bindings.size(), stack);
        for (int i = 0; i < bindings.size(); i++) {
            var b = bindings.get(i);
            lb.get(i).binding(b.binding()).descriptorType(b.type()).descriptorCount(1).stageFlags(stages);
        }
        var dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                .flags(VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR)
                .pBindings(lb);
        var pDsl = stack.mallocLong(1);
        check(vkCreateDescriptorSetLayout(vctx.device, dslci, null, pDsl), "vkCreateDescriptorSetLayout");
        long handle = pDsl.get(0);
        perDevice.put(key, handle);
        return handle;
    }

    private static long createPipelineLayout(VulkanContext vctx, MemoryStack stack, long setLayout, int pushBytes, int pushStages) {
        var plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayout));
        if (pushBytes > 0) {
            var range = VkPushConstantRange.calloc(1, stack).stageFlags(pushStages).offset(0).size(pushBytes);
            plci.pPushConstantRanges(range);
        }
        var pPl = stack.mallocLong(1);
        check(vkCreatePipelineLayout(vctx.device, plci, null, pPl), "vkCreatePipelineLayout");
        return pPl.get(0);
    }

    public void bind(VkCommandBuffer cmd) {
        vkCmdBindPipeline(cmd, this.compute ? VK_PIPELINE_BIND_POINT_COMPUTE : VK_PIPELINE_BIND_POINT_GRAPHICS, this.pipeline);
    }

    public void pushConstants(VkCommandBuffer cmd, ByteBuffer data) {
        vkCmdPushConstants(cmd, this.pipelineLayout, this.pushStages, 0, data);
    }

    /** Accumulates descriptor writes then pushes them; render-thread scratch use only. */
    public Binder binder() {
        return new Binder(this);
    }

    public static final class Binder implements AutoCloseable {
        private final VkShaderPipeline owner;
        private final MemoryStack stack = stackPush();
        private final List<VkWriteDescriptorSet> writes = new ArrayList<>();

        private Binder(VkShaderPipeline owner) {
            this.owner = owner;
        }

        public Binder buffer(int binding, int type, long buffer, long offset, long range) {
            var info = VkDescriptorBufferInfo.calloc(1, this.stack).buffer(buffer).offset(offset).range(range);
            var write = VkWriteDescriptorSet.calloc(this.stack).sType$Default()
                    .dstBinding(binding).descriptorType(type).descriptorCount(1).pBufferInfo(info);
            this.writes.add(write);
            return this;
        }

        public Binder ubo(int binding, VkBuffer buffer) { return this.buffer(binding, T_UBO, buffer.buffer, 0, buffer.size()); }
        public Binder ssbo(int binding, VkBuffer buffer) { return this.buffer(binding, T_SSBO, buffer.buffer, 0, buffer.size()); }
        public Binder ssbo(int binding, long rawBuffer, long offset, long range) { return this.buffer(binding, T_SSBO, rawBuffer, offset, range); }

        public Binder sampler(int binding, long imageView, long sampler, int layout) {
            var info = VkDescriptorImageInfo.calloc(1, this.stack).sampler(sampler).imageView(imageView).imageLayout(layout);
            var write = VkWriteDescriptorSet.calloc(this.stack).sType$Default()
                    .dstBinding(binding).descriptorType(T_SAMPLER).descriptorCount(1).pImageInfo(info);
            this.writes.add(write);
            return this;
        }

        public Binder image(int binding, long imageView) {
            var info = VkDescriptorImageInfo.calloc(1, this.stack).imageView(imageView).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            var write = VkWriteDescriptorSet.calloc(this.stack).sType$Default()
                    .dstBinding(binding).descriptorType(T_IMAGE).descriptorCount(1).pImageInfo(info);
            this.writes.add(write);
            return this;
        }

        public void push(VkCommandBuffer cmd) {
            var buf = VkWriteDescriptorSet.calloc(this.writes.size(), this.stack);
            for (int i = 0; i < this.writes.size(); i++) {
                buf.put(i, this.writes.get(i));
            }
            vkCmdPushDescriptorSetKHR(cmd,
                    this.owner.compute ? VK_PIPELINE_BIND_POINT_COMPUTE : VK_PIPELINE_BIND_POINT_GRAPHICS,
                    this.owner.pipelineLayout, 0, buf);
        }

        @Override
        public void close() {
            this.stack.close();
        }
    }

    public void free() {
        var device = this.ctx.vk().device;
        vkDestroyPipeline(device, this.pipeline, null);
        vkDestroyPipelineLayout(device, this.pipelineLayout, null);
        //descriptorSetLayout is interned (shared across pipelines with identical
        // bindings); never freed per-pipeline. Leaks are bounded by the device
        // lifetime and the binding-table cardinality (a handful of distinct layouts).
        for (long module : this.modules) {
            vkDestroyShaderModule(device, module, null);
        }
    }
}
