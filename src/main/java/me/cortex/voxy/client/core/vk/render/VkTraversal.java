package me.cortex.voxy.client.core.vk.render;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.vk.VkBuffer;
import me.cortex.voxy.client.core.vk.VkDownloadStream;
import me.cortex.voxy.client.core.vk.VkFrameCtx;
import me.cortex.voxy.client.core.vk.VkShaderPipeline;
import me.cortex.voxy.client.core.vk.VkShaderSource;
import me.cortex.voxy.client.core.vk.VkUploadStream;
import me.cortex.voxy.common.CmpLog;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

//Pure-VK port of HierarchicalOcclusionTraverser: the same iterative BFS over
// the LOD node tree (flip-flop GPU queues, one indirect dispatch per LOD layer),
// HiZ-tested, emitting the render list + node requests. CPU-side TLN bookkeeping
// and request readback are identical to the GL implementation.
public class VkTraversal {
    public static final int MAX_REQUEST_QUEUE_SIZE = HierarchicalOcclusionTraverser.MAX_REQUEST_QUEUE_SIZE;
    public static final int MAX_QUEUE_SIZE = HierarchicalOcclusionTraverser.MAX_QUEUE_SIZE;
    private static final int MAX_ITERATIONS = WorldEngine.MAX_LOD_LAYER + 1;
    //Traversal workgroup size: 64 threads (2 subgroups on 32-wide devices, 1 on
    // 64-wide AMD) improves HiZ texture cache locality and amortises dispatch
    // overhead over the 32-thread default. Falls back to 32 (one subgroup) when
    // subgroup support is unavailable. The shader's LOCAL_SIZE is driven by this
    // define, and queue.glsl's push math is LOCAL_SIZE-parametric, so no shader
    // change is needed.
    private final int localWorkSizeBits;

    private static int resolveLocalWorkSizeBits(VkFrameCtx ctx) {
        //subgroupSize >= 32 on every conformant Vulkan 1.1+ device; bump to 64
        // (2 subgroups) when subgroupSize >= 32. Conservative 32 fallback otherwise.
        return ctx.vk().subgroupArithmetic && ctx.vk().subgroupSize >= 32 ? 6 : 5;
    }

    //Bindings (single Vulkan namespace: HiZ sampler at 0, UBO at 1, SSBOs from 2)
    private static final int HIZ_BINDING = 0;
    private static final int SCENE_UNIFORM_BINDING = 1;
    private static final int REQUEST_QUEUE_BINDING = 2;
    private static final int RENDER_QUEUE_BINDING = 3;
    private static final int NODE_DATA_BINDING = 4;
    private static final int NODE_QUEUE_META_BINDING = 5;
    private static final int NODE_QUEUE_SOURCE_BINDING = 6;
    private static final int NODE_QUEUE_SINK_BINDING = 7;
    private static final int RENDER_TRACKER_BINDING = 8;

    private final VkFrameCtx ctx;
    private final VkUploadStream uploadStream;
    private final VkDownloadStream downloadStream;
    private final AsyncNodeManager nodeManager;
    private final VkNodeCleaner nodeCleaner;
    private final RenderGenerationService meshGen;

    private final VkBuffer requestBuffer;
    private final VkBuffer nodeBuffer;
    private final VkBuffer uniformBuffer;
    private final VkBuffer topNodeIds;
    private final VkBuffer queueMetaBuffer;
    private final VkBuffer scratchQueueA;
    private final VkBuffer scratchQueueB;

    private final VkShaderPipeline traversal;

    private int topNodeCount;
    private final Int2IntOpenHashMap topNode2idxMapping = new Int2IntOpenHashMap();
    private final int[] idx2topNodeMapping = new int[MAX_QUEUE_SIZE];

