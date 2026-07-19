package me.cortex.voxy.client.core.rendering.hierachical;

import me.cortex.voxy.client.core.rendering.section.geometry.IBasicGeometryData;
import me.cortex.voxy.client.core.rendering.util.IDeviceBuffer;

//The two GPU operations AsyncNodeManager needs each render-thread sync,
// abstracted per backend (GlNodeGpuOps / VkNodeGpuOps):
//
//  - multiMemcpy: stream (header,data) scratch to the GPU and scatter-copy the
//    described ranges into the geometry buffer (voxy:util/memcpy.comp);
//  - scatterWrite: stream packed (location,value) chunks and scatter them into
//    the node buffer + section metadata buffer (voxy:util/scatter.comp).
//
//Source pointers are CPU addresses owned by the caller; implementations stage
// them through their upload stream and dispatch compute.
public interface INodeGpuOps {
    void multiMemcpy(long headerPtr, int copies, long dataPtr, int dataSize, IBasicGeometryData geometry);

    void scatterWrite(long chunksPtr, int count, IDeviceBuffer nodeBuffer, IBasicGeometryData geometry);

    void free();
}
