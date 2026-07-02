package me.cortex.voxy.client.core.rendering.section.backend.vulkan;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.rendering.IRenderList;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import me.cortex.voxy.client.core.vk.SharedBuffer;
import me.cortex.voxy.client.core.vk.SharedImage;
import me.cortex.voxy.client.core.vk.SharedSemaphore;
import me.cortex.voxy.client.core.vk.VulkanBackend;
import me.cortex.voxy.client.core.vk.VulkanContext;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan analogue of MDICViewport. Identical buffer roles and sizes, but every
 * buffer the GL compute passes write AND the VK graphics queue reads is a
 * SharedBuffer (VK-allocated, GL-imported). The hierarchical traversal and the
 * MDIC-style command generation keep running in GL, bit-identically, writing
 * into these; VK consumes them for the actual draws (phase-1 hybrid).
 */
public class VulkanViewport extends Viewport<VulkanViewport> {
    private final VulkanContext ctx = VulkanBackend.context();

    public final SharedBuffer drawCountCallBuffer;
    public final SharedBuffer drawCallBuffer;
    public final SharedBuffer positionScratchBuffer;
    public final SharedBuffer indirectLookupBuffer;
    public final SharedBuffer visibilityBuffer;

    // VK render targets, GL-visible for composite. Lazily (re)created on resize.
    public SharedImage color;
    public SharedImage depth;
    public final SharedSemaphore vkDone;
    public final SharedSemaphore glDone;

    public VulkanViewport(RenderProperties properties, int maxSectionCount) {
        super(properties);
        int indirect = VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        this.drawCountCallBuffer = new SharedBuffer(this.ctx, 1024, indirect);
        this.drawCallBuffer = new SharedBuffer(this.ctx, 5L*4*(MDICSectionRenderer.OPAQUE_DRAW_COUNT
                + MDICSectionRenderer.TRANSLUCENT_DRAW_COUNT + MDICSectionRenderer.TEMPORAL_DRAW_COUNT), indirect);
        this.positionScratchBuffer = new SharedBuffer(this.ctx, 8L*400000, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        this.indirectLookupBuffer = new SharedBuffer(this.ctx,
                HierarchicalOcclusionTraverser.MAX_QUEUE_SIZE*4L+4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        this.visibilityBuffer = new SharedBuffer(this.ctx, maxSectionCount*4L, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        this.vkDone = new SharedSemaphore(this.ctx);
        this.glDone = new SharedSemaphore(this.ctx);
    }

    /** (Re)creates the shared color+depth targets if the viewport size changed. Returns true if recreated. */
    public boolean ensureTargets() {
        if (this.width <= 0 || this.height <= 0) return false;
        if (this.color != null && this.color.width == this.width && this.color.height == this.height) return false;
        if (this.color != null) { this.color.free(); this.depth.free(); }
        this.color = new SharedImage(this.ctx, this.width, this.height,
                VK_FORMAT_R8G8B8A8_UNORM, org.lwjgl.opengl.GL11.GL_RGBA8,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
        this.depth = new SharedImage(this.ctx, this.width, this.height,
                VK_FORMAT_D32_SFLOAT, org.lwjgl.opengl.GL30.GL_DEPTH_COMPONENT32F,
                VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, VK_IMAGE_ASPECT_DEPTH_BIT);
        return true;
    }

    @Override
    protected void delete0() {
        super.delete0();
        if (this.color != null) { this.color.free(); this.depth.free(); }
        this.vkDone.free(); this.glDone.free();
        this.visibilityBuffer.free();
        this.indirectLookupBuffer.free();
        this.drawCountCallBuffer.free();
        this.drawCallBuffer.free();
        this.positionScratchBuffer.free();
    }

    @Override
    public IRenderList getRenderList() {
        return this.indirectLookupBuffer;
    }
}
