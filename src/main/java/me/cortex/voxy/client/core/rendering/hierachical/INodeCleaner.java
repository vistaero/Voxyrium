package me.cortex.voxy.client.core.rendering.hierachical;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.client.core.rendering.util.IDeviceBuffer;

//Backend-neutral contract of the GPU node cleaner (GL NodeCleaner / VkNodeCleaner).
public interface INodeCleaner {
    void tick(IDeviceBuffer nodeDataBuffer);

    void updateIds(IntOpenHashSet collection);

    void free();
}
