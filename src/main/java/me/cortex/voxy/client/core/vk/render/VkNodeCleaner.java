package me.cortex.voxy.client.core.vk.render;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.INodeCleaner;
import me.cortex.voxy.client.core.rendering.util.IDeviceBuffer;
import me.cortex.voxy.client.core.vk.VkBuffer;
import me.cortex.voxy.client.core.vk.VkDownloadStream;
import me.cortex.voxy.client.core.vk.VkFrameCtx;
import me.cortex.voxy.client.core.vk.VkShaderPipeline;
import me.cortex.voxy.client.core.vk.VkShaderSource;
import me.cortex.voxy.client.core.vk.VkUploadStream;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.vkCmdDispatch;

//Pure-VK port of NodeCleaner: finds the least-recently-rendered nodes on the
// GPU (sort + transform computes) and feeds them back to the AsyncNodeManager
// for geometry eviction when the geometry buffer runs low. Mirrors the GL
// implementation pass-for-pass.
public class VkNodeCleaner implements INodeCleaner {
    private static final int SORTING_WORKER_SIZE = 64;
    private static final int WORK_PER_THREAD = 8;
    static final int OUTPUT_COUNT = 256;

    private final VkFrameCtx ctx;
    private final VkUploadStream uploadStream;
    private final VkDownloadStream downloadStream;
    private final AsyncNodeManager nodeManager;

    private final VkShaderPipeline sorter;
    private final VkShaderPipeline resultTransformer;
    private final VkShaderPipeline batchClear;

    public final VkBuffer visibilityBuffer;
    private final VkBuffer outputBuffer;
    int visibilityId = 0;

    public VkNodeCleaner(VkFrameCtx ctx, VkUploadStream up, VkDownloadStream down, AsyncNodeManager nodeManager) {
        this.ctx = ctx;
        this.uploadStream = up;
        this.downloadStream = down;
        this.nodeManager = nodeManager;
        this.visibilityBuffer = new VkBuffer(ctx, nodeManager.maxNodeCount * 4L).fill(-1);
        this.outputBuffer = new VkBuffer(ctx, OUTPUT_COUNT * 4 + OUTPUT_COUNT * 8);
        ctx.flushImmediate();

        this.sorter = new VkShaderPipeline(ctx, "sort_visibility.comp",
                VkShaderSource.load("voxy:lod/hierarchical/cleaner/sort_visibility.comp", VkShaderSource.defs()
                        .def("WORK_SIZE", SORTING_WORKER_SIZE)
                        .def("ELEMS_PER_THREAD", WORK_PER_THREAD)
                        .def("OUTPUT_SIZE", OUTPUT_COUNT)
                        .def("VISIBILITY_BUFFER_BINDING", 1)
                        .def("OUTPUT_BUFFER_BINDING", 2)
                        .def("NODE_DATA_BINDING", 3)
                        .build()),
                0,
                List.of(VkShaderPipeline.ssbo(1), VkShaderPipeline.ssbo(2), VkShaderPipeline.ssbo(3)));

        this.resultTransformer = new VkShaderPipeline(ctx, "result_transformer.comp",
                VkShaderSource.load("voxy:lod/hierarchical/cleaner/result_transformer.comp", VkShaderSource.defs()
                        .def("OUTPUT_SIZE", OUTPUT_COUNT)
                        .def("MIN_ID_BUFFER_BINDING", 0)
                        .def("NODE_BUFFER_BINDING", 1)
                        .def("OUTPUT_BUFFER_BINDING", 2)
                        .def("VISIBILITY_BUFFER_BINDING", 3)
                        .build()),
                4,
                List.of(VkShaderPipeline.ssbo(0), VkShaderPipeline.ssbo(1), VkShaderPipeline.ssbo(2), VkShaderPipeline.ssbo(3)));

        this.batchClear = new VkShaderPipeline(ctx, "batch_visibility_set.comp",
                VkShaderSource.load("voxy:lod/hierarchical/cleaner/batch_visibility_set.comp", VkShaderSource.defs()
                        .def("VISIBILITY_BUFFER_BINDING", 0)
                        .def("LIST_BUFFER_BINDING", 1)
                        .build()),
                8,
                List.of(VkShaderPipeline.ssbo(0), VkShaderPipeline.ssbo(1)));
    }

