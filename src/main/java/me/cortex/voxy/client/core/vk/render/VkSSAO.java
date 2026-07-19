package me.cortex.voxy.client.core.vk.render;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.SSAO;
import me.cortex.voxy.client.core.vk.VkBuffer;
import me.cortex.voxy.client.core.vk.VkFrameCtx;
import me.cortex.voxy.client.core.vk.VkImage2D;
import me.cortex.voxy.client.core.vk.VkShaderPipeline;
import me.cortex.voxy.client.core.vk.VkShaderSource;
import me.cortex.voxy.client.core.vk.VkUploadStream;
import me.cortex.voxy.common.Logger;
import org.joml.Matrix4f;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

//Pure-VK port of the GL SSAO pass (post/ssao.comp): between the temporal and
// translucent draws, a compute dispatch reads the opaque LOD colour+depth and
// writes the AO-modulated colour into viewport.colourSSAO. Beyond the AO itself
// this pass is load-bearing for compositing: the raw colour target carries
// per-pixel METADATA in its alpha channel, and this pass rewrites alpha to 1
// (LOD present) / 0 (empty), which is what the composite blend expects.
//
// The BETTER/BEST modes additionally sample MC's own depth attachment (for AO
// across the vanilla/LOD seam), transitioned around the dispatch. AUTO mode is
// resolved from the device-local heap size (VK always exposes heap sizes,
// unlike the GL Capabilities memory query).
public class VkSSAO {
    private final VkFrameCtx ctx;
    private final VkUploadStream uploadStream;

    private final boolean isBetterSSAO;
    private final int spp;
    private final VkShaderPipeline pipeline;
    private final VkBuffer params;
    private final long colourSampler;
    private final long depthSampler;
    private final Matrix4f invScratch = new Matrix4f();//per-frame matrix inversion scratch (avoids a heap alloc each compute())

    public VkSSAO(VkFrameCtx ctx, VkUploadStream uploadStream, RenderProperties properties, SSAO.SSAOMode mode) {
        this.ctx = ctx;
        this.uploadStream = uploadStream;

        mode = resolveAuto(ctx, mode);
        this.isBetterSSAO = mode != SSAO.SSAOMode.BASIC;
        this.spp = switch (mode) {
            case BETTER -> 12;
            case BEST -> 24;
            default -> 0;
        };
        Logger.info("Voxy VK SSAO mode: " + mode);

        var defs = VkShaderSource.defs().props(properties);
        if (this.isBetterSSAO) {
            defs.def("BETTER_SSAO").def("SSAO_STEPS", this.spp).def("USE_GENERATED_SAMPLE_POINTS");
        }
        String src = VkShaderSource.load("voxy:post/ssao.comp", defs.build());
        if (this.isBetterSSAO) {
            //Same compile-time sample disk the GL builder splices in via replace()
            src = src.replace("%%CONST_ARRAY%%", generateSamplePoints(this.spp));
        }

        var bindings = this.isBetterSSAO
                ? List.of(VkShaderPipeline.image(0), VkShaderPipeline.sampler(1), VkShaderPipeline.sampler(2),
                        VkShaderPipeline.sampler(3), VkShaderPipeline.ubo(4))
                : List.of(VkShaderPipeline.image(0), VkShaderPipeline.sampler(1), VkShaderPipeline.sampler(2),
                        VkShaderPipeline.ubo(4));
        this.pipeline = new VkShaderPipeline(ctx, "ssao.comp", src, 0, bindings);

        this.params = new VkBuffer(ctx, 256).zero();
        ctx.flushImmediate();
        this.colourSampler = VkImage2D.createSampler(ctx.vk(), false, false);//nearest (GL colourTex is NEAREST)
        //GL: better -> NEAREST(+mip), basic -> LINEAR; our targets are single-mip
        this.depthSampler = VkImage2D.createSampler(ctx.vk(), false, !this.isBetterSSAO);
    }

