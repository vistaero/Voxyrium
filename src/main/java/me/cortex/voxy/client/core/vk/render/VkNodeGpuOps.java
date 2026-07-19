package me.cortex.voxy.client.core.vk.render;

import me.cortex.voxy.client.core.rendering.hierachical.INodeGpuOps;
import me.cortex.voxy.client.core.rendering.section.geometry.IBasicGeometryData;
import me.cortex.voxy.client.core.rendering.util.IDeviceBuffer;
import me.cortex.voxy.client.core.vk.VkBuffer;
import me.cortex.voxy.client.core.vk.VkFrameCtx;
import me.cortex.voxy.client.core.vk.VkShaderPipeline;
import me.cortex.voxy.client.core.vk.VkShaderSource;
import me.cortex.voxy.client.core.vk.VkUploadStream;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.UnsafeUtil;
import org.lwjgl.system.MemoryStack;

import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.vkCmdDispatch;

//Vulkan implementation of the node-manager GPU ops (scatter + multi-memcpy computes).
public class VkNodeGpuOps implements INodeGpuOps {
    private final VkFrameCtx ctx;
    private final VkUploadStream uploadStream;
    private final VkShaderPipeline scatterWrite;
    private final VkShaderPipeline multiMemcpy;

    public VkNodeGpuOps(VkFrameCtx ctx, VkUploadStream uploadStream) {
        this.ctx = ctx;
        this.uploadStream = uploadStream;
        this.scatterWrite = new VkShaderPipeline(ctx, "scatter.comp",
                VkShaderSource.load("voxy:util/scatter.comp", VkShaderSource.defs()
                        .def("INPUT_BUFFER_BINDING", 0)
                        .def("OUTPUT_BUFFER1_BINDING", 1)
                        .def("OUTPUT_BUFFER2_BINDING", 2)
                        .build()),
                4,
                List.of(VkShaderPipeline.ssbo(0), VkShaderPipeline.ssbo(1), VkShaderPipeline.ssbo(2)));
        this.multiMemcpy = new VkShaderPipeline(ctx, "memcpy.comp",
                VkShaderSource.load("voxy:util/memcpy.comp", VkShaderSource.defs()
                        .def("INPUT_HEADER_BUFFER_BINDING", 0)
                        .def("INPUT_DATA_BUFFER_BINDING", 1)
                        .def("OUTPUT_BUFFER_BINDING", 2)
                        .build()),
                0,
                List.of(VkShaderPipeline.ssbo(0), VkShaderPipeline.ssbo(1), VkShaderPipeline.ssbo(2)));
    }

    @Override
    public void multiMemcpy(long headerPtr, int copies, long dataPtr, int dataSize, IBasicGeometryData geometry) {
        int upCopies = this.uploadStream.alignUpAlloc(copies * 16);
        int upScratchSize = this.uploadStream.alignUpAlloc(dataSize);
        long off = this.uploadStream.rawUploadAddress(upScratchSize + upCopies);
        UnsafeUtil.memcpy(headerPtr, this.uploadStream.getBaseAddress() + off, copies * 16L);
        UnsafeUtil.memcpy(dataPtr, this.uploadStream.getBaseAddress() + off + upCopies, dataSize);
        this.uploadStream.commit();

        if (copies > 500) {
            Logger.warn("Large amount of copies, lag will probably happen: " + copies);
        }

        var cmd = this.ctx.cmd();
        this.multiMemcpy.bind(cmd);
        try (var binder = this.multiMemcpy.binder()) {
            binder.ssbo(0, this.uploadStream.stagingBufferHandle(), off, upCopies)
                    .ssbo(1, this.uploadStream.stagingBufferHandle(), off + upCopies, upScratchSize)
                    .ssbo(2, (VkBuffer) geometry.geometryBufferHandle())
                    .push(cmd);
        }
        vkCmdDispatch(cmd, copies, 1, 1);
        //Next consumer is the scatterWrite compute pass (or the cleaner); scope to
        // COMPUTE -> COMPUTE so unrelated raster/transfer can overlap.
        this.ctx.computeToComputeBarrier();
    }

    @Override
    public void scatterWrite(long chunksPtr, int count, IDeviceBuffer nodeBuffer, IBasicGeometryData geometry) {
        int chunks = (count + 3) / 4;
        int streamSize = chunks * 80;//80 bytes per chunk
        long off = this.uploadStream.rawUploadAddress(streamSize);
        UnsafeUtil.memcpy(chunksPtr, this.uploadStream.getBaseAddress() + off, streamSize);
        this.uploadStream.commit();

        var cmd = this.ctx.cmd();
        this.scatterWrite.bind(cmd);
        try (var binder = this.scatterWrite.binder()) {
            binder.ssbo(0, this.uploadStream.stagingBufferHandle(), off, this.uploadStream.alignUpAlloc(streamSize))
                    .ssbo(1, (VkBuffer) nodeBuffer)
                    .ssbo(2, (VkBuffer) geometry.metadataBufferHandle())
                    .push(cmd);
        }
        try (MemoryStack stack = stackPush()) {
            this.scatterWrite.pushConstants(cmd, stack.malloc(4).putInt(0, count));
        }
        vkCmdDispatch(cmd, (count + 127) / 128, 1, 1);
        //Next consumer is the node cleaner / next-frame traversal (both compute).
        this.ctx.computeToComputeBarrier();
    }

    @Override
    public void free() {
        this.scatterWrite.free();
        this.multiMemcpy.free();
    }
}
