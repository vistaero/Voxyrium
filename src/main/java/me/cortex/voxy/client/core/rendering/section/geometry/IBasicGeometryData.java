package me.cortex.voxy.client.core.rendering.section.geometry;

import me.cortex.voxy.client.core.rendering.util.IDeviceBuffer;

//Backend-neutral view of the "basic" geometry data store (a big quad buffer +
// per-section metadata). Implemented by BasicSectionGeometryData (GL) and
// VkSectionGeometryData (pure Vulkan); AsyncNodeManager and the section
// renderers traffic in this so the CPU-side management is shared.
public interface IBasicGeometryData extends IGeometryData {
    int SECTION_METADATA_SIZE = BasicSectionGeometryData.SECTION_METADATA_SIZE;

    IDeviceBuffer geometryBufferHandle();

    IDeviceBuffer metadataBufferHandle();

    /** Ensure element indices up to maxElementAccess are backed (sparse GL); no-op elsewhere. */
    void ensureAccessable(int maxElementAccess);

    void setSectionCount(int count);

    int getMaxSectionCount();

    long getGeometryCapacityBytes();
}
