package me.cortex.voxy.client.core.vk.render;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.core.vk.VkBuffer;
import me.cortex.voxy.client.core.vk.VkCmd;
import me.cortex.voxy.client.core.vk.VkDownloadStream;
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

//Pure-VK mirror of MDICSectionRenderer: the same six GPU passes (prep,
// raster-cull, command generation, translucency prefix sort + build, then
// opaque / temporal / translucent indexed-indirect-count draws) against the
// same buffer layouts, recorded into MC's frame command buffer with dynamic
// rendering over Voxy's offscreen colour + depth-stencil targets.
public class VkTerrainRenderer {
    //Draw-command offsets shared with the cmdgen/translucent shader defines.
    private static final int TRANSLUCENT_OFFSET = VkViewport.OPAQUE_DRAW_COUNT;
    private static final int TEMPORAL_OFFSET = TRANSLUCENT_OFFSET + VkViewport.TRANSLUCENT_DRAW_COUNT;

    private final VkFrameCtx ctx;
    private final VkUploadStream uploadStream;
    private final VkDownloadStream downloadStream;
    private final RenderProperties properties;
    private final VkSectionGeometryData geometry;
    private final VkModelStore modelStore;

    //MoltenVK fixed-count fallback state: the last-known REAL per-pass draw counts,
    // read back from drawCountCallBuffer each frame. On desktop the GPU sources
    // these counts itself via vkCmdDrawIndexedIndirectCount. Initialised to the
    // per-pass caps so the first few frames — before the first readback lands —
    // behave like the old section-count fallback, then converge to the true
    // visible counts.
    private int fbOpaqueDraws = VkViewport.OPAQUE_DRAW_COUNT;
    private int fbTranslucentDraws = VkViewport.TRANSLUCENT_DRAW_COUNT;
    private int fbTemporalDraws = VkViewport.TEMPORAL_DRAW_COUNT;

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