    public VkTraversal(VkFrameCtx ctx, VkUploadStream up, VkDownloadStream down, RenderProperties properties,
                       AsyncNodeManager nodeManager, VkNodeCleaner nodeCleaner, RenderGenerationService meshGen) {
        this.ctx = ctx;
        this.uploadStream = up;
        this.downloadStream = down;
        this.nodeManager = nodeManager;
        this.nodeCleaner = nodeCleaner;
        this.meshGen = meshGen;
        this.localWorkSizeBits = resolveLocalWorkSizeBits(ctx);

        this.requestBuffer = new VkBuffer(ctx, MAX_REQUEST_QUEUE_SIZE * 8L + 8).zero();
        this.nodeBuffer = new VkBuffer(ctx, nodeManager.maxNodeCount * 16L).fill(-1);
        this.uniformBuffer = new VkBuffer(ctx, 1024).zero();
        this.topNodeIds = new VkBuffer(ctx, MAX_QUEUE_SIZE * 4L).zero();
        this.queueMetaBuffer = new VkBuffer(ctx, 4L * 4 * MAX_ITERATIONS).zero();
        this.scratchQueueA = new VkBuffer(ctx, MAX_QUEUE_SIZE * 4L).zero();
        this.scratchQueueB = new VkBuffer(ctx, MAX_QUEUE_SIZE * 4L).zero();
        ctx.flushImmediate();

        this.topNode2idxMapping.defaultReturnValue(-1);
        this.nodeManager.setTLNAddRemoveCallbacks(this::addTLN, this::remTLN);

        this.traversal = new VkShaderPipeline(ctx, "traversal_dev.comp",
                VkShaderSource.load("voxy:lod/hierarchical/traversal_dev.comp", VkShaderSource.defs()
                        .props(properties)
                        .def("MAX_ITERATIONS", MAX_ITERATIONS)
                        .def("LOCAL_SIZE_BITS", this.localWorkSizeBits)
                        .def("MAX_REQUEST_QUEUE_SIZE", MAX_REQUEST_QUEUE_SIZE)
                        .def("HIZ_BINDING", HIZ_BINDING)
                        .def("SCENE_UNIFORM_BINDING", SCENE_UNIFORM_BINDING)
                        .def("REQUEST_QUEUE_BINDING", REQUEST_QUEUE_BINDING)
                        .def("RENDER_QUEUE_BINDING", RENDER_QUEUE_BINDING)
                        .def("NODE_DATA_BINDING", NODE_DATA_BINDING)
                        .def("NODE_QUEUE_INDEX_BINDING", 0)//unused on VK (push constant)
                        .def("NODE_QUEUE_META_BINDING", NODE_QUEUE_META_BINDING)
                        .def("NODE_QUEUE_SOURCE_BINDING", NODE_QUEUE_SOURCE_BINDING)
                        .def("NODE_QUEUE_SINK_BINDING", NODE_QUEUE_SINK_BINDING)
                        .def("RENDER_TRACKER_BINDING", RENDER_TRACKER_BINDING)
                        .build()),
                4,
                List.of(VkShaderPipeline.sampler(HIZ_BINDING), VkShaderPipeline.ubo(SCENE_UNIFORM_BINDING),
                        VkShaderPipeline.ssbo(REQUEST_QUEUE_BINDING), VkShaderPipeline.ssbo(RENDER_QUEUE_BINDING),
                        VkShaderPipeline.ssbo(NODE_DATA_BINDING), VkShaderPipeline.ssbo(NODE_QUEUE_META_BINDING),
                        VkShaderPipeline.ssbo(NODE_QUEUE_SOURCE_BINDING), VkShaderPipeline.ssbo(NODE_QUEUE_SINK_BINDING),
                        VkShaderPipeline.ssbo(RENDER_TRACKER_BINDING)));
    }

    private void addTLN(int id) {
        int aid = this.topNodeCount++;
        if (this.topNodeCount > this.topNodeIds.size() / 4) {
            throw new IllegalStateException("Top level node count greater than capacity");
        }
        vkCmdFillBuffer(this.ctx.cmd(), this.topNodeIds.buffer, aid * 4L, 4, id);
        if (this.topNode2idxMapping.put(id, aid) != -1) {
            throw new IllegalStateException();
        }
        this.idx2topNodeMapping[aid] = id;
    }

