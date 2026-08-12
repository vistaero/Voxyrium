package me.cortex.voxy.client.core.vk.render;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.vk.VkFrameCtx;
import me.cortex.voxy.client.core.vk.VkImage2D;
import me.cortex.voxy.client.core.vk.VkShaderPipeline;
import me.cortex.voxy.client.core.vk.VkShaderSource;
import org.lwjgl.system.MemoryStack;

import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

//Pure-VK HiZ pyramid: an R32F mip chain reduced with the same conservative
// REDUCTION as the GL path (min for reverse-Z), built by one small compute
// dispatch per level. Level 0 reduces from the offscreen depth image directly.
// The whole pyramid lives in GENERAL layout (written as storage image, read as
// sampled image by the traversal).
//
// When subgroup arithmetic is supported (MoltenVK/Metal, NVIDIA, AMD, Intel on
// Vulkan 1.1+), levels 1..6 are collapsed into a SINGLE dispatch by the
// subgroup reduce shader (hiz_subgroup.comp), cutting ~5 dispatches + 5
// barriers per frame at 1080p. Level 0 still uses the per-level reduce (it
// handles the non-power-of-two source/dest ratio). Any levels beyond 6 (for
// pyramids larger than 64x64 base) fall back to the per-level loop.
public class VkHiZ {
    private final VkFrameCtx ctx;
    private final VkShaderPipeline reduce;
    private final VkShaderPipeline subgroupReduce; //null when subgroup unsupported
    public final long sampler;//nearest, nearest-mip, clamped

    private VkImage2D pyramid;
    private int levels;
    private int width, height;
    private boolean initialized;

    public VkHiZ(VkFrameCtx ctx, RenderProperties properties) {
        this.ctx = ctx;
        this.reduce = new VkShaderPipeline(ctx, "hiz_reduce.comp",
                VkShaderSource.load("voxy:hiz/vk/hiz_reduce.comp", VkShaderSource.defs().props(properties).build()),
                16,
                List.of(VkShaderPipeline.sampler(0), VkShaderPipeline.image(1)));
    //Subgroup reduce: 7 bindings (mip_0 sampler + mip_1..mip_6 storage images).
    //Only built when the device supports subgroup arithmetic AND the pyramid
    // will have >= 7 levels.
        if (ctx.vk().subgroupArithmetic) {
            this.subgroupReduce = new VkShaderPipeline(ctx, "hiz_subgroup.comp",
                    VkShaderSource.load("voxy:hiz/vk/hiz_subgroup.comp", VkShaderSource.defs().props(properties).build()),
                    16,
                    List.of(VkShaderPipeline.sampler(0),
                            VkShaderPipeline.image(1), VkShaderPipeline.image(2), VkShaderPipeline.image(3),
                            VkShaderPipeline.image(4), VkShaderPipeline.image(5), VkShaderPipeline.image(6)));
        } else {
            this.subgroupReduce = null;
        }
        this.sampler = VkImage2D.createSampler(ctx.vk(), true, false);
    }

    private void alloc(int width, int height) {
        if (this.pyramid != null) this.pyramid.free();
        this.levels = (int) Math.ceil(Math.log(Math.max(width, height)) / Math.log(2));
        this.levels = Math.max(this.levels, 1);
        this.pyramid = new VkImage2D(this.ctx, width, height, this.levels, VK_FORMAT_R32_SFLOAT,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, VK_IMAGE_ASPECT_COLOR_BIT, true);
        this.width = width;
        this.height = height;
        this.initialized = false;
    }

