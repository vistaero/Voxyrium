package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.common.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceSubgroupProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkQueue;

import static me.cortex.voxy.client.core.vk.VkUtil.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;

//Wraps the Vulkan device Voxy renders on. Voxy never creates its own device:
// when MC 26.2 runs on its native Vulkan backend, Voxy ADOPTS the game's
// VkInstance/VkDevice/queue via IVkHost and allocates only its own command pool.
// MC owns (and destroys) the device/instance, so destroy() tears down only the
// command pool Voxy created here.
public final class VulkanContext {
    public final VkInstance instance;
    public final VkPhysicalDevice physicalDevice;
    public final VkDevice device;
    public final VkQueue queue;
    public final int queueFamily;
    public final boolean hasDrawIndirectCount;
    //MASTER SWITCH for every subgroup-dependent VK path. Keep FALSE.
    //
    //querySubgroupProperties() used to chain a properties struct into
    // vkGetPhysicalDeviceFeatures2, which silently returned all-zeroes, so
    // subgroupArithmetic was false on EVERY device and the subgroup paths were
    // dead code that had never once executed. Correcting the query turned all
    // three of them on at once:
    //
    //   VkHiZ            - hiz_subgroup.comp builds the HiZ pyramid
    //   VkTraversal      - traversal workgroup size 32 -> 64
    //   VkTerrainRenderer- subgroup prefix-sum variant for translucent sorting
    //
    //That regressed rendering on both desktop and macOS: terrain flickered with
    // only far LODs surviving, consistent with a bad HiZ pyramid feeding the
    // traversal's occlusion test. hiz_subgroup.comp is also independently
    // suspect — its comments assume a 64-wide subgroup, and its subgroupBarrier()
    // at the mip_5/mip_6 tail runs with only 4 of 256 lanes still active, which
    // SPIR-V requires to be subgroup-uniform.
    //
    //Flip to true only after validating each of the three paths on its own
    // against the non-subgroup path (ab_compare.py) at several subgroup widths.
    private static final boolean ENABLE_SUBGROUP_PATHS = false;

    public final boolean subgroupArithmetic;
    public final int subgroupSize;
    public final String deviceName;
    public final long commandPool;
    private VkPhysicalDeviceSubgroupProperties subgroupProps;

    public static VulkanContext adopt(IVkHost host) { return new VulkanContext(host); }

    private VulkanContext(IVkHost host) {
        this.instance = host.instance();
        this.physicalDevice = host.physicalDevice();
        this.device = host.device();
        this.queue = host.graphicsQueue();
        this.queueFamily = host.graphicsQueueFamily();
        this.hasDrawIndirectCount = queryDrawIndirectCount(this.physicalDevice);
        var subgroup = querySubgroupProperties(this.physicalDevice);
        this.subgroupProps = subgroup;
        this.subgroupSize = subgroup != null ? subgroup.subgroupSize() : 1;
        int ops = subgroup != null ? subgroup.supportedOperations() : 0;
        int stages = subgroup != null ? subgroup.supportedStages() : 0;
        int needOps = VK_SUBGROUP_FEATURE_ARITHMETIC_BIT | VK_SUBGROUP_FEATURE_BASIC_BIT | VK_SUBGROUP_FEATURE_CLUSTERED_BIT;
        boolean deviceSupportsSubgroups = (ops & needOps) == needOps
                && (stages & VK_SHADER_STAGE_COMPUTE_BIT) != 0
                && this.subgroupSize >= 16;
        this.subgroupArithmetic = ENABLE_SUBGROUP_PATHS && deviceSupportsSubgroups;
        String name;
        try (MemoryStack stack = stackPush()) {
            var props = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(this.physicalDevice, props);
            name = props.deviceNameString();
            var cpci = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(this.queueFamily);
            var pPool = stack.mallocLong(1);
            check(vkCreateCommandPool(this.device, cpci, null, pPool), "vkCreateCommandPool(adopted)");
            this.commandPool = pPool.get(0);
        }
        this.deviceName = name + " (MC host)";
        Logger.info("Voxy Vulkan context adopted Minecraft device: " + this.deviceName
                + " (drawIndirectCount=" + this.hasDrawIndirectCount
                + ", subgroupArithmetic=" + this.subgroupArithmetic
                + " (deviceCapable=" + deviceSupportsSubgroups + ", gate=" + ENABLE_SUBGROUP_PATHS + ")"
                + ", subgroupSize=" + this.subgroupSize + ")");
    }