    private void remTLN(int id) {
        int idx = this.topNode2idxMapping.remove(id);
        this.topNodeCount--;
        if (idx == -1) throw new IllegalStateException();
        if (idx == this.topNodeCount) return;
        int endTLNId = this.idx2topNodeMapping[this.topNodeCount];
        this.idx2topNodeMapping[idx] = endTLNId;
        if (this.topNode2idxMapping.put(endTLNId, idx) == -1) throw new IllegalStateException();
        vkCmdFillBuffer(this.ctx.cmd(), this.topNodeIds.buffer, idx * 4L, 4, endTLNId);
    }

    private void uploadUniform(Viewport<?> viewport, VkViewport vkViewport) {
        long ptr = this.uploadStream.upload(this.uniformBuffer, 0, 1024);
        viewport.MVP.getToAddress(ptr); ptr += 4 * 4 * 4;
        viewport.section.getToAddress(ptr); ptr += 4 * 3;
        MemoryUtil.memPutInt(ptr, vkViewport.hiZ.getPackedLevels()); ptr += 4;
        viewport.innerTranslation.getToAddress(ptr); ptr += 4 * 3;

        final float screenspaceAreaDecreasingSize = VoxyConfig.CONFIG.subDivisionSize * VoxyConfig.CONFIG.subDivisionSize;
        MemoryUtil.memPutFloat(ptr, screenspaceAreaDecreasingSize / (viewport.width * viewport.height)); ptr += 4;

        for (int i = 0; i < 6; i++) {
            viewport.frustumPlanes[i].getToAddress(ptr); ptr += 4 * 4;
        }

        MemoryUtil.memPutInt(ptr, (int) (vkViewport.indirectLookupBuffer.size() / 4 - 1)); ptr += 4;
        MemoryUtil.memPutInt(ptr, this.nodeCleaner.visibilityId); ptr += 4;
        {
            final double TARGET_COUNT = 4000;
            double iFillness = Math.max(0, (TARGET_COUNT - this.meshGen.getTaskCount()) / TARGET_COUNT);
            iFillness = Math.pow(iFillness, 2);
            final int requestSize = (int) Math.ceil(iFillness * MAX_REQUEST_QUEUE_SIZE);
            MemoryUtil.memPutInt(ptr, Math.max(0, Math.min(MAX_REQUEST_QUEUE_SIZE, requestSize))); ptr += 4;
        }
        MemoryUtil.memPutFloat(ptr, (float) Math.pow(VoxyConfig.CONFIG.sectionRenderDistance * 16 * 32, 2));
    }

    public void doTraversal(VkViewport viewport) {
        this.uploadUniform(viewport, viewport);
        var cmd = this.ctx.cmd();

        //Clear the render output counter
        vkCmdFillBuffer(cmd, viewport.indirectLookupBuffer.buffer, 0, 4, 0);

        //Prime the per-iteration queue metadata
        int firstDispatchSize = (this.topNodeCount + (1 << this.localWorkSizeBits) - 1) >> this.localWorkSizeBits;
        {
            long ptr = this.uploadStream.upload(this.queueMetaBuffer, 0, 16L * MAX_ITERATIONS);
            MemoryUtil.memPutInt(ptr, firstDispatchSize);
            MemoryUtil.memPutInt(ptr + 4, 1);
            MemoryUtil.memPutInt(ptr + 8, 1);
            MemoryUtil.memPutInt(ptr + 12, this.topNodeCount);
            for (int i = 1; i < MAX_ITERATIONS; i++) {
                MemoryUtil.memPutInt(ptr + (i * 16L), 0);
                MemoryUtil.memPutInt(ptr + (i * 16L) + 4, 1);
                MemoryUtil.memPutInt(ptr + (i * 16L) + 8, 1);
                MemoryUtil.memPutInt(ptr + (i * 16L) + 12, 0);
            }
            this.uploadStream.commit();
        }
        //The upload commit just issued a scoped TRANSFER -> COMPUTE/VERTEX/etc
        // barrier; this one orders the queueMetaBuffer upload + the
        // indirectLookupBuffer vkCmdFillBuffer (TRANSFER writes) ahead of the
        // traversal compute reads. Scoped to TRANSFER -> COMPUTE.
        this.ctx.barrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_INDIRECT_COMMAND_READ_BIT);