    public VkTerrainRenderer(VkFrameCtx ctx, VkUploadStream uploadStream, VkDownloadStream downloadStream,
                             RenderProperties properties, VkSectionGeometryData geometry, VkModelStore modelStore) {
        this.ctx = ctx;
        this.uploadStream = uploadStream;
        this.downloadStream = downloadStream;
        this.properties = properties;
        this.geometry = geometry;
        this.modelStore = modelStore;

        this.uniform = new VkBuffer(ctx, 1024).zero();
        this.distanceCountBuffer = new VkBuffer(ctx, 1024L * 4 + VkViewport.TRANSLUCENT_DRAW_COUNT * 4L).zero();

        //Shared index buffer: u16 quad pattern + u16 cube indices at CUBE_INDEX_OFFSET
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

        //================= compute pipelines =================
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

        //Subgroup prefix sum on VK when the device advertises subgroup arithmetic
        // (MoltenVK/Metal simdgroups, NVIDIA, AMD, Intel — virtually every VK 1.1+
        // device). Falls back to the shared-memory Hillis-Steele scan otherwise.
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

        //================= raster cull pipeline (depth-only, no writes) =================
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
        opaque.stencilTestEqual1 = true;//only render where vanilla terrain is absent
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

    //==================================================================================

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

    /** Mirrors MDIC buildDrawCalls: prep -> raster cull -> cmdgen -> translucency sort. */
    public void buildDrawCalls(VkViewport viewport) {
        if (this.geometry.getSectionCount() == 0) return;
        var cmd = this.ctx.cmd();
        this.uploadUniform(viewport);

        if (!this.ctx.vk().hasDrawIndirectCount) {
            //Fixed-count fallback (MoltenVK): renderTerrain issues
            // vkCmdDrawIndexedIndirect with maxDrawCount rather than a GPU-sourced
            // count, so trailing slots past the actual command count would be read
            // as stale draw commands from the previous frame. Zero the three
            // drawCallBuffer slices (opaque / temporal / translucent) here so any
            // un-overwritten slot reads as instanceCount=0 (a no-op draw). The
            // cmdgen compute below then writes the real commands on top; the fill
            // completes (with a barrier) before the cmdgen dispatch reads the buffer.
            // Gated to the fallback path only — the tight-count path (desktop Vulkan)
            // never reads past the actual count, so it pays nothing here.
            long stride = 5L * 4;
            vkCmdFillBuffer(cmd, viewport.drawCallBuffer.buffer, 0L, VkViewport.OPAQUE_DRAW_COUNT * stride, 0);
            vkCmdFillBuffer(cmd, viewport.drawCallBuffer.buffer, TEMPORAL_OFFSET * stride, VkViewport.TEMPORAL_DRAW_COUNT * stride, 0);
            vkCmdFillBuffer(cmd, viewport.drawCallBuffer.buffer, TRANSLUCENT_OFFSET * stride, VkViewport.TRANSLUCENT_DRAW_COUNT * stride, 0);
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
            //prep (compute) wrote the cull indirect args; the raster-cull draw
            // reads them. Scope to COMPUTE -> DRAW_INDIRECT|VERTEX_INPUT.
            this.ctx.computeToDrawBarrier();
        }

        {//raster occlusion test into the visibility buffer (depth-tested box draw, no writes)
            this.beginRendering(cmd, viewport, 0L, true);//depth-only
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
            //The raster-cull draw wrote visibilityData (SSBO) from the fragment
            // shader; the consumer is the cmdgen compute. Scope to those stages
            // instead of the previous fullBarrier (ALL_COMMANDS -> ALL_COMMANDS)
            // which forced a full pipeline stall on every frame.
            this.ctx.barrier(VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        }

        {//command generation (indirect dispatch sized by prep)
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
            //cmdgen -> prefixsum: both compute. The draw that consumes these
            // indirect commands is guarded by renderTerrain's own barrier.
            this.ctx.computeToComputeBarrier();
        }

        {//translucency sorting
            this.prefixSum.bind(cmd);
            try (var b = this.prefixSum.binder()) {
                b.ssbo(0, this.distanceCountBuffer).push(cmd);
            }
            vkCmdDispatch(cmd, 1, 1, 1);
            //prefixsum -> translucentGen: both compute.
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
            //No trailing barrier here: the translucent commands written into
            // drawCallBuffer are read by renderTranslucent's renderTerrain, which
            // issues its own COMPUTE|TRANSFER -> DRAW_INDIRECT|VERTEX|FRAGMENT
            // barrier (line ~355) before drawing. The previous computeToAllBarrier
            // here was redundant with that draw barrier and serialised the GPU.
        }

        if (!this.ctx.vk().hasDrawIndirectCount) {
            //Read the three real per-pass draw counts back to the CPU so next frame's
            // fixed-count multi-draws track them instead of the worst-case section
            // cap (see fixedCountBudget). The counts live at opaque@12 / translucent@16
            // / temporal@20 in drawCountCallBuffer; download those 12 bytes on the same
            // async, event-retired path the traversal request readback already uses
            // (commit() scopes its own COMPUTE->TRANSFER->HOST barriers). The callback
            // runs on the render thread from pollRetired, so the plain field writes are
            // race-free. Desktop (drawIndirectCount) neither needs nor issues this.
            this.downloadStream.download(viewport.drawCountCallBuffer, 12, 12, (ptr, size) -> {
                this.fbOpaqueDraws = clampCount(MemoryUtil.memGetInt(ptr), VkViewport.OPAQUE_DRAW_COUNT);
                this.fbTranslucentDraws = clampCount(MemoryUtil.memGetInt(ptr + 4), VkViewport.TRANSLUCENT_DRAW_COUNT);
                this.fbTemporalDraws = clampCount(MemoryUtil.memGetInt(ptr + 8), VkViewport.TEMPORAL_DRAW_COUNT);
            });
        }
    }

    private static int clampCount(int value, int cap) {
        return value < 0 ? 0 : Math.min(value, cap);
    }

    /**
     * Draw-count upper bound for one terrain pass. On desktop Vulkan the GPU sources
     * the real count from drawCountCallBuffer (vkCmdDrawIndexedIndirectCount), so the
     * section-derived {@code cap} is only a ceiling and is returned unchanged. On
     * MoltenVK — which lacks drawIndirectCount — the fixed-count multi-draw instead
     * iterates whatever count it is handed, encoding one Metal draw per slot; handing
     * it the section-count ceiling means tens of thousands of no-op draws every frame,
     * invariant to where the camera looks (this is why sky/occlusion culling produced
     * no Mac speed-up). Bounding it to the last-known real count plus headroom lets the
     * culling actually reduce Mac draw cost. The full drawCallBuffer is still zeroed per
     * frame, so every slot within the ceiling reads either a real command or a no-op —
     * a transient under-estimate during fast camera motion only drops a few LOD draws
     * for a frame or two, never reads stale geometry.
     */
    private int fixedCountBudget(int lastKnownCount, int headroom, int cap) {
        if (this.ctx.vk().hasDrawIndirectCount) return cap;
        int budget = (int) (lastKnownCount * 1.5f) + headroom;
        return Math.min(cap, Math.max(0, budget));
    }

    public void renderOpaque(VkViewport viewport, boolean clearTargets) {
        //Build pipelines BEFORE the section-count guard: within a frame geometry is
        // uploaded after this call (nodeManager.tick/buildDrawCalls), so renderTemporal/
        // renderTranslucent can see sectionCount>0 later this same frame. If we skipped
        // ensure here when the count is momentarily 0, those calls would bind a null
        // pipeline. ensureTerrainPipelines is idempotent (no-op once built for the format).
        this.ensureTerrainPipelines(viewport);
        if (this.geometry.getSectionCount() == 0) return;
        this.uploadUniform(viewport);
        int cap = Math.min((int) (this.geometry.getSectionCount() * 4.4 + 128), VkViewport.OPAQUE_DRAW_COUNT);
        int maxDraw = this.fixedCountBudget(this.fbOpaqueDraws, 1024, cap);
        this.renderTerrain(viewport, viewport.colour.view, this.terrainOpaque, 0, 4 * 3, maxDraw, clearTargets);
    }

    public void renderTemporal(VkViewport viewport) {
        this.ensureTerrainPipelines(viewport);
        if (this.geometry.getSectionCount() == 0) return;
        int cap = Math.min(this.geometry.getSectionCount(), VkViewport.TEMPORAL_DRAW_COUNT);
        int maxDraw = this.fixedCountBudget(this.fbTemporalDraws, 256, cap);
        this.renderTerrain(viewport, viewport.colour.view, this.terrainOpaque, TEMPORAL_OFFSET * 5L * 4, 4 * 5, maxDraw, false);
    }

    /** Translucents draw onto the SSAO output (mirrors the GL fbSSAO target). */
    public void renderTranslucent(VkViewport viewport) {
        this.ensureTerrainPipelines(viewport);
        if (this.geometry.getSectionCount() == 0) return;
        int cap = Math.min(this.geometry.getSectionCount(), VkViewport.TRANSLUCENT_DRAW_COUNT);
        int maxDraw = this.fixedCountBudget(this.fbTranslucentDraws, 256, cap);
        this.renderTerrain(viewport, viewport.colourSSAO.view, this.terrainTranslucent, TRANSLUCENT_OFFSET * 5L * 4, 4 * 4, maxDraw, false);
    }

    private void renderTerrain(VkViewport viewport, long colorView, VkShaderPipeline pipeline,
                               long indirectOffset, long drawCountOffset, int maxDrawCount, boolean clear) {
        var cmd = this.ctx.cmd();
        this.ctx.barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_VERTEX_INPUT_BIT,
                VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_INDEX_READ_BIT);

        this.beginRendering(cmd, viewport, colorView, !clear);//LOAD unless first pass (which cleared via compositor setup)
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
            //Tight-count path: the GPU reads the actual draw count from the count
            // buffer each frame. Requires the drawIndirectCount Vulkan 1.2 feature
            // to be enabled on the device — desktop Vulkan (NVIDIA/AMD/Intel) enables
            // it; MoltenVK does not (see the else branch).
            vkCmdDrawIndexedIndirectCount(cmd,
                    viewport.drawCallBuffer.buffer, indirectOffset,
                    viewport.drawCountCallBuffer.buffer, drawCountOffset,
                    maxDrawCount, 5 * 4);
        } else {
            //MoltenVK/macOS fixed-count fallback: drawIndirectCount is not enabled
            // on MC's adopted Vulkan device (MC's DeviceFeatures record does not
            // even track it), and calling the function without the feature enabled
            // is invalid usage that MoltenVK degenerates. The drawCallBuffer slice
            // for this pass is zeroed per frame in buildDrawCalls (gated to this
            // fallback path) so trailing slots past the actual command count read
            // instanceCount=0 (no-op draws); a fixed-count multi-draw over the
            // clamped maxDrawCount is therefore correct, just less tight.
            vkCmdDrawIndexedIndirect(cmd, viewport.drawCallBuffer.buffer, indirectOffset, maxDrawCount, 5 * 4);
        }
        vkCmdEndRenderingKHR(cmd);
    }

    /**
     * Begin dynamic rendering over the offscreen targets (always LOAD; clears
     * happen in the depth-setup pass). {@code colorView} selects the colour
     * attachment (main colour vs SSAO output); 0 = depth-only.
     */
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
        //depthBoundSampler/lightmapSampler come from VkImage2D.createSampler's
        // device-lifetime cache (shared handles); never destroy them per-object
        // (multi-free vkDestroySampler -> SIGSEGV on world unload).
        this.uniform.free();
        this.distanceCountBuffer.free();
        this.indexBuffer.free();
    }
}
