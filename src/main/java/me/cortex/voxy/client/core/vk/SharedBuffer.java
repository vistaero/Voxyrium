package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.client.core.rendering.IRenderList;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;

import static me.cortex.voxy.client.core.vk.VkUtil.check;
import static org.lwjgl.opengl.EXTMemoryObject.*;
import static org.lwjgl.opengl.EXTMemoryObjectFD.GL_HANDLE_TYPE_OPAQUE_FD_EXT;
import static org.lwjgl.opengl.EXTMemoryObjectFD.glImportMemoryFdEXT;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT;
import static org.lwjgl.opengl.GL45C.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRExternalMemoryFd.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT_KHR;
import static org.lwjgl.vulkan.KHRExternalMemoryFd.vkGetMemoryFdKHR;
import static org.lwjgl.vulkan.KHRExternalMemoryWin32.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT_KHR;
import static org.lwjgl.vulkan.KHRExternalMemoryWin32.vkGetMemoryWin32HandleKHR;
import static org.lwjgl.vulkan.VK10.*;
import org.lwjgl.vulkan.*;

/**
 * A buffer whose memory is allocated by Vulkan with an exportable handle and
 * imported into the current GL context. Both APIs address the same storage:
 * GL compute passes (hierarchical traversal, command generation) write it,
 * the VK graphics queue consumes it. Implements IRenderList so it can sit
 * directly behind Viewport#getRenderList().
 */
public final class SharedBuffer implements IRenderList {
    public final long vkBuffer;
    public final long vkMemory;
    private final int glMemoryObject;
    public final int glBufferId;
    private final long size;
    private final VulkanContext ctx;

    public SharedBuffer(VulkanContext ctx, long size, int vkUsage) {
        this.ctx = ctx;
        this.size = size;
        boolean win = Platform.get() == Platform.WINDOWS;
        int vkHandleType = win ? VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT_KHR
                               : VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT_KHR;
        try (MemoryStack stack = stackPush()) {
            var extBuf = VkExternalMemoryBufferCreateInfo.calloc(stack).sType$Default().handleTypes(vkHandleType);
            var bci = VkBufferCreateInfo.calloc(stack).sType$Default().pNext(extBuf)
                    .size(size).usage(vkUsage).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            var pBuf = stack.mallocLong(1);
            check(vkCreateBuffer(ctx.device, bci, null, pBuf), "vkCreateBuffer(shared)");
            this.vkBuffer = pBuf.get(0);

            var req = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(ctx.device, this.vkBuffer, req);
            var export = VkExportMemoryAllocateInfo.calloc(stack).sType$Default().handleTypes(vkHandleType);
            var dedicated = VkMemoryDedicatedAllocateInfo.calloc(stack).sType$Default()
                    .pNext(export).buffer(this.vkBuffer);
            var mai = VkMemoryAllocateInfo.calloc(stack).sType$Default().pNext(dedicated)
                    .allocationSize(req.size())
                    .memoryTypeIndex(ctx.findMemoryType(req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            var pMem = stack.mallocLong(1);
            check(vkAllocateMemory(ctx.device, mai, null, pMem), "vkAllocateMemory(shared)");
            this.vkMemory = pMem.get(0);
            check(vkBindBufferMemory(ctx.device, this.vkBuffer, this.vkMemory, 0), "vkBindBufferMemory");

            this.glMemoryObject = glCreateMemoryObjectsEXT();
            if (win) {
                var ghi = VkMemoryGetWin32HandleInfoKHR.calloc(stack).sType$Default()
                        .memory(this.vkMemory).handleType(vkHandleType);
                var pHandle = stack.mallocPointer(1);
                check(vkGetMemoryWin32HandleKHR(ctx.device, ghi, pHandle), "vkGetMemoryWin32HandleKHR");
                glImportMemoryWin32HandleEXT(this.glMemoryObject, req.size(), GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, pHandle.get(0));
            } else {
                var gfi = VkMemoryGetFdInfoKHR.calloc(stack).sType$Default()
                        .memory(this.vkMemory).handleType(vkHandleType);
                var pFd = stack.mallocInt(1);
                check(vkGetMemoryFdKHR(ctx.device, gfi, pFd), "vkGetMemoryFdKHR");
                glImportMemoryFdEXT(this.glMemoryObject, req.size(), GL_HANDLE_TYPE_OPAQUE_FD_EXT, pFd.get(0));
            }
            this.glBufferId = glCreateBuffers();
            glNamedBufferStorageMemEXT(this.glBufferId, size, this.glMemoryObject, 0);
        }
    }

    @Override public int glId() { return this.glBufferId; }
    @Override public long sizeBytes() { return this.size; }

    public void free() {
        glDeleteBuffers(this.glBufferId);
        glDeleteMemoryObjectsEXT(this.glMemoryObject);
        vkDestroyBuffer(this.ctx.device, this.vkBuffer, null);
        vkFreeMemory(this.ctx.device, this.vkMemory, null);
    }
}
