package me.cortex.voxy.common.util;

import java.lang.ref.Cleaner;

public class ThreadLocalMemoryBuffer {
    private static final Cleaner CLEANER = Cleaner.create();
    private static MemoryBuffer createMemoryBuffer(long size) {
        var buffer = new MemoryBuffer(size);
        var ref = MemoryBuffer.createUntrackedUnfreeableRawFrom(buffer.address, buffer.size);
        CLEANER.register(ref, buffer::free);
        return ref;
    }

    //TODO: make this much better
    private final ThreadLocal<MemoryBuffer> threadLocal;

    public ThreadLocalMemoryBuffer(long size) {
        this.threadLocal = ThreadLocal.withInitial(()->createMemoryBuffer(size));
    }

    public MemoryBuffer get() {
        return this.threadLocal.get();
    }
}
