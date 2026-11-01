package me.cortex.voxy.client.core.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.EXTValidationFeatures.*;

/** Native image-transition smoke test. It does not substitute for a game scene
 * with actual fragment/compute sampling, descriptors and Minecraft-owned images. */
final class VulkanImageGpu {
    private static void check(int result, String action) {
        if (result != VK_SUCCESS) throw new IllegalStateException(action + " failed: " + result);
    }

    static void run() throws Exception {
        try (var stack = stackPush()) {
            var count = stack.ints(0);
            check(vkEnumerateInstanceLayerProperties(count, null), "enumerate layers");
            var layers = VkLayerProperties.calloc(count.get(0), stack);
            check(vkEnumerateInstanceLayerProperties(count, layers), "read layers");
            boolean available = false;
            for (var layer : layers) available |= layer.layerNameString().equals("VK_LAYER_KHRONOS_validation");
            if (!available) {
                System.err.println("UNAVAILABLE: VK_LAYER_KHRONOS_validation is not installed/discoverable; native validation was NOT run.");
                System.exit(2);
            }
            var callback = VkDebugUtilsMessengerCallbackEXT.create((severity, type, data, user) -> {
                String message = VkDebugUtilsMessengerCallbackDataEXT.create(data).pMessageString();
                System.err.println("VALIDATION: " + message);
                if ((severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0)
                    VulkanImageTest.require(false, "native validation error");
                return VK_FALSE;
            });
            try {
                var debug = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack).sType$Default()
                        .messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                        .messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                        .pfnUserCallback(callback);
                var validation = VkValidationFeaturesEXT.calloc(stack).sType$Default()
                        .pEnabledValidationFeatures(stack.ints(VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT))
                        .pNext(debug.address());
                var app = VkApplicationInfo.calloc(stack).sType$Default().pApplicationName(stack.UTF8("Voxy image regression")).apiVersion(VK_API_VERSION_1_0);
                var create = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app)
                        .ppEnabledLayerNames(stack.pointers(stack.UTF8("VK_LAYER_KHRONOS_validation")))
                        .ppEnabledExtensionNames(stack.pointers(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME), stack.UTF8(VK_EXT_VALIDATION_FEATURES_EXTENSION_NAME)))
                        .pNext(validation.address());
                var pointer = stack.mallocPointer(1);
                check(vkCreateInstance(create, null, pointer), "create validation instance");
                var instance = new VkInstance(pointer.get(0), create);
                long messenger = 0;
                try {
                    var out = stack.mallocLong(1);
                    check(vkCreateDebugUtilsMessengerEXT(instance, debug, null, out), "create validation messenger");
                    messenger = out.get(0);
                    System.out.println("VALIDATION_ENABLED: core + synchronization");
                    check(vkEnumeratePhysicalDevices(instance, count, null), "enumerate devices");
                    if (count.get(0) == 0) throw new IllegalStateException("No Vulkan devices available");
                    var devices = stack.mallocPointer(count.get(0));
                    check(vkEnumeratePhysicalDevices(instance, count, devices), "read devices");
                    var physical = new VkPhysicalDevice(devices.get(0), instance);
                    var properties = VkPhysicalDeviceProperties.calloc(stack);
                    vkGetPhysicalDeviceProperties(physical, properties);
                    System.out.println("DEVICE: " + properties.deviceNameString() + " type=" + properties.deviceType() + " driver=" + properties.driverVersion());
                    vkGetPhysicalDeviceQueueFamilyProperties(physical, count, null);
                    var families = VkQueueFamilyProperties.calloc(count.get(0), stack);
                    vkGetPhysicalDeviceQueueFamilyProperties(physical, count, families);
                    int family = -1;
                    for (int i = 0; i < families.capacity(); i++) {
                        if ((families.get(i).queueFlags() & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) == (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) { family = i; break; }
                    }
                    if (family < 0) throw new IllegalStateException("No graphics+compute queue");
                    var queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack).sType$Default().queueFamilyIndex(family).pQueuePriorities(stack.floats(1));
                    var deviceInfo = VkDeviceCreateInfo.calloc(stack).sType$Default().pQueueCreateInfos(queueInfo);
                    check(vkCreateDevice(physical, deviceInfo, null, pointer), "create test device");
                    var device = new VkDevice(pointer.get(0), physical, deviceInfo);
                    try {
                        vkGetDeviceQueue(device, family, 0, pointer);
                        var queue = new VkQueue(pointer.get(0), device);
                        var poolInfo = VkCommandPoolCreateInfo.calloc(stack).sType$Default().queueFamilyIndex(family);
                        check(vkCreateCommandPool(device, poolInfo, null, out), "create command pool");
                        long pool = out.get(0);
                        try {
                            boolean depthOnly = false, combined = false, colour = false;
                            for (int format : VulkanImageTest.formats()) {
                                var features = VkFormatProperties.calloc(stack);
                                vkGetPhysicalDeviceFormatProperties(physical, format, features);
                                int required = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | (VulkanImageTest.isDepth(format) ? VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT : VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT);
                                if ((features.optimalTilingFeatures() & required) != required) {
                                    System.out.println("SKIP_FORMAT: " + format + " unsupported sampled attachment");
                                    continue;
                                }
                                int aspect = VulkanImageTest.aspect(format);
                                depthOnly |= aspect == VK_IMAGE_ASPECT_DEPTH_BIT;
                                combined |= (aspect & VK_IMAGE_ASPECT_STENCIL_BIT) != 0;
                                colour |= aspect == VK_IMAGE_ASPECT_COLOR_BIT;
                                runImage(device, physical, queue, pool, format);
                            }
                            VulkanImageTest.require(depthOnly && combined && colour, "native coverage includes depth-only, depth+stencil and colour");
                        } finally { vkDestroyCommandPool(device, pool, null); }
                    } finally { vkDeviceWaitIdle(device); vkDestroyDevice(device, null); }
                } finally {
                    if (messenger != 0) vkDestroyDebugUtilsMessengerEXT(instance, messenger, null);
                    vkDestroyInstance(instance, null);
                }
            } finally { callback.free(); }
        }
    }

    private static void runImage(VkDevice device, VkPhysicalDevice physical, VkQueue queue, long pool, int format) throws Exception {
        try (MemoryStack stack = stackPush()) {
            var info = VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(format)
                    .extent(e -> e.set(16, 16, 1)).mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL).sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .usage(VK_IMAGE_USAGE_SAMPLED_BIT | (VulkanImageTest.isDepth(format) ? VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT : VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT))
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            var out = stack.mallocLong(1);
            check(vkCreateImage(device, info, null, out), "create format " + format);
            long image = out.get(0), memory = 0;
            try {
                var requirements = VkMemoryRequirements.calloc(stack);
                vkGetImageMemoryRequirements(device, image, requirements);
                var properties = VkPhysicalDeviceMemoryProperties.calloc(stack);
                vkGetPhysicalDeviceMemoryProperties(physical, properties);
                int memoryType = -1;
                for (int i = 0; i < properties.memoryTypeCount(); i++) {
                    if ((requirements.memoryTypeBits() & (1 << i)) != 0) { memoryType = i; break; }
                }
                if (memoryType < 0) throw new IllegalStateException("No image memory type");
                var allocation = VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(requirements.size()).memoryTypeIndex(memoryType);
                check(vkAllocateMemory(device, allocation, null, out), "allocate image"); memory = out.get(0);
                check(vkBindImageMemory(device, image, memory, 0), "bind image");
                var allocate = VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
                var pointer = stack.mallocPointer(1);
                check(vkAllocateCommandBuffers(device, allocate, pointer), "allocate command buffer");
                var cmd = new VkCommandBuffer(pointer.get(0), device);
                VulkanDepthSampling sampling = null;
                try {
                    check(vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(stack).sType$Default()), "begin commands");
                    boolean depth = VulkanImageTest.isDepth(format);
                    int attachment = depth ? VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL : VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
                    var initial = VkImageMemoryBarrier.calloc(1, stack).sType$Default().image(image)
                            .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VulkanImageTest.layout(attachment))
                            .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                            .dstAccessMask(depth ? VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT : VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
                    initial.subresourceRange().aspectMask(VulkanImageTest.aspect(format)).levelCount(1).layerCount(1);
                    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                            depth ? VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT : VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                            0, null, null, initial);
                    VulkanImageTest.format = format; VulkanImageTest.imageHandle = image;
                    VulkanImageTest.checkBarrier(cmd, false);
                    if (depth) VulkanImageTest.checkBarrier(cmd, true);
                    if (depth) {
                        sampling = new VulkanDepthSampling(device, physical, image, format);
                        sampling.record(cmd);
                    }
                    check(vkEndCommandBuffer(cmd), "end commands");
                    check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(cmd)), VK_NULL_HANDLE), "submit transitions");
                    check(vkQueueWaitIdle(queue), "wait transitions");
                    if (sampling != null) sampling.verify();
                    System.out.println("NATIVE_FORMAT_EXECUTED: " + format);
                } finally {
                    vkDeviceWaitIdle(device);
                    if (sampling != null) sampling.close();
                    vkFreeCommandBuffers(device, pool, cmd);
                }
            } finally {
                vkDestroyImage(device, image, null);
                if (memory != 0) vkFreeMemory(device, memory, null);
            }
        }
    }
}
