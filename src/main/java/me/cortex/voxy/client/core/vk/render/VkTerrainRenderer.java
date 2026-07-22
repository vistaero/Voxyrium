package me.cortex.voxy.client.core.vk.render;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.core.vk.VkBuffer;
import me.cortex.voxy.client.core.vk.VkCmd;
import me.cortex.voxy.client.core.vk.VkFrameCtx;
import me.cortex.voxy.client.core.vk.VkImage2D;
import me.cortex.voxy.client.core.vk.VkShaderPipeline;
import me.cortex.voxy.client.core.vk.VkShaderSource;
import me.cortex.voxy.client.core.vk.VkUploadStream;
import me.cortex.voxy.common.Logger;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfoKHR;
import org.lwjgl.vulkan.VkRenderingInfoKHR;

import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdBeginRenderingKHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdEndRenderingKHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.vkCmdDrawIndexedIndirectCount;

//Pure-VK mirror of MDICSectionRenderer.
public class VkTerrainRenderer {
    //Draw-command offsets shared with cmdgen/translucent shaders.
    private static final int TRANSLUCENT_OFFSET = VkViewport.OPAQUE_DRAW_COUNT;
    private static final int TEMPORAL_OFFSET = TRANSLUCENT_OFFSET + VkViewport.TRANSLUCENT_DRAW_COUNT;

    private final VkFrameCtx ctx;
    private final VkUploadStream uploadStream;
    private final RenderProperties properties;
    private final VkSectionGeometryData geometry;
    private final VkModelStore modelStore;

    //MoltenVK fallback: uses fixed drawCount with zeroed trailing slots as no-ops.

    private final VkBuffer uniform;
    private final VkBuffer distanceCountBuffer;
    private final VkBuffer indexBuffer;
    private final long depthBoundSampler;
    private final long lightmapSampler;

    private final VkShaderPipeline prep;
    private final VkShaderPipeline cmdGen;
    private final VkShaderPipeline prefixSum;
    private final VkShaderPipeline translucentGen;
    private final VkShaderPipeline cullRaster;
    private VkShaderPipeline terrainOpaque;
    private VkShaderPipeline terrainTranslucent;
    private int pipelineColorFormat = -1, pipelineDepthFormat = -1;