    @Override
    public void tick(IDeviceBuffer nodeDataBufferIn) {
        var nodeDataBuffer = (VkBuffer) nodeDataBufferIn;
        this.visibilityId++;
        if (this.shouldCleanGeometry()) {
            this.outputBuffer.fill(this.nodeManager.maxNodeCount - 2);
            var cmd = this.ctx.cmd();

            this.sorter.bind(cmd);
            try (var binder = this.sorter.binder()) {
                binder.ssbo(1, this.visibilityBuffer)
                        .ssbo(2, this.outputBuffer)
                        .ssbo(3, nodeDataBuffer)
                        .push(cmd);
            }
            vkCmdDispatch(cmd, (this.nodeManager.getCurrentMaxNodeId() + (SORTING_WORKER_SIZE * WORK_PER_THREAD) - 1) / (SORTING_WORKER_SIZE * WORK_PER_THREAD), 1, 1);
            //Sort -> resultTransformer: both compute.
            this.ctx.computeToComputeBarrier();

            this.resultTransformer.bind(cmd);
            try (var binder = this.resultTransformer.binder()) {
                binder.ssbo(0, this.outputBuffer.buffer, 0, 4L * OUTPUT_COUNT)
                        .ssbo(1, nodeDataBuffer)
                        .ssbo(2, this.outputBuffer.buffer, 4L * OUTPUT_COUNT, 8L * OUTPUT_COUNT)
                        .ssbo(3, this.visibilityBuffer)
                        .push(cmd);
            }
            try (MemoryStack stack = stackPush()) {
                this.resultTransformer.pushConstants(cmd, stack.malloc(4).putInt(0, this.visibilityId));
            }
            vkCmdDispatch(cmd, 1, 1, 1);
            //resultTransformer -> download copy: compute -> transfer.
            this.ctx.computeToTransferBarrier();

            this.downloadStream.download(this.outputBuffer, 4L * OUTPUT_COUNT, 8L * OUTPUT_COUNT,
                    buffer -> this.nodeManager.submitRemoveBatch(buffer.copy()));
        }
    }

    private boolean shouldCleanGeometry() {
        long remaining = this.nodeManager.getGeometryCapacity() - this.nodeManager.getUsedGeometryCapacity();
        return remaining < 256_000_000;//If less than 256 mb free memory
    }

    @Override
    public void updateIds(IntOpenHashSet collection) {
        if (!collection.isEmpty()) {
            int count = collection.size();
            long off = this.uploadStream.rawUploadAddress(count * 4);
            long ptr = this.uploadStream.getBaseAddress() + off;
            var iter = collection.iterator();
            while (iter.hasNext()) {
                MemoryUtil.memPutInt(ptr, iter.nextInt());
                ptr += 4;
            }
            this.uploadStream.commit();

            var cmd = this.ctx.cmd();
            this.batchClear.bind(cmd);
            try (var binder = this.batchClear.binder()) {
                binder.ssbo(0, this.visibilityBuffer)
                        .ssbo(1, this.uploadStream.stagingBufferHandle(), off, this.uploadStream.alignUpAlloc(count * 4))
                        .push(cmd);
            }
            try (MemoryStack stack = stackPush()) {
                var pc = stack.malloc(8);
                pc.putInt(0, count);
                pc.putInt(4, this.visibilityId);
                this.batchClear.pushConstants(cmd, pc);
            }
            vkCmdDispatch(cmd, (count + 127) / 128, 1, 1);
            //batchClear -> next-frame traversal/sorter (compute -> compute).
            this.ctx.computeToComputeBarrier();
        }
    }

    @Override
    public void free() {
        this.sorter.free();
        this.resultTransformer.free();
        this.batchClear.free();
        this.visibilityBuffer.free();
        this.outputBuffer.free();
    }
}
