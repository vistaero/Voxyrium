package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.common.util.MemoryBuffer;

import java.util.function.Consumer;

//Backend-neutral GPU->CPU readback API; see AbstractUploadStream for the
// frame/tick model. Callbacks fire on the render thread from tick() once the
// GPU work that produced the data has provably completed.
public abstract class AbstractDownloadStream {
    public interface DownloadResultConsumer {
        void consume(long ptr, long size);
    }

    public void download(IDeviceBuffer buffer, DownloadResultConsumer resultConsumer) {
        this.download(buffer, 0, buffer.sizeBytes(), resultConsumer);
    }

    public void download(IDeviceBuffer buffer, Consumer<MemoryBuffer> resultConsumer) {
        this.download(buffer, 0, buffer.sizeBytes(), resultConsumer);
    }

    public void download(IDeviceBuffer buffer, long downloadOffset, long size, Consumer<MemoryBuffer> consumer) {
        this.download(buffer, downloadOffset, size, (ptr, size2) -> consumer.accept(MemoryBuffer.createUntrackedUnfreeableRawFrom(ptr, size)));
    }

    public abstract void download(IDeviceBuffer buffer, long downloadOffset, long size, DownloadResultConsumer resultConsumer);

    public abstract void commit();

    public abstract void tick();

    /** Force-completes and discards all pending downloads (shutdown paths). */
    public abstract void waitDiscard();

    /** Force-completes all pending downloads, firing callbacks (shutdown flush). */
    public abstract void flushWaitClear();

    public abstract void free();

    //Backend-selected global download stream; see AbstractUploadStream.INSTANCE() for
    // why the holder lives on the abstract class (GL classload safety on Vulkan).
    private static AbstractDownloadStream INSTANCE;

    public static AbstractDownloadStream INSTANCE() {
        var instance = INSTANCE;
        if (instance == null) {
            instance = INSTANCE = new DownloadStream(1 << 25);//32 mb download buffer
        }
        return instance;
    }

    public static void setInstance(AbstractDownloadStream instance) {
        if (INSTANCE != null) throw new IllegalStateException("Download stream already initialized");
        INSTANCE = instance;
    }

    public static void clearInstance() {
        INSTANCE = null;
    }
}
