package me.cortex.voxy.client.core.vk.render;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.rendering.bounding.IBoundStore;
import me.cortex.voxy.client.core.vk.VkBuffer;
import me.cortex.voxy.client.core.vk.VkCmd;
import me.cortex.voxy.client.core.vk.VkFrameCtx;
import me.cortex.voxy.client.core.vk.VkShaderPipeline;
import me.cortex.voxy.client.core.vk.VkShaderSource;
import me.cortex.voxy.client.core.vk.VkUploadStream;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdBeginRenderingKHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdEndRenderingKHR;
import static org.lwjgl.vulkan.VK10.*;

//Pure-VK mirror of the GL BoundRenderer: rasterizes an AABB per Sodium-visible
// chunk section into the viewport's depth-bound image with a "further" depth
// test, capturing the far bound of the vanilla-covered volume. The terrain
// fragment shader (quads.frag, sampler binding 10) then discards LOD fragments
// that vanilla terrain will cover, saving overdraw.
//
//Differences from GL, both deliberate:
//  - one 36-index box per instance instead of the 32-box batches (no
//    baseInstance arithmetic — gl_InstanceIndex is the chunk id directly);
//  - no face culling: with the further depth compare the back faces win the
//    depth test anyway, which is exactly the bound the GL backface trick kept.
public class VkBoundRenderer {
    private final VkFrameCtx ctx;
    private final VkUploadStream uploadStream;
    private final RenderProperties properties;

    private final VkBuffer uniform;
    private final VkBuffer boxIndexBuffer;//36 u16 indices, vertex ids 0..7
    private final VkShaderPipeline pipeline;

    //Per-frame uniform scratch (reused each render() to avoid heap allocs)
    private final Vector3i cameraBlock = new Vector3i();
    private final Vector3f innerBlock = new Vector3f();
    private final Matrix4f mvpScratch = new Matrix4f();

    public VkBoundRenderer(VkFrameCtx ctx, VkUploadStream uploadStream, RenderProperties properties) {
        this.ctx = ctx;
        this.uploadStream = uploadStream;
        this.properties = properties;
        this.uniform = new VkBuffer(ctx, 128).zero();

        this.boxIndexBuffer = new VkBuffer(ctx, 6 * 2 * 3 * 2L);
        {
            long ptr = uploadStream.upload(this.boxIndexBuffer, 0, this.boxIndexBuffer.size());
            VkCmd.writeCubeIndicesU16(ptr);
            uploadStream.commit();
            ctx.flushImmediate();
        }

        var d = new VkShaderPipeline.GfxDesc();
        d.name = "chunk-bounds";
        d.vertGlsl = VkShaderSource.load("voxy:chunkoutline/outline.vsh", VkShaderSource.defs().props(properties).build());
        d.fragGlsl = VkShaderSource.load("voxy:chunkoutline/outline.fsh", VkShaderSource.defs().props(properties).build());
        d.colorFormat = VK_FORMAT_UNDEFINED;
        d.depthFormat = VK_FORMAT_D32_SFLOAT;//the depth-bound image format
        d.stencilFormat = VK_FORMAT_UNDEFINED;
        d.depthTest = true;
        d.depthWrite = true;
        d.colorWrite = false;
        //"further" compare: keep the farthest fragment (the AABB back face)
        d.depthCompare = properties.isReverseZ() ? VK_COMPARE_OP_LESS : VK_COMPARE_OP_GREATER;
        d.bindings = List.of(VkShaderPipeline.ubo(0), VkShaderPipeline.ssbo(1));
        this.pipeline = new VkShaderPipeline(ctx, d);
    }

    //Records the bound raster into the frame. The depth-bound image is cleared
    // inline as LOAD_OP_CLEAR (inverseClearDepth) by this pass — the previous
    // compositor vkCmdClearDepthStencilImage + TRANSFER_DST round-trip +
    // LOAD_OP_LOAD was a redundant tile load on TBDR. With no visible sections
    // we still issue the clear-only pass so the terrain sampler sees the
    // "no bound" state.
    public void render(VkViewport viewport, IBoundStore store) {
        store.preRender(viewport);
        int count = store.getCount();

        if (count != 0) {
            {//uniform: same 128-byte layout as the GL BoundRenderer (MVP', cameraBlockPos, fract, renderDistance)
                final float renderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;//In blocks
                long ptr = this.uploadStream.upload(this.uniform, 0, 128);
                long matPtr = ptr; ptr += 4 * 4 * 4;

                int bx = (int) Math.floor(viewport.cameraX);
                int by = (int) Math.floor(viewport.cameraY);
                int bz = (int) Math.floor(viewport.cameraZ);
                this.cameraBlock.set(bx, by, bz).getToAddress(ptr); ptr += 4 * 4;

                var negInnerBlock = this.innerBlock.set(
                        (float) (viewport.cameraX - bx),
                        (float) (viewport.cameraY - by),
                        (float) (viewport.cameraZ - bz));
                negInnerBlock.getToAddress(ptr); ptr += 4 * 3;
                viewport.MVP.translate(negInnerBlock.negate(), this.mvpScratch).getToAddress(matPtr);
                MemoryUtil.memPutFloat(ptr, renderDistance);
                this.uploadStream.commit();
            }

            var cmd = this.ctx.cmd();
            //uniform/chunk-pos uploads + the store's SSBO must be visible to the draw
            this.ctx.barrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_VERTEX_INPUT_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT,
                    VK_ACCESS_INDEX_READ_BIT | VK_ACCESS_UNIFORM_READ_BIT | VK_ACCESS_SHADER_READ_BIT);
        }

        var cmd = this.ctx.cmd();
        //Transition depthBound from whatever layout the previous frame left it in
        // (SHADER_READ_ONLY_OPTIMAL after the post-render transition below, or
        // UNDEFINED on first frame) to DEPTH_STENCIL_ATTACHMENT_OPTIMAL.
        viewport.depthBound.transition(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);

        try (MemoryStack stack = stackPush()) {
            var depthAttach = org.lwjgl.vulkan.VkRenderingAttachmentInfoKHR.calloc(stack).sType$Default()
                    .imageView(viewport.depthBound.view)
                    .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)//inline clear (inverseClearDepth) — saves the transfer-clear + tile load
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            depthAttach.clearValue().depthStencil().depth(this.properties.inverseClearDepth()).stencil(0);
            var info = org.lwjgl.vulkan.VkRenderingInfoKHR.calloc(stack).sType$Default()
                    .renderArea(org.lwjgl.vulkan.VkRect2D.calloc(stack).extent(e -> e.width(viewport.width).height(viewport.height)))
                    .layerCount(1)
                    .pDepthAttachment(depthAttach);
            vkCmdBeginRenderingKHR(cmd, info);
        }
        if (count != 0) {
            this.pipeline.bind(cmd);
            VkCmd.setViewportScissor(cmd, viewport.width, viewport.height);
            try (var b = this.pipeline.binder()) {
                b.ubo(0, this.uniform)
                        .ssbo(1, (VkBuffer) store.getBuffer())
                        .push(cmd);
            }
            vkCmdBindIndexBuffer(cmd, this.boxIndexBuffer.buffer, 0, VK_INDEX_TYPE_UINT16);
            vkCmdDrawIndexed(cmd, 6 * 2 * 3, count, 0, 0, 0);
        }
        vkCmdEndRenderingKHR(cmd);

        viewport.depthBound.transition(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT, VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);

        store.postRender(viewport);
    }

    public void free() {
        this.pipeline.free();
        this.uniform.free();
        this.boxIndexBuffer.free();
    }
}
