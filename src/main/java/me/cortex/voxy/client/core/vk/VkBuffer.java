package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.client.core.rendering.IRenderList;
import me.cortex.voxy.client.core.rendering.util.IDeviceBuffer;
import me.cortex.voxy.common.util.TrackedObject;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

import static me.cortex.voxy.client.core.vk.VkUtil.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.*;

//Device buffer on the pure-Vulkan path; the VK analogue of GlBuffer. All Voxy
// buffers get a superset of usage flags (storage/indirect/index/transfer) so a
// single class covers every role the GL path used raw buffer ids for. Memory is
// a dedicated device-local allocation (Voxy has few, large, long-lived buffers).
//
//Freeing is DEFERRED through VkFrameCtx — a buffer may still be referenced by
// command buffers in flight when free() is called.
public class VkBuffer extends TrackedObject implements IDeviceBuffer, IRenderList {
    public static final int USAGE_DEFAULT = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
            | VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT
            | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
            | VK_BUFFER_USAGE_INDEX_BUFFER_BIT
            | VK_BUFFER_USAGE_TRANSFER_SRC_BIT
            | VK_BUFFER_USAGE_TRANSFER_DST_BIT;

    private final VkFrameCtx ctx;
    public final long buffer;
    public final long memory;
    private final long size;

    private static int COUNT;
    private static long TOTAL_SIZE;

    public VkBuffer(VkFrameCtx ctx, long size) {
        this(ctx, size, USAGE_DEFAULT, false);
    }

    public VkBuffer(VkFrameCtx ctx, long size, int usage, boolean hostVisible) {
        this.ctx = ctx;
        this.size = size;
        var vctx = ctx.vk();
        try (MemoryStack stack = stackPush()) {
            var bci = VkBufferCreateInfo.calloc(stack).sType$Default()
                    .size(size).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            var pBuf = stack.mallocLong(1);
            check(vkCreateBuffer(vctx.device, bci, null, pBuf), "vkCreateBuffer");
            this.buffer = pBuf.get(0);

            var req = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(vctx.device, this.buffer, req);
            int props = hostVisible
                    ? (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
                    : VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
            var mai = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .allocationSize(req.size())
                    .memoryTypeIndex(vctx.findMemoryType(req.memoryTypeBits(), props));
            var pMem = stack.mallocLong(1);
            check(vkAllocateMemory(vctx.device, mai, null, pMem), "vkAllocateMemory");
            this.memory = pMem.get(0);
            check(vkBindBufferMemory(vctx.device, this.buffer, this.memory, 0), "vkBindBufferMemory");
        }
        COUNT++;
        TOTAL_SIZE += size;
    }

    /** Maps the whole buffer; only valid for hostVisible buffers. */
    public long map() {
        try (MemoryStack stack = stackPush()) {
            var pp = stack.mallocPointer(1);
            check(vkMapMemory(this.ctx.vk().device, this.memory, 0, this.size, 0, pp), "vkMapMemory");
            return pp.get(0);
        }
    }

    @Override
    public long sizeBytes() {
        return this.size;
    }

    public long size() {
        return this.size;
    }

    @Override
    public int glId() {
        throw new UnsupportedOperationException("VkBuffer has no GL id");
    }

    /** Records a fill of 0 across the given range into the current frame commands. */
    public VkBuffer zeroRange(long offset, long len) {
        this.ctx.fillBuffer(this, offset, len, 0);
        return this;
    }

    public VkBuffer zero() {
        return this.zeroRange(0, VK_WHOLE_SIZE);
    }

    public VkBuffer fill(int value) {
        this.ctx.fillBuffer(this, 0, VK_WHOLE_SIZE, value);
        return this;
    }

    @Override
    public void free() {
        this.free0();
        COUNT--;
        TOTAL_SIZE -= this.size;
        this.ctx.deferDestroy(this.buffer, this.memory);
    }

    public static int getCount() {
        return COUNT;
    }

    public static long getTotalSize() {
        return TOTAL_SIZE;
    }
}
