package me.cortex.voxy.client.core.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;
import org.lwjgl.vulkan.*;

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

/**
 * A 2D render target allocated by VK with exportable memory, visible in GL as a
 * texture: VK renders LOD color/depth into it; GL composites it into MC's
 * framebuffer. Recreated on resolution change.
 */
public final class SharedImage {
    public final long vkImage;
    public final long vkMemory;
    public final long vkView;
    public final int glMemoryObject;
    public final int glTexture;
    public final int width, height;
    public final int vkFormat;
    private final VulkanContext ctx;

    public SharedImage(VulkanContext ctx, int width, int height, int vkFormat, int glInternalFormat, int vkUsage, int aspect) {
        this.ctx = ctx; this.width = width; this.height = height; this.vkFormat = vkFormat;
        boolean win = Platform.get() == Platform.WINDOWS;
        int handleType = win ? VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT_KHR
                             : VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT_KHR;
        try (MemoryStack stack = stackPush()) {
            var ext = VkExternalMemoryImageCreateInfo.calloc(stack).sType$Default().handleTypes(handleType);
            var ici = VkImageCreateInfo.calloc(stack).sType$Default().pNext(ext)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(vkFormat)
                    .extent(e -> e.width(width).height(height).depth(1))
                    .mipLevels(1).arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(vkUsage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            var pImg = stack.mallocLong(1);
            check(vkCreateImage(ctx.device, ici, null, pImg), "vkCreateImage(shared)");
            this.vkImage = pImg.get(0);

            var req = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(ctx.device, this.vkImage, req);
            var export = VkExportMemoryAllocateInfo.calloc(stack).sType$Default().handleTypes(handleType);
            var dedicated = VkMemoryDedicatedAllocateInfo.calloc(stack).sType$Default().pNext(export).image(this.vkImage);
            var mai = VkMemoryAllocateInfo.calloc(stack).sType$Default().pNext(dedicated)
                    .allocationSize(req.size())
                    .memoryTypeIndex(ctx.findMemoryType(req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            var pMem = stack.mallocLong(1);
            check(vkAllocateMemory(ctx.device, mai, null, pMem), "vkAllocateMemory(sharedImage)");
            this.vkMemory = pMem.get(0);
            check(vkBindImageMemory(ctx.device, this.vkImage, this.vkMemory, 0), "vkBindImageMemory");

            var vci = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(this.vkImage).viewType(VK_IMAGE_VIEW_TYPE_2D).format(vkFormat);
            vci.subresourceRange().aspectMask(aspect).levelCount(1).layerCount(1);
            var pView = stack.mallocLong(1);
            check(vkCreateImageView(ctx.device, vci, null, pView), "vkCreateImageView(shared)");
            this.vkView = pView.get(0);

            this.glMemoryObject = glCreateMemoryObjectsEXT();
            if (win) {
                var ghi = VkMemoryGetWin32HandleInfoKHR.calloc(stack).sType$Default()
                        .memory(this.vkMemory).handleType(handleType);
                var pHandle = stack.mallocPointer(1);
                check(vkGetMemoryWin32HandleKHR(ctx.device, ghi, pHandle), "vkGetMemoryWin32HandleKHR(img)");
                glImportMemoryWin32HandleEXT(this.glMemoryObject, req.size(), GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, pHandle.get(0));
            } else {
                var gfi = VkMemoryGetFdInfoKHR.calloc(stack).sType$Default()
                        .memory(this.vkMemory).handleType(handleType);
                var pFd = stack.mallocInt(1);
                check(vkGetMemoryFdKHR(ctx.device, gfi, pFd), "vkGetMemoryFdKHR(img)");
                glImportMemoryFdEXT(this.glMemoryObject, req.size(), GL_HANDLE_TYPE_OPAQUE_FD_EXT, pFd.get(0));
            }
            this.glTexture = glCreateTextures(GL_TEXTURE_2D);
            glTextureParameteri(this.glTexture, GL_TEXTURE_TILING_EXT, GL_OPTIMAL_TILING_EXT);
            glTextureStorageMem2DEXT(this.glTexture, 1, glInternalFormat, width, height, this.glMemoryObject, 0);
        }
    }

    public void free() {
        glDeleteTextures(this.glTexture);
        glDeleteMemoryObjectsEXT(this.glMemoryObject);
        vkDestroyImageView(this.ctx.device, this.vkView, null);
        vkDestroyImage(this.ctx.device, this.vkImage, null);
        vkFreeMemory(this.ctx.device, this.vkMemory, null);
    }
}