        this.traversal.bind(cmd);
        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            var source = iter == 0 ? this.topNodeIds : ((iter & 1) == 0 ? this.scratchQueueA : this.scratchQueueB);
            var sink = (iter & 1) == 0 ? this.scratchQueueB : this.scratchQueueA;
            try (var b = this.traversal.binder()) {
                b.sampler(HIZ_BINDING, viewport.hiZ.pyramidView(), viewport.hiZ.sampler, VK_IMAGE_LAYOUT_GENERAL)
                        .ubo(SCENE_UNIFORM_BINDING, this.uniformBuffer)
                        .ssbo(REQUEST_QUEUE_BINDING, this.requestBuffer)
                        .ssbo(RENDER_QUEUE_BINDING, viewport.indirectLookupBuffer)
                        .ssbo(NODE_DATA_BINDING, this.nodeBuffer)
                        .ssbo(NODE_QUEUE_META_BINDING, this.queueMetaBuffer)
                        .ssbo(NODE_QUEUE_SOURCE_BINDING, source)
                        .ssbo(NODE_QUEUE_SINK_BINDING, sink)
                        .ssbo(RENDER_TRACKER_BINDING, this.nodeCleaner.visibilityBuffer)
                        .push(cmd);
            }
            try (MemoryStack stack = stackPush()) {
                this.traversal.pushConstants(cmd, stack.malloc(4).putInt(0, iter));
            }
            if (iter == 0) {
                if (firstDispatchSize != 0) {
                    vkCmdDispatch(cmd, firstDispatchSize, 1, 1);
                }
            } else {
                vkCmdDispatchIndirect(cmd, this.queueMetaBuffer.buffer, iter * 16L);
            }
            this.ctx.barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,
                    VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_INDIRECT_COMMAND_READ_BIT);
        }
        //Traversal compute -> download copy (transfer) + requestBuffer fill.
        this.ctx.computeToTransferBarrier();

        //Download + reset the request queue
        this.downloadStream.download(this.requestBuffer, this::forwardDownloadResult);
        vkCmdFillBuffer(this.ctx.cmd(), this.requestBuffer.buffer, 0, 4, 0);
        //The download copy read the requestBuffer (TRANSFER read); the fill
        // resets it (TRANSFER write). The next reader is next frame's traversal
        // compute, so this barrier only needs to order TRANSFER -> TRANSFER +
        // COMPUTE. Removed the previous fullBarrier (ALL_COMMANDS -> ALL_COMMANDS)
        // which was a full pipeline stall.
        this.ctx.barrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_SHADER_READ_BIT);
    }

    private void forwardDownloadResult(long ptr, long size) {
        int count = MemoryUtil.memGetInt(ptr);
        ptr += 8;//skip the (empty) second value
        if (count < 0 || count > 50000) {
            Logger.error(new IllegalStateException("Count unexpected extreme value: " + count + " things may get weird"));
            return;
        }
        if (count > (this.requestBuffer.size() >> 3) - 1) {
            count = (int) ((this.requestBuffer.size() >> 3) - 1);
        }
        CmpLog.rec("traversal", "requestCount", count);
        CmpLog.rec("traversal", "topNodeCount", this.topNodeCount);
        if (count != 0) {
            var buffer = new MemoryBuffer(count * 8L + 8).cpyFrom(ptr - 8);
            MemoryUtil.memPutInt(buffer.address, count);
            this.nodeManager.submitRequestBatch(buffer);
        }
    }

    public VkBuffer getNodeBuffer() {
        return this.nodeBuffer;
    }

    public void free() {
        this.traversal.free();
        this.requestBuffer.free();
        this.nodeBuffer.free();
        this.uniformBuffer.free();
        this.queueMetaBuffer.free();
        this.topNodeIds.free();
        this.scratchQueueA.free();
        this.scratchQueueB.free();
    }
}