    /**
     * Rebuild the pyramid from the offscreen depth image (must currently be in
     * SHADER_READ_ONLY_OPTIMAL with a DEPTH-aspect sampling view).
     */
    public void buildMipChain(long depthSampleView, int srcWidth, int srcHeight) {
        int w = Integer.highestOneBit(srcWidth);
        int h = Integer.highestOneBit(srcHeight);
        if (this.width != w || this.height != h || this.pyramid == null) {
            this.alloc(w, h);
        }
        var cmd = this.ctx.cmd();
        if (!this.initialized) {
            this.pyramid.transition(VK_IMAGE_LAYOUT_GENERAL,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT);
            this.initialized = true;
        }

        //Level 0: always the per-level reduce (handles non-power-of-two source ratio).
        this.reduce.bind(cmd);
        int cw = this.width, ch = this.height;
        int sw = srcWidth, sh = srcHeight;
        {
            try (var binder = this.reduce.binder()) {
                binder.sampler(0, depthSampleView, this.sampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                        .image(1, this.pyramid.mipViews[0])
                        .push(cmd);
            }
            try (MemoryStack stack = stackPush()) {
                var pc = stack.malloc(16);
                pc.putInt(0, cw).putInt(4, ch).putInt(8, sw).putInt(12, sh);
                this.reduce.pushConstants(cmd, pc);
            }
            vkCmdDispatch(cmd, (cw + 7) / 8, (ch + 7) / 8, 1);
        }
        //Level 0 write -> level 1 read
        this.ctx.barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
        sw = cw; sh = ch;
        cw = Math.max(cw / 2, 1);
        ch = Math.max(ch / 2, 1);

        //Levels 1..6: single subgroup dispatch when available and pyramid has enough levels.
        //The subgroup shader reduces 64x64 tiles of mip_0 to mip_1..mip_6 in one dispatch.
        int subgroupEndLevel = 0;
        if (this.subgroupReduce != null && this.levels >= 7) {
            this.subgroupReduce.bind(cmd);
            try (var binder = this.subgroupReduce.binder()) {
                binder.sampler(0, this.pyramid.mipViews[0], this.sampler, VK_IMAGE_LAYOUT_GENERAL)
                        .image(1, this.pyramid.mipViews[1])
                        .image(2, this.pyramid.mipViews[2])
                        .image(3, this.pyramid.mipViews[3])
                        .image(4, this.pyramid.mipViews[4])
                        .image(5, this.pyramid.mipViews[5])
                        .image(6, this.pyramid.mipViews[6])
                        .push(cmd);
            }
            try (MemoryStack stack = stackPush()) {
                var pc = stack.malloc(16);
                pc.putFloat(0, 1.0f / this.width).putFloat(4, 1.0f / this.height)
                        .putInt(8, this.levels).putInt(12, 0);
                this.subgroupReduce.pushConstants(cmd, pc);
            }
            //One dispatch per 64x64 tile of the pyramid base.
            vkCmdDispatch(cmd, (this.width + 63) / 64, (this.height + 63) / 64, 1);
            //mip_1..mip_6 writes -> subsequent level reads
            this.ctx.barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            subgroupEndLevel = 6;
            //Advance cw/ch past the subgroup-covered levels for any remaining per-level loop.
            cw = Math.max(this.width >> 6, 1);
            ch = Math.max(this.height >> 6, 1);
            sw = Math.max(this.width >> 5, 1);
            sh = Math.max(this.height >> 5, 1);
        }

        //Remaining levels (7+): per-level reduce loop.
        for (int i = subgroupEndLevel + 1; i < this.levels; i++) {
            this.reduce.bind(cmd);
            try (var binder = this.reduce.binder()) {
                long srcView = this.pyramid.mipViews[i - 1];
                binder.sampler(0, srcView, this.sampler, VK_IMAGE_LAYOUT_GENERAL)
                        .image(1, this.pyramid.mipViews[i])
                        .push(cmd);
            }
            try (MemoryStack stack = stackPush()) {
                var pc = stack.malloc(16);
                pc.putInt(0, cw).putInt(4, ch).putInt(8, sw).putInt(12, sh);
                this.reduce.pushConstants(cmd, pc);
            }
            vkCmdDispatch(cmd, (cw + 7) / 8, (ch + 7) / 8, 1);
            this.ctx.barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
            sw = cw; sh = ch;
            cw = Math.max(cw / 2, 1);
            ch = Math.max(ch / 2, 1);
        }

        //Pyramid -> traversal sampling
        this.ctx.barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
    }

    public long pyramidView() {
        return this.pyramid.view;
    }

    public int getPackedLevels() {
        return (this.width << 16) | this.height;
    }

    public void free() {
        if (this.pyramid != null) this.pyramid.free();
        //sampler comes from VkImage2D.createSampler's device-lifetime cache (shared
        // handle); never destroy it per-object (multi-free vkDestroySampler -> SIGSEGV).
        this.reduce.free();
        if (this.subgroupReduce != null) this.subgroupReduce.free();
    }
}
