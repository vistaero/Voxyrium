package me.cortex.voxy.client.core.rendering;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;

//CPU side cache for section geometry, not thread safe
public class GeometryCache {
    private long maxCombinedSize;
    private long currentSize;
    private final Long2ObjectLinkedOpenHashMap<BuiltSection> cache = new Long2ObjectLinkedOpenHashMap<>();
    public GeometryCache(long maxSize) {
        this.setMaxTotalSize(maxSize);
    }

    public void setMaxTotalSize(long size) {
        this.maxCombinedSize = size;
    }

    //Puts the section into the cache
    public void put(BuiltSection section) {
        var prev = this.cache.put(section.position, section);
        this.currentSize += section.geometryBuffer.size;
        if (prev != null) {
            this.currentSize -= prev.geometryBuffer.size;
            prev.free();
        }

        while (this.maxCombinedSize <= this.currentSize) {
            var entry = this.cache.removeFirst();
            this.currentSize -= entry.geometryBuffer.size;
            entry.free();
        }
    }

    public BuiltSection remove(long position) {
        var section = this.cache.remove(position);
        if (section != null) {
            this.currentSize -= section.geometryBuffer.size;
        }
        return section;
    }
}
