package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.common.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static me.cortex.voxy.client.core.vk.VkUtil.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

/**
 * Owns Voxy's private VkInstance/VkDevice. Voxy does NOT take over Minecraft's
 * swapchain: the interop model is VK renders LODs offscreen into images whose
 * memory is exported (VK_KHR_external_memory) and imported into the game's GL
 * context (GL_EXT_memory_object), synchronized with exported semaphores.
 */
public final class VulkanContext {
    public final VkInstance instance;
    public final VkPhysicalDevice physicalDevice;
    public final VkDevice device;
    public final VkQueue queue;
    public final int queueFamily;
    public final boolean hasDrawIndirectCount;
    public final String deviceName;
    public final long commandPool;

    private static final String[] REQUIRED_DEVICE_EXTS_COMMON = {
            "VK_KHR_external_memory",
            "VK_KHR_external_semaphore",
    };

    public static String[] platformInteropExts() {
        return Platform.get() == Platform.WINDOWS
                ? new String[]{"VK_KHR_external_memory_win32", "VK_KHR_external_semaphore_win32"}
                : new String[]{"VK_KHR_external_memory_fd", "VK_KHR_external_semaphore_fd"};
    }

    public VulkanContext() {
        try (MemoryStack stack = stackPush()) {
            var appInfo = VkApplicationInfo.calloc(stack)
                    .sType$Default()
                    .pApplicationName(stack.UTF8("voxy"))
                    .pEngineName(stack.UTF8("voxy-vk"))
                    .apiVersion(VK_API_VERSION_1_2);
            var ici = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(appInfo);
            PointerBuffer pInstance = stack.mallocPointer(1);
            check(vkCreateInstance(ici, null, pInstance), "vkCreateInstance");
            this.instance = new VkInstance(pInstance.get(0), ici);

            IntBuffer count = stack.mallocInt(1);
            check(vkEnumeratePhysicalDevices(this.instance, count, null), "enumerate devices (count)");
            if (count.get(0) == 0) throw new IllegalStateException("No Vulkan physical devices");
            PointerBuffer devices = stack.mallocPointer(count.get(0));
            check(vkEnumeratePhysicalDevices(this.instance, count, devices), "enumerate devices");

            VkPhysicalDevice chosen = null; int chosenFamily = -1; boolean chosenDic = false; String chosenName = null;
            for (int i = 0; i < devices.capacity() && chosen == null; i++) {
                var pd = new VkPhysicalDevice(devices.get(i), this.instance);
                Set<String> exts = deviceExtensions(pd, stack);
                if (!hasAll(exts, REQUIRED_DEVICE_EXTS_COMMON) || !hasAll(exts, platformInteropExts())) continue;
                int family = findGraphicsQueueFamily(pd, stack);
                if (family < 0) continue;
                var props = VkPhysicalDeviceProperties.calloc(stack);
                vkGetPhysicalDeviceProperties(pd, props);
                chosen = pd; chosenFamily = family; chosenName = props.deviceNameString();
                chosenDic = exts.contains("VK_KHR_draw_indirect_count") || props.apiVersion() >= VK_API_VERSION_1_2;
            }
            if (chosen == null) throw new IllegalStateException("No VK device with GL-interop extensions");
            this.physicalDevice = chosen; this.queueFamily = chosenFamily;
            this.hasDrawIndirectCount = chosenDic; this.deviceName = chosenName;

            List<String> devExts = new ArrayList<>(List.of(REQUIRED_DEVICE_EXTS_COMMON));
            devExts.addAll(List.of(platformInteropExts()));
            if (chosenDic) devExts.add("VK_KHR_draw_indirect_count");
            PointerBuffer pExts = stack.mallocPointer(devExts.size());
            for (String e : devExts) pExts.put(stack.UTF8(e));
            pExts.flip();

            var queueCI = VkDeviceQueueCreateInfo.calloc(1, stack).sType$Default()
                    .queueFamilyIndex(this.queueFamily)
                    .pQueuePriorities(stack.floats(1.0f));
            var features12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default()
                    .drawIndirectCount(chosenDic);
            var features = VkPhysicalDeviceFeatures.calloc(stack).multiDrawIndirect(true);
            var dci = VkDeviceCreateInfo.calloc(stack).sType$Default()
                    .pNext(features12)
                    .pQueueCreateInfos(queueCI)
                    .ppEnabledExtensionNames(pExts)
                    .pEnabledFeatures(features);
            PointerBuffer pDevice = stack.mallocPointer(1);
            check(vkCreateDevice(this.physicalDevice, dci, null, pDevice), "vkCreateDevice");
            this.device = new VkDevice(pDevice.get(0), this.physicalDevice, dci);

            PointerBuffer pQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(this.device, this.queueFamily, 0, pQueue);
            this.queue = new VkQueue(pQueue.get(0), this.device);

            var cpci = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(this.queueFamily);
            var pPool = stack.mallocLong(1);
            check(vkCreateCommandPool(this.device, cpci, null, pPool), "vkCreateCommandPool");
            this.commandPool = pPool.get(0);
            Logger.info("Voxy Vulkan context created on device: " + this.deviceName
                    + " (drawIndirectCount=" + this.hasDrawIndirectCount + ")");
        }
    }

    private static Set<String> deviceExtensions(VkPhysicalDevice pd, MemoryStack stack) {
        IntBuffer c = stack.mallocInt(1);
        vkEnumerateDeviceExtensionProperties(pd, (String) null, c, null);
        var props = VkExtensionProperties.calloc(c.get(0), stack);
        vkEnumerateDeviceExtensionProperties(pd, (String) null, c, props);
        Set<String> out = new HashSet<>();
        for (int i = 0; i < props.capacity(); i++) out.add(props.get(i).extensionNameString());
        return out;
    }

    private static boolean hasAll(Set<String> have, String[] want) {
        for (String w : want) if (!have.contains(w)) return false;
        return true;
    }

    private static int findGraphicsQueueFamily(VkPhysicalDevice pd, MemoryStack stack) {
        IntBuffer c = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(pd, c, null);
        var fams = VkQueueFamilyProperties.calloc(c.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(pd, c, fams);
        for (int i = 0; i < fams.capacity(); i++) {
            if ((fams.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) return i;
        }
        return -1;
    }

    public int findMemoryType(int typeBits, int required) {
        try (MemoryStack stack = stackPush()) {
            var mem = VkPhysicalDeviceMemoryProperties.calloc(stack);
            vkGetPhysicalDeviceMemoryProperties(this.physicalDevice, mem);
            for (int i = 0; i < mem.memoryTypeCount(); i++) {
                if ((typeBits & (1 << i)) != 0 && (mem.memoryTypes(i).propertyFlags() & required) == required) return i;
            }
        }
        throw new IllegalStateException("No suitable VK memory type");
    }

    public void destroy() {
        vkDeviceWaitIdle(this.device);
        vkDestroyCommandPool(this.device, this.commandPool, null);
        vkDestroyDevice(this.device, null);
        vkDestroyInstance(this.instance, null);
    }
}
