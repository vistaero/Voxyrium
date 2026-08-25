package me.cortex.voxy.client.core.vk.render;

import me.cortex.voxy.client.core.rendering.section.geometry.IBasicGeometryData;
import me.cortex.voxy.client.core.rendering.util.IDeviceBuffer;
import me.cortex.voxy.client.core.vk.VkBuffer;
import me.cortex.voxy.client.core.vk.VkFrameCtx;
import me.cortex.voxy.common.Logger;

//Pure-VK geometry store: the quad geometry buffer + per-section metadata as
// plain device-local VkBuffers (no sparse tricks — Vulkan allocation succeeds
// or fails up front; on failure we halve the capacity and retry).
public class VkSectionGeometryData implements IBasicGeometryData {
    private final VkBuffer sectionMetadataBuffer;
    private final VkBuffer geometryBuffer;
    private final int maxSectionCount;
    private int currentSectionCount;

    public VkSectionGeometryData(VkFrameCtx ctx, int maxSectionCount, long geometryCapacity) {
        this.maxSectionCount = maxSectionCount;
        if ((geometryCapacity % 8) != 0) throw new IllegalStateException();
        this.sectionMetadataBuffer = new VkBuffer(ctx, (long) maxSectionCount * SECTION_METADATA_SIZE);
        VkBuffer buffer = null;
        long capacity = geometryCapacity;
        while (buffer == null) {
            try {
                Logger.info("Allocating " + (capacity / (1024 * 1024)) + "MB VK geometry buffer");
                buffer = new VkBuffer(ctx, capacity);
            } catch (RuntimeException e) {
                if (capacity <= (256L << 20)) throw e;
                capacity /= 2;
                Logger.warn("VK geometry allocation failed, retrying with " + (capacity / (1024 * 1024)) + "MB");
            }
        }
        this.geometryBuffer = buffer;
        //Match the GL path's zeroed geometry buffer
        this.geometryBuffer.zero();
        this.sectionMetadataBuffer.zero();
        ctx.flushImmediate();
    }

    @Override
    public IDeviceBuffer geometryBufferHandle() {
        return this.geometryBuffer;
    }

    @Override
    public IDeviceBuffer metadataBufferHandle() {
        return this.sectionMetadataBuffer;
    }

    public VkBuffer geometryBuffer() {
        return this.geometryBuffer;
    }

    public VkBuffer metadataBuffer() {
        return this.sectionMetadataBuffer;
    }

    @Override
    public void ensureAccessable(int maxElementAccess) {
        //Fully-resident allocation: nothing to commit
    }

    @Override
    public int getSectionCount() {
        return this.currentSectionCount;
    }

    @Override
    public void setSectionCount(int count) {
        this.currentSectionCount = count;
    }

    @Override
    public int getMaxSectionCount() {
        return this.maxSectionCount;
    }

    @Override
    public long getGeometryCapacityBytes() {
        return this.geometryBuffer.size();
    }

    @Override
    public long getMaxCapacity() {
        return this.geometryBuffer.size();
    }

    @Override
    public void free() {
        this.sectionMetadataBuffer.free();
        this.geometryBuffer.free();
    }
}
