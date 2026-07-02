package me.cortex.voxy.client.core.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;
import org.lwjgl.vulkan.*;

import static me.cortex.voxy.client.core.vk.VkUtil.check;
import static org.lwjgl.opengl.EXTSemaphore.glDeleteSemaphoresEXT;
import static org.lwjgl.opengl.EXTSemaphore.glGenSemaphoresEXT;
import static org.lwjgl.opengl.EXTSemaphoreFD.GL_HANDLE_TYPE_OPAQUE_FD_EXT;
import static org.lwjgl.opengl.EXTSemaphoreFD.glImportSemaphoreFdEXT;
import static org.lwjgl.opengl.EXTSemaphoreWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT;
import static org.lwjgl.opengl.EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRExternalSemaphoreFd.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT_KHR;
import static org.lwjgl.vulkan.KHRExternalSemaphoreFd.vkGetSemaphoreFdKHR;
import static org.lwjgl.vulkan.KHRExternalSemaphoreWin32.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT_KHR;
import static org.lwjgl.vulkan.KHRExternalSemaphoreWin32.vkGetSemaphoreWin32HandleKHR;
import static org.lwjgl.vulkan.VK10.*;

/** VK binary semaphore exported to GL, for cross-API queue ordering (GL compute -> VK draw -> GL composite). */
public final class SharedSemaphore {
    public final long vkSemaphore;
    public final int glSemaphore;
    private final VulkanContext ctx;

    public SharedSemaphore(VulkanContext ctx) {
        this.ctx = ctx;
        boolean win = Platform.get() == Platform.WINDOWS;
        int handleType = win ? VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT_KHR
                             : VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT_KHR;
        try (MemoryStack stack = stackPush()) {
            var export = VkExportSemaphoreCreateInfo.calloc(stack).sType$Default().handleTypes(handleType);
            var sci = VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(export);
            var pSem = stack.mallocLong(1);
            check(vkCreateSemaphore(ctx.device, sci, null, pSem), "vkCreateSemaphore(export)");
            this.vkSemaphore = pSem.get(0);
            this.glSemaphore = glGenSemaphoresEXT();
            if (win) {
                var gi = VkSemaphoreGetWin32HandleInfoKHR.calloc(stack).sType$Default()
                        .semaphore(this.vkSemaphore).handleType(handleType);
                var pHandle = stack.mallocPointer(1);
                check(vkGetSemaphoreWin32HandleKHR(ctx.device, gi, pHandle), "vkGetSemaphoreWin32HandleKHR");
                glImportSemaphoreWin32HandleEXT(this.glSemaphore, GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, pHandle.get(0));
            } else {
                var gi = VkSemaphoreGetFdInfoKHR.calloc(stack).sType$Default()
                        .semaphore(this.vkSemaphore).handleType(handleType);
                var pFd = stack.mallocInt(1);
                check(vkGetSemaphoreFdKHR(ctx.device, gi, pFd), "vkGetSemaphoreFdKHR");
                glImportSemaphoreFdEXT(this.glSemaphore, GL_HANDLE_TYPE_OPAQUE_FD_EXT, pFd.get(0));
            }
        }
    }

    public void free() {
        glDeleteSemaphoresEXT(this.glSemaphore);
        vkDestroySemaphore(this.ctx.device, this.vkSemaphore, null);
    }
}