    public VkTerrainRenderer(VkFrameCtx ctx, VkUploadStream uploadStream,
                             RenderProperties properties, VkSectionGeometryData geometry, VkModelStore modelStore) {
        this.ctx = ctx;
        this.uploadStream = uploadStream;
        this.properties = properties;
        this.geometry = geometry;
        this.modelStore = modelStore;

        this.uniform = new VkBuffer(ctx, 1024).zero();
        this.distanceCountBuffer = new VkBuffer(ctx, 1024L * 4 + VkViewport.TRANSLUCENT_DRAW_COUNT * 4L).zero();

        //Shared index buffer: u16 quad + cube indices.
        this.indexBuffer = new VkBuffer(ctx, SharedIndexBuffer.CUBE_INDEX_OFFSET + 6 * 2 * 3 * 2L);
        {
            var quads = SharedIndexBuffer.generateQuadIndicesShort(16380);
            long ptr = uploadStream.upload(this.indexBuffer, 0, this.indexBuffer.size());
            quads.cpyTo(ptr);
            VkCmd.writeCubeIndicesU16(ptr + SharedIndexBuffer.CUBE_INDEX_OFFSET);
            quads.free();
            uploadStream.commit();
            ctx.flushImmediate();
        }
        this.depthBoundSampler = VkImage2D.createSampler(ctx.vk(), false, false);
        this.lightmapSampler = VkImage2D.createSampler(ctx.vk(), false, true);

        //Compute pipelines.
        this.prep = new VkShaderPipeline(ctx, "prep.comp",
                VkShaderSource.load("voxy:lod/gl46/prep.comp", VkShaderSource.defs().build()),
                0, List.of(VkShaderPipeline.ubo(0), VkShaderPipeline.ssbo(1), VkShaderPipeline.ssbo(2)));

        this.cmdGen = new VkShaderPipeline(ctx, "cmdgen.comp",
                VkShaderSource.load("voxy:lod/gl46/cmdgen.comp", VkShaderSource.defs()
                        .def("TRANSLUCENT_WRITE_BASE", 1024)
                        .def("TEMPORAL_OFFSET", TEMPORAL_OFFSET)
                        .def("TRANSLUCENT_DISTANCE_BUFFER_BINDING", 7)
                        .build()),
                0, List.of(VkShaderPipeline.ubo(0), VkShaderPipeline.ssbo(1), VkShaderPipeline.ssbo(2),
                        VkShaderPipeline.ssbo(3), VkShaderPipeline.ssbo(4), VkShaderPipeline.ssbo(5),
                        VkShaderPipeline.ssbo(6), VkShaderPipeline.ssbo(7)));

        //Subgroup prefix sum if available, else shared-memory fallback.
        boolean useSubgroup = ctx.vk().subgroupArithmetic;
        this.prefixSum = new VkShaderPipeline(ctx, "prefixsum.comp",
                VkShaderSource.load(useSubgroup ? "voxy:util/prefixsum/inital3_vk.comp" : "voxy:util/prefixsum/simple.comp",
                        VkShaderSource.defs().def("IO_BUFFER", 0).build()),
                0, List.of(VkShaderPipeline.ssbo(0)));

        this.translucentGen = new VkShaderPipeline(ctx, "buildtranslucents.comp",
                VkShaderSource.load("voxy:lod/gl46/buildtranslucents.comp", VkShaderSource.defs()
                        .def("TRANSLUCENT_WRITE_BASE", 1024)
                        .def("TRANSLUCENT_DISTANCE_BUFFER_BINDING", 5)
                        .def("TRANSLUCENT_OFFSET", TRANSLUCENT_OFFSET)
                        .build()),
                0, List.of(VkShaderPipeline.ubo(0), VkShaderPipeline.ssbo(1), VkShaderPipeline.ssbo(2),
                        VkShaderPipeline.ssbo(3), VkShaderPipeline.ssbo(4), VkShaderPipeline.ssbo(5)));

        //Raster cull pipeline (depth-only).
        var cullDesc = new VkShaderPipeline.GfxDesc();
        cullDesc.name = "cullraster";
        cullDesc.vertGlsl = VkShaderSource.load("voxy:lod/gl46/cull/raster.vert", VkShaderSource.defs().props(properties).build());
        cullDesc.fragGlsl = VkShaderSource.load("voxy:lod/gl46/cull/raster.frag", VkShaderSource.defs().props(properties).build());
        cullDesc.colorFormat = VK_FORMAT_UNDEFINED;
        cullDesc.depthFormat = VK_FORMAT_D32_SFLOAT_S8_UINT;
        cullDesc.stencilFormat = VK_FORMAT_D32_SFLOAT_S8_UINT;
        cullDesc.depthTest = true;
        cullDesc.depthWrite = false;
        cullDesc.colorWrite = false;
        cullDesc.depthCompare = VkCmd.closerEqual(this.properties);
        cullDesc.bindings = List.of(VkShaderPipeline.ubo(0), VkShaderPipeline.ssbo(1),
                VkShaderPipeline.ssbo(2), VkShaderPipeline.ssbo(3));
        this.cullRaster = new VkShaderPipeline(ctx, cullDesc);
    }