    private static boolean queryDrawIndirectCount(VkPhysicalDevice pd) {
        try (MemoryStack stack = stackPush()) {
            var f12q = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
            var f2 = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(f12q);
            VK11.vkGetPhysicalDeviceFeatures2(pd, f2);
            return f12q.drawIndirectCount();
        }
    }

    private static VkPhysicalDeviceSubgroupProperties querySubgroupProperties(VkPhysicalDevice pd) {
        try (MemoryStack stack = stackPush()) {
            //VkPhysicalDeviceSubgroupProperties is a PROPERTIES struct: it extends
            // VkPhysicalDeviceProperties2, not VkPhysicalDeviceFeatures2. Chaining it
            // into vkGetPhysicalDeviceFeatures2 is invalid usage (VUID-VkPhysicalDeviceFeatures2-pNext-pNext)
            // and leaves the struct at its calloc'd zeroes, so supportedOperations
            // read back 0 and subgroup support was reported absent on EVERY device.
            var sg = VkPhysicalDeviceSubgroupProperties.calloc(stack).sType$Default();
            var p2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(sg.address());
            VK11.vkGetPhysicalDeviceProperties2(pd, p2);
            // Return a malloc'd copy so the caller can read it past the stack frame.
            var copy = VkPhysicalDeviceSubgroupProperties.malloc();
            copy.set(sg);
            return copy;
        }
    }

    private long storageAlign = -1;
    /** minStorageBufferOffsetAlignment of the physical device. */
    public long storageBufferOffsetAlignment() {
        if (this.storageAlign == -1) {
            try (MemoryStack stack = stackPush()) {
                var props = VkPhysicalDeviceProperties.calloc(stack);
                vkGetPhysicalDeviceProperties(this.physicalDevice, props);
                this.storageAlign = props.limits().minStorageBufferOffsetAlignment();
            }
        }
        return this.storageAlign;
    }

    private long uniformAlign = -1;
    /** minUniformBufferOffsetAlignment of the physical device. */
    public long uniformBufferOffsetAlignment() {
        if (this.uniformAlign == -1) {
            try (MemoryStack stack = stackPush()) {
                var props = VkPhysicalDeviceProperties.calloc(stack);
                vkGetPhysicalDeviceProperties(this.physicalDevice, props);
                this.uniformAlign = props.limits().minUniformBufferOffsetAlignment();
            }
        }
        return this.uniformAlign;
    }

    //Device memory properties are immutable for the device lifetime; cache them
    // instead of re-querying the driver per allocation. Freed in destroy().
    private VkPhysicalDeviceMemoryProperties memoryProperties;
    public int findMemoryType(int typeBits, int required) {
        if (this.memoryProperties == null) {
            this.memoryProperties = VkPhysicalDeviceMemoryProperties.malloc();
            vkGetPhysicalDeviceMemoryProperties(this.physicalDevice, this.memoryProperties);
        }
        var mem = this.memoryProperties;
        for (int i = 0; i < mem.memoryTypeCount(); i++) {
            if ((typeBits & (1 << i)) != 0 && (mem.memoryTypes(i).propertyFlags() & required) == required) return i;
        }
        throw new IllegalStateException("No suitable VK memory type");
    }

    public void destroy() {
        vkDeviceWaitIdle(this.device);
        vkDestroyCommandPool(this.device, this.commandPool, null);
        if (this.subgroupProps != null) {
            this.subgroupProps.free();
            this.subgroupProps = null;
        }
        if (this.memoryProperties != null) {
            this.memoryProperties.free();
            this.memoryProperties = null;
        }
        //Host mode: MC owns the device/instance — only the command pool above was ours.
    }
}
