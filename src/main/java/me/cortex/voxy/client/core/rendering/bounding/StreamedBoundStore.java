package me.cortex.voxy.client.core.rendering.bounding;

import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.util.AbstractUploadStream;
import me.cortex.voxy.client.core.rendering.util.IDeviceBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;

import java.util.Arrays;
import java.util.function.LongFunction;

//This is a render subsystem, its very simple in what it does
// it renders an AABB around loaded chunks, thats it
//Backend-neutral: the device buffer comes from the injected allocator
// (GlBuffer::new on the GL path, VkBuffer on the VK path) so the Sodium
// visibility mixins can feed this store on both backends.
public final class StreamedBoundStore implements IBoundStore {
    private static final int INIT_MAX_CHUNK_COUNT = 1<<12;
    private final LongFunction<IDeviceBuffer> allocator;
    private IDeviceBuffer chunkPosBuffer;//Stored as ivec2
    private int count;//NOTE: count here is in INTS, not LONGS (i.e. 1 pos is 2 ints)
    private boolean didChange = false;
    private int[] visibleSections = new int[INIT_MAX_CHUNK_COUNT*2];
    public StreamedBoundStore(LongFunction<IDeviceBuffer> allocator) {
        this.allocator = allocator;
        this.chunkPosBuffer = allocator.apply(INIT_MAX_CHUNK_COUNT*8);
    }

    //Bind and render, changing as little gl state as possible so that the caller may configure how it wants to render
    @Override
    public void preRender(Viewport<?> viewport) {
        if (this.count == 0 || !this.didChange) return;
        if (this.count*4L>this.chunkPosBuffer.sizeBytes()) {
            this.chunkPosBuffer.free();
            this.chunkPosBuffer = this.allocator.apply(((long) Math.ceil(this.count*1.25))*4);
        }
        long addr = AbstractUploadStream.INSTANCE().upload(this.chunkPosBuffer, 0, this.count*4);
        UnsafeUtil.memcpy(this.visibleSections, this.count, addr);
        AbstractUploadStream.INSTANCE().commit();
        this.didChange = false;
    }

    public void reset() {
        this.count = 0;
        this.didChange = true;
    }

    public void put(long pos) {
        pos = IBoundStore.transformBeforeStore(pos);
        this.visibleSections[this.count++] = (int)(pos&0xFFFFFFFFL);
        this.visibleSections[this.count++] = (int)((pos>>>32)&0xFFFFFFFFL);
        if (this.count >= this.visibleSections.length-2) {
            this.visibleSections = Arrays.copyOf(this.visibleSections, (int) (this.visibleSections.length*1.25));
        }
    }

    public void free() {
        this.chunkPosBuffer.free();
    }

    @Override
    public IDeviceBuffer getBuffer() {
        return this.chunkPosBuffer;
    }

    @Override
    public int getCount() {
        return this.count>>1;
    }
}