    private void ensureTerrainPipelines(VkViewport viewport) {
        int cf = viewport.colour.format;
        int df = viewport.depthStencil.format;
        if (this.terrainOpaque != null && cf == this.pipelineColorFormat && df == this.pipelineDepthFormat) return;
        if (this.terrainOpaque != null) {
            this.terrainOpaque.free();
            this.terrainTranslucent.free();
        }
        var cardinalLight = net.minecraft.client.Minecraft.getInstance().level.cardinalLighting();
        String vert = VkShaderSource.load("voxy:lod/gl46/quads3.vert", VkShaderSource.defs().props(this.properties)
                .def("NO_SHADE_FACE_TINT", cardinalLight.up())
                .def("UP_FACE_TINT", cardinalLight.up())
                .def("DOWN_FACE_TINT", cardinalLight.down())
                .def("Z_AXIS_FACE_TINT", cardinalLight.north())
                .def("X_AXIS_FACE_TINT", cardinalLight.east())
                .build());

        var bindings = List.of(VkShaderPipeline.ubo(0), VkShaderPipeline.ssbo(1), VkShaderPipeline.ssbo(3),
                VkShaderPipeline.ssbo(4), VkShaderPipeline.ssbo(5),
                VkShaderPipeline.sampler(8), VkShaderPipeline.sampler(9), VkShaderPipeline.sampler(10));

        var opaque = new VkShaderPipeline.GfxDesc();
        opaque.name = "terrain-opaque";
        opaque.vertGlsl = vert;
        opaque.fragGlsl = VkShaderSource.load("voxy:lod/gl46/quads.frag", VkShaderSource.defs().props(this.properties).build());
        opaque.colorFormat = cf;
        opaque.depthFormat = df;
        opaque.stencilFormat = df;
        opaque.depthTest = true;
        opaque.depthWrite = true;
        opaque.depthCompare = VkCmd.closerEqual(this.properties);
        opaque.blend = false;
        opaque.stencilTestEqual1 = true;
        opaque.bindings = bindings;
        this.terrainOpaque = new VkShaderPipeline(this.ctx, opaque);

        var translucent = new VkShaderPipeline.GfxDesc();
        translucent.name = "terrain-translucent";
        translucent.vertGlsl = vert;
        translucent.fragGlsl = VkShaderSource.load("voxy:lod/gl46/quads.frag", VkShaderSource.defs().props(this.properties)
                .def("TRANSLUCENT").build());
        translucent.colorFormat = cf;
        translucent.depthFormat = df;
        translucent.stencilFormat = df;
        translucent.depthTest = true;
        translucent.depthWrite = true;
        translucent.depthCompare = VkCmd.closerEqual(this.properties);
        translucent.blend = true;
        translucent.stencilTestEqual1 = true;
        translucent.bindings = bindings;
        this.terrainTranslucent = new VkShaderPipeline(this.ctx, translucent);

        this.pipelineColorFormat = cf;
        this.pipelineDepthFormat = df;
    }

    //---

    private final Matrix4f uniformScratch = new Matrix4f();
    public void uploadUniform(VkViewport viewport) {
        long ptr = this.uploadStream.upload(this.uniform, 0, 1024);
        var mat = this.uniformScratch.set(viewport.MVP);
        mat.translate(-viewport.innerTranslation.x, -viewport.innerTranslation.y, -viewport.innerTranslation.z);
        mat.getToAddress(ptr); ptr += 4 * 4 * 4;
        viewport.section.getToAddress(ptr); ptr += 4 * 3;
        if (viewport.frameId < 0) {
            Logger.error("Frame ID negative, this will cause things to break, wrapping around");
            viewport.frameId &= 0x7fffffff;
        }
        MemoryUtil.memPutInt(ptr, viewport.frameId & 0x7fffffff); ptr += 4;
        viewport.innerTranslation.getToAddress(ptr);
        this.uploadStream.commit();
    }

    /** Mirrors MDIC buildDrawCalls. */
    public void buildDrawCalls(VkViewport viewport) {
        if (this.geometry.getSectionCount() == 0) return;
        var cmd = this.ctx.cmd();
        this.uploadUniform(viewport);

        if (!this.ctx.vk().hasDrawIndirectCount) {
            //MoltenVK fallback: zero trailing slots so stale draws read as no-ops.
            long stride = 5L * 4;
            int opaqueCap = Math.min((int) (this.geometry.getSectionCount() * 4.4 + 128), VkViewport.OPAQUE_DRAW_COUNT);
            int temporalCap = Math.min(this.geometry.getSectionCount(), VkViewport.TEMPORAL_DRAW_COUNT);
            int translucentCap = Math.min(this.geometry.getSectionCount(), VkViewport.TRANSLUCENT_DRAW_COUNT);
            vkCmdFillBuffer(cmd, viewport.drawCallBuffer.buffer, 0L, opaqueCap * stride, 0);
            vkCmdFillBuffer(cmd, viewport.drawCallBuffer.buffer, TEMPORAL_OFFSET * stride, temporalCap * stride, 0);
            vkCmdFillBuffer(cmd, viewport.drawCallBuffer.buffer, TRANSLUCENT_OFFSET * stride, translucentCap * stride, 0);
            this.ctx.barrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT);
        }

