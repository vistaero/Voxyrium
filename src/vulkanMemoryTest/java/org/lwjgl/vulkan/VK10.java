package org.lwjgl.vulkan;

import java.nio.LongBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.lwjgl.PointerBuffer;

// Driver boundary only. Tests execute the unmodified production constructors,
// real LWJGL structs, VkUtil.check and TrackedObject; no physical GPU is needed.
public final class VK10 {
    public static final int VK_BUFFER_USAGE_TRANSFER_SRC_BIT=1, VK_BUFFER_USAGE_TRANSFER_DST_BIT=2,
            VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT=16, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT=32,
            VK_BUFFER_USAGE_INDEX_BUFFER_BIT=64, VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT=256,
            VK_SHARING_MODE_EXCLUSIVE=0, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT=1,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT=2, VK_MEMORY_PROPERTY_HOST_COHERENT_BIT=4;
    public static final long VK_WHOLE_SIZE=-1L, VK_NULL_HANDLE=0L;
    public static final Map<Long, Long> buffers = new LinkedHashMap<>(), memories = new LinkedHashMap<>();
    public static boolean failCreate, failAllocate, failBind, failMemoryType, failFill;
    public static long allocationLimit=Long.MAX_VALUE;
    private static long next=1;
    private static final java.util.List<String> events = new java.util.ArrayList<>();
    public static void reset() {
        buffers.clear(); memories.clear(); events.clear();
        failCreate=failAllocate=failBind=failMemoryType=failFill=false;
        allocationLimit=Long.MAX_VALUE;
    }
    public static int vkCreateBuffer(VkDevice device, VkBufferCreateInfo info, VkAllocationCallbacks allocator, LongBuffer out) {
        if (failCreate) return -2;
        long handle=next++; buffers.put(handle, info.size()); out.put(0, handle); return 0;
    }
    public static void vkGetBufferMemoryRequirements(VkDevice device, long buffer, VkMemoryRequirements out) {
        out.size(buffers.get(buffer)).alignment(16).memoryTypeBits(1);
    }
    public static int vkAllocateMemory(VkDevice device, VkMemoryAllocateInfo info, VkAllocationCallbacks allocator, LongBuffer out) {
        if (failAllocate || info.allocationSize()>allocationLimit) return -2;
        long handle=next++; memories.put(handle, info.allocationSize()); out.put(0,handle); return 0;
    }
    public static int vkBindBufferMemory(VkDevice device, long buffer, long memory, long offset) { return failBind ? -2 : 0; }
    public static void vkDestroyBuffer(VkDevice device, long buffer, VkAllocationCallbacks allocator) {
        if (buffer!=0 && buffers.remove(buffer)==null) throw new AssertionError("double/unknown buffer destroy: "+buffer);
        events.add("buffer");
    }
    public static void vkFreeMemory(VkDevice device, long memory, VkAllocationCallbacks allocator) {
        if (memory!=0 && memories.remove(memory)==null) throw new AssertionError("double/unknown memory free: "+memory);
        events.add("memory");
    }
    public static int vkMapMemory(VkDevice device, long memory, long offset, long size, int flags, PointerBuffer out) { out.put(0,1L); return 0; }
    public static void vkCmdFillBuffer(VkCommandBuffer cmd, long buffer, long offset, long size, int value) {
        if (failFill) throw new IllegalStateException("injected initialization failure");
    }
    public static void vkCmdPipelineBarrier(VkCommandBuffer cmd, int src, int dst, int flags,
            VkMemoryBarrier.Buffer memory, VkBufferMemoryBarrier.Buffer buffers, VkImageMemoryBarrier.Buffer images) {}
    public static int vkDeviceWaitIdle(VkDevice device) { return 0; }
    public static void vkDestroyEvent(VkDevice device, long event, VkAllocationCallbacks allocator) {}
    public static int vkCreateEvent(VkDevice device, VkEventCreateInfo info, VkAllocationCallbacks allocator, LongBuffer out) { out.put(0,next++); return 0; }
    public static void vkCmdSetEvent(VkCommandBuffer cmd, long event, int stages) {}
    public static int vkGetEventStatus(VkDevice device, long event) { return 3; }
    public static int vkResetEvent(VkDevice device, long event) { return 0; }
    public static long liveBytes() { return memories.values().stream().mapToLong(Long::longValue).sum(); }
    public static String destroyOrder() { return String.join(",", events); }
}
