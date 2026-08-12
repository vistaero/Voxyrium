package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkViewport;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Small shared Vulkan command/setup helpers reused across the render subsystems. */
public final class VkCmd {
    private VkCmd() {}

    /** Full-frame dynamic viewport + scissor covering [0,0]..[width,height]. */
    public static void setViewportScissor(VkCommandBuffer cmd, int width, int height) {
        try (MemoryStack stack = stackPush()) {
            var vp = VkViewport.calloc(1, stack).x(0).y(0).width(width).height(height).minDepth(0).maxDepth(1);
            vkCmdSetViewport(cmd, 0, vp);
            var sc = VkRect2D.calloc(1, stack);
            sc.extent(e -> e.width(width).height(height));
            vkCmdSetScissor(cmd, 0, sc);
        }
    }

    /** The reverse-Z-aware "closer or equal" depth compare op the terrain/composite passes use. */
    public static int closerEqual(RenderProperties properties) {
        return properties.isReverseZ() ? VK_COMPARE_OP_GREATER_OR_EQUAL : VK_COMPARE_OP_LESS_OR_EQUAL;
    }

    /** Expands the shared u8 cube index buffer (36 indices) to u16 at {@code dst}. */
    public static void writeCubeIndicesU16(long dst) {
        var cube = SharedIndexBuffer.generateCubeIndexBuffer();//u8 source
        for (int i = 0; i < 6 * 2 * 3; i++) {
            MemoryUtil.memPutShort(dst + i * 2L, (short) (MemoryUtil.memGetByte(cube.address + i) & 0xFF));
        }
        cube.free();
    }
}