        {//prep
            this.prep.bind(cmd);
            try (var b = this.prep.binder()) {
                b.ubo(0, this.uniform)
                        .ssbo(1, viewport.drawCountCallBuffer)
                        .ssbo(2, viewport.indirectLookupBuffer)
                        .push(cmd);
            }
            vkCmdDispatch(cmd, 1, 1, 1);
            //prep -> cmdgen: include COMPUTE in dst barrier (atomics need it on MoltenVK).
            this.ctx.barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT
                            | VK_PIPELINE_STAGE_VERTEX_INPUT_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
        }

        {//raster occlusion cull (depth-only)
            this.beginRendering(cmd, viewport, 0L, true);
            this.cullRaster.bind(cmd);
            VkCmd.setViewportScissor(cmd, viewport.width, viewport.height);
            try (var b = this.cullRaster.binder()) {
                b.ubo(0, this.uniform)
                        .ssbo(1, this.geometry.metadataBuffer())
                        .ssbo(2, viewport.visibilityBuffer)
                        .ssbo(3, viewport.indirectLookupBuffer)
                        .push(cmd);
            }
            vkCmdBindIndexBuffer(cmd, this.indexBuffer.buffer, 0, VK_INDEX_TYPE_UINT16);
            vkCmdDrawIndexedIndirect(cmd, viewport.drawCountCallBuffer.buffer, 6 * 4, 1, 20);
            vkCmdEndRenderingKHR(cmd);
            //Cull fragment -> cmdgen compute: scoped barrier.
            this.ctx.barrier(VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        }

        {//command generation
            vkCmdFillBuffer(cmd, this.distanceCountBuffer.buffer, 0, 1024L * 4, 0);
            this.ctx.barrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            this.cmdGen.bind(cmd);
            try (var b = this.cmdGen.binder()) {
                b.ubo(0, this.uniform)
                        .ssbo(1, viewport.drawCallBuffer)
                        .ssbo(2, viewport.drawCountCallBuffer)
                        .ssbo(3, this.geometry.metadataBuffer())
                        .ssbo(4, viewport.visibilityBuffer)
                        .ssbo(5, viewport.indirectLookupBuffer)
                        .ssbo(6, viewport.positionScratchBuffer)
                        .ssbo(7, this.distanceCountBuffer)
                        .push(cmd);
            }
            vkCmdDispatchIndirect(cmd, viewport.drawCountCallBuffer.buffer, 0);
            //cmdgen -> prefixsum: compute barrier.
            this.ctx.computeToComputeBarrier();
        }

        {//translucency sorting
            this.prefixSum.bind(cmd);
            try (var b = this.prefixSum.binder()) {
                b.ssbo(0, this.distanceCountBuffer).push(cmd);
            }
            vkCmdDispatch(cmd, 1, 1, 1);
            //prefixsum -> translucentGen: compute barrier.
            this.ctx.computeToComputeBarrier();

            this.translucentGen.bind(cmd);
            try (var b = this.translucentGen.binder()) {
                b.ubo(0, this.uniform)
                        .ssbo(1, viewport.drawCallBuffer)
                        .ssbo(2, viewport.drawCountCallBuffer)
                        .ssbo(3, this.geometry.metadataBuffer())
                        .ssbo(4, viewport.indirectLookupBuffer)
                        .ssbo(5, this.distanceCountBuffer)
                        .push(cmd);
            }
            vkCmdDispatchIndirect(cmd, viewport.drawCountCallBuffer.buffer, 0);
            //No trailing barrier: renderTerrain issues its own.
        }

    }

    public void renderOpaque(VkViewport viewport, boolean clearTargets) {
        //Ensure pipelines before section-count guard (idempotent).
        this.ensureTerrainPipelines(viewport);
        if (this.geometry.getSectionCount() == 0) return;
        this.uploadUniform(viewport);
        int maxDraw = Math.min((int) (this.geometry.getSectionCount() * 4.4 + 128), VkViewport.OPAQUE_DRAW_COUNT);
        this.renderTerrain(viewport, viewport.colour.view, this.terrainOpaque, 0, 4 * 3, maxDraw, clearTargets);
    }

    public void renderTemporal(VkViewport viewport) {
        this.ensureTerrainPipelines(viewport);
        if (this.geometry.getSectionCount() == 0) return;
        int maxDraw = Math.min(this.geometry.getSectionCount(), VkViewport.TEMPORAL_DRAW_COUNT);
        this.renderTerrain(viewport, viewport.colour.view, this.terrainOpaque, TEMPORAL_OFFSET * 5L * 4, 4 * 5, maxDraw, false);
    }