    private static SSAO.SSAOMode resolveAuto(VkFrameCtx ctx, SSAO.SSAOMode mode) {
        if (mode != SSAO.SSAOMode.AUTO) return mode;
        //Budget-based heuristic: pick the highest tier whose sample count fits a
        // per-frame sample budget = screenPixels * spp. Downgrades high-resolution
        // devices (more pixels = more samples at the same spp) and unified-memory
        // Macs (where the previous heap-size heuristic always picked BEST because
        // the "device-local heap" is the whole RAM).
        long w = net.minecraft.client.Minecraft.getInstance().getWindow().getWidth();
        long h = net.minecraft.client.Minecraft.getInstance().getWindow().getHeight();
        long pixels = Math.max(1, w * h);
        //BEST = 24 spp, BETTER = 12 spp; budget thresholds in samples/frame.
        if (pixels * 24 < 50_000_000L) return SSAO.SSAOMode.BEST;
        if (pixels * 12 < 150_000_000L) return SSAO.SSAOMode.BETTER;
        return SSAO.SSAOMode.BASIC;
    }

    private static String generateSamplePoints(int samples) {
        var array = new StringBuilder();
        for (int i = 0; i < samples; i++) {
            array.append("vec2(");
            float a = (((float) i) + 0.5f) * (1.0f / samples);
            float base = (float) (i * (1.0 / 1.6180339887) + 0.5);
            float r = (float) Math.sqrt(base % 1);
            float theta = a * 6.2831853f;
            array.append((float) (r * Math.cos(theta))).append("f, ")
                    .append((float) (r * Math.sin(theta))).append("f)");
            if (i != samples - 1) array.append(", ");
        }
        return array.toString();
    }

    /**
     * Records the SSAO dispatch: colour+depth (and MC depth for BETTER) sampled,
     * colourSSAO written. Leaves colourSSAO in COLOR_ATTACHMENT layout for the
     * translucent pass and the offscreen depth back in attachment layout.
     */
    public void compute(VkViewport viewport, VkCompositor.VkViewportRT rt) {
        var cmd = this.ctx.cmd();

        {//params UBO: BASIC = MVP,invMVP; BETTER = Proj,invProj,MV,sourceInvProj
            long ptr = this.uploadStream.upload(this.params, 0, 256);
            var scratch = this.invScratch;
            if (this.isBetterSSAO) {
                viewport.projection.getToAddress(ptr);
                viewport.projection.invert(scratch).getToAddress(ptr + 64);
                viewport.modelView.getToAddress(ptr + 128);
                viewport.vanillaProjection.invert(scratch).getToAddress(ptr + 192);
            } else {
                viewport.MVP.getToAddress(ptr);
                viewport.MVP.invert(scratch).getToAddress(ptr + 64);
            }
            this.uploadStream.commit();
        }
        this.ctx.barrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_UNIFORM_READ_BIT | VK_ACCESS_SHADER_READ_BIT);

        //opaque+temporal colour -> sampled; offscreen depth -> sampled; SSAO target -> storage
        viewport.colour.transition(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        viewport.depthStencil.transition(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT, VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        viewport.colourSSAO.transition(VK_IMAGE_LAYOUT_GENERAL,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT);
        if (this.isBetterSSAO) {
            VkFrameHost.transitionMcImage(cmd, rt.mcDepth(), true,
                    VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }

        this.pipeline.bind(cmd);
        try (var b = this.pipeline.binder()) {
            b.image(0, viewport.colourSSAO.view)
                    .sampler(1, viewport.colour.view, this.colourSampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .sampler(2, viewport.depthSampleView, this.depthSampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            if (this.isBetterSSAO) {
                b.sampler(3, VkFrameHost.vkView(rt.mcDepth()), this.depthSampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
            b.ubo(4, this.params).push(cmd);
        }
        vkCmdDispatch(cmd, (viewport.width + 7) / 8, (viewport.height + 7) / 8, 1);

        if (this.isBetterSSAO) {
            VkFrameHost.transitionMcImage(cmd, rt.mcDepth(), true,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        }
        //SSAO output -> colour attachment for the translucent pass
        viewport.colourSSAO.transition(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
        //offscreen depth back to attachment for the translucent pass
        viewport.depthStencil.transition(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
    }

    public void addDebugInfo(List<String> debugLines) {
        debugLines.add("SSAO(VK): " + (this.isBetterSSAO ? ("new (" + this.spp + " spp)") : "basic"));
    }

    public void free() {
        this.pipeline.free();
        this.params.free();
        //colourSampler/depthSampler come from VkImage2D.createSampler's device-lifetime
        // cache (shared handles); never destroy them per-object (multi-free -> SIGSEGV).
    }
}