    /** Translucents draw onto SSAO output. */
    public void renderTranslucent(VkViewport viewport) {
        this.ensureTerrainPipelines(viewport);
        if (this.geometry.getSectionCount() == 0) return;
        int maxDraw = Math.min(this.geometry.getSectionCount(), VkViewport.TRANSLUCENT_DRAW_COUNT);
        this.renderTerrain(viewport, viewport.colourSSAO.view, this.terrainTranslucent, TRANSLUCENT_OFFSET * 5L * 4, 4 * 4, maxDraw, false);
    }

    private void renderTerrain(VkViewport viewport, long colorView, VkShaderPipeline pipeline,
                               long indirectOffset, long drawCountOffset, int maxDrawCount, boolean clear) {
        var cmd = this.ctx.cmd();
        this.ctx.barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_VERTEX_INPUT_BIT,
                VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_INDEX_READ_BIT);

        this.beginRendering(cmd, viewport, colorView, !clear); //LOAD unless first pass
        pipeline.bind(cmd);
        VkCmd.setViewportScissor(cmd, viewport.width, viewport.height);
        long lightmapView = VkFrameHost.lightmapView();
        try (var b = pipeline.binder()) {
            b.ubo(0, this.uniform)
                    .ssbo(1, this.geometry.geometryBuffer())
                    .ssbo(3, this.modelStore.modelBuffer)
                    .ssbo(4, this.modelStore.modelColourBuffer)
                    .ssbo(5, viewport.positionScratchBuffer)
                    .sampler(8, this.modelStore.atlas.view, this.modelStore.atlasSampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .sampler(9, lightmapView, this.lightmapSampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .sampler(10, viewport.depthBoundSampleView, this.depthBoundSampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .push(cmd);
        }
        vkCmdBindIndexBuffer(cmd, this.indexBuffer.buffer, 0, VK_INDEX_TYPE_UINT16);
        if (this.ctx.vk().hasDrawIndirectCount) {
            //Desktop: tight count from GPU buffer.
            vkCmdDrawIndexedIndirectCount(cmd,
                    viewport.drawCallBuffer.buffer, indirectOffset,
                    viewport.drawCountCallBuffer.buffer, drawCountOffset,
                    maxDrawCount, 5 * 4);
        } else {
            //MoltenVK: fixed count, trailing slots zeroed as no-ops.
            vkCmdDrawIndexedIndirect(cmd, viewport.drawCallBuffer.buffer, indirectOffset, maxDrawCount, 5 * 4);
        }
        vkCmdEndRenderingKHR(cmd);
    }

    /** Begin dynamic rendering. colorView=0 for depth-only. */
    private void beginRendering(VkCommandBuffer cmd, VkViewport viewport,
                                long colorView, boolean load) {
        try (MemoryStack stack = stackPush()) {
            var depthAttach = VkRenderingAttachmentInfoKHR.calloc(stack).sType$Default()
                    .imageView(viewport.depthStencil.view)
                    .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            var stencilAttach = VkRenderingAttachmentInfoKHR.calloc(stack).sType$Default()
                    .imageView(viewport.depthStencil.view)
                    .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            var info = VkRenderingInfoKHR.calloc(stack).sType$Default()
                    .renderArea(VkRect2D.calloc(stack).extent(e -> e.width(viewport.width).height(viewport.height)))
                    .layerCount(1)
                    .pDepthAttachment(depthAttach)
                    .pStencilAttachment(stencilAttach);
            if (colorView != 0L) {
                var colorAttach = VkRenderingAttachmentInfoKHR.calloc(1, stack).sType$Default()
                        .imageView(colorView)
                        .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                        .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                        .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                info.pColorAttachments(colorAttach);
            }
            vkCmdBeginRenderingKHR(cmd, info);
        }
    }

    public void free() {
        this.prep.free();
        this.cmdGen.free();
        this.prefixSum.free();
        this.translucentGen.free();
        this.cullRaster.free();
        if (this.terrainOpaque != null) {
            this.terrainOpaque.free();
            this.terrainTranslucent.free();
        }
        //Samplers are device-lifetime cached; don't destroy per-object.
        this.uniform.free();
        this.distanceCountBuffer.free();
        this.indexBuffer.free();
    }
}
