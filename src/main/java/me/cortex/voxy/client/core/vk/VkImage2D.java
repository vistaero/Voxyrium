package me.cortex.voxy.client.core.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageFormatListCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import static me.cortex.voxy.client.core.vk.VkUtil.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

//2D image + full view (+ optional per-mip views) for the pure-VK path:
// offscreen colour/depth targets, the HiZ mip pyramid, and the model atlas.
// Tracks the current layout for whole-image transitions (Voxy transitions
// whole subresource ranges only, keeping parity with the GL path's coarse
// barrier usage).
public final class VkImage2D {
    private final VkFrameCtx ctx;
    public final long image;
    public final long memory;
    public final long view;
    public final long[] mipViews;//null unless requested
    public final int width, height, mipLevels;
    public final int format;
    public final int aspect;
    private int currentLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    public VkImage2D(VkFrameCtx ctx, int width, int height, int mipLevels, int format, int usage, int aspect, boolean perMipViews) {
        this(ctx, width, height, mipLevels, format, usage, aspect, perMipViews, null);
    }

    //Creates a 2D image with optional VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT + a
    // VkImageFormatListCreateInfo listing the formats the image will be viewed
    // as. Required for sampling a depth-only aspect of a packed D32_SFLOAT_S8_UINT
    // image on MoltenVK (so the depth-only view aliases the image without a
    // separate staging texture). viewFormats may be null (no mutable-format
    // flag, classic path).
    public VkImage2D(VkFrameCtx ctx, int width, int height, int mipLevels, int format, int usage, int aspect,
                     boolean perMipViews, int[] viewFormats) {
        this.ctx = ctx;
        this.width = width;
        this.height = height;
        this.mipLevels = mipLevels;
        this.format = format;
        this.aspect = aspect;
        var vctx = ctx.vk();
        try (MemoryStack stack = stackPush()) {
            var ici = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(format)
                    .extent(e -> e.width(width).height(height).depth(1))
                    .mipLevels(mipLevels).arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            if (viewFormats != null && viewFormats.length > 0) {
                ici.flags(VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT);
                var formatList = VkImageFormatListCreateInfo.calloc(stack).sType$Default()
                        .pViewFormats(stack.ints(viewFormats));
                ici.pNext(formatList.address());
            }
            var pImg = stack.mallocLong(1);
            check(vkCreateImage(vctx.device, ici, null, pImg), "vkCreateImage");
            this.image = pImg.get(0);

            var req = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(vctx.device, this.image, req);
            var mai = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .allocationSize(req.size())
                    .memoryTypeIndex(vctx.findMemoryType(req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            var pMem = stack.mallocLong(1);
            check(vkAllocateMemory(vctx.device, mai, null, pMem), "vkAllocateMemory(image)");
            this.memory = pMem.get(0);
            check(vkBindImageMemory(vctx.device, this.image, this.memory, 0), "vkBindImageMemory");

            this.view = createView(stack, vctx, 0, mipLevels);
            if (perMipViews) {
                this.mipViews = new long[mipLevels];
                for (int i = 0; i < mipLevels; i++) {
                    this.mipViews[i] = createView(stack, vctx, i, 1);
                }
            } else {
                this.mipViews = null;
            }
        }
    }

    private long createView(MemoryStack stack, VulkanContext vctx, int baseMip, int mipCount) {
        var vci = VkImageViewCreateInfo.calloc(stack).sType$Default()
                .image(this.image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(this.format);
        vci.subresourceRange().aspectMask(this.aspect).baseMipLevel(baseMip).levelCount(mipCount).baseArrayLayer(0).layerCount(1);
        var pView = stack.mallocLong(1);
        check(vkCreateImageView(vctx.device, vci, null, pView), "vkCreateImageView");
        return pView.get(0);
    }

    /** Whole-image layout transition. UNDEFINED-layout images get TOP_OF_PIPE/0
     *  (no prior producer to synchronize — contents are discarded). */
    public void transition(int newLayout, int srcStage, int srcAccess, int dstStage, int dstAccess) {
        if (this.currentLayout == VK_IMAGE_LAYOUT_UNDEFINED) {
            srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            srcAccess = 0;
        }
        try (MemoryStack stack = stackPush()) {
            var imb = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(srcAccess).dstAccessMask(dstAccess)
                    .oldLayout(this.currentLayout).newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(this.image);
            imb.subresourceRange().aspectMask(this.aspect).levelCount(this.mipLevels).layerCount(1);
            vkCmdPipelineBarrier(this.ctx.cmd(), srcStage, dstStage, 0, null, null, imb);
            this.currentLayout = newLayout;
        }
    }

    //Batched transition: records multiple images' layout changes in a single
    // vkCmdPipelineBarrier. UNDEFINED-layout images get TOP_OF_PIPE/0 (see transition()).
    public record BatchEntry(VkImage2D image, int newLayout, int srcAccess, int dstAccess) {}
    public static void transitionBatch(java.util.List<BatchEntry> entries, int unionSrcStage, int unionDstStage) {
        if (entries.isEmpty()) return;
        boolean anyUndefined = false;
        try (MemoryStack stack = stackPush()) {
            var imbs = VkImageMemoryBarrier.calloc(entries.size(), stack);
            for (int i = 0; i < entries.size(); i++) {
                var e = entries.get(i);
                boolean undef = e.image.currentLayout == VK_IMAGE_LAYOUT_UNDEFINED;
                if (undef) anyUndefined = true;
                imbs.get(i).sType$Default()
                        .srcAccessMask(undef ? 0 : e.srcAccess).dstAccessMask(e.dstAccess)
                        .oldLayout(e.image.currentLayout).newLayout(e.newLayout)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(e.image.image)
                        .subresourceRange().aspectMask(e.image.aspect).levelCount(e.image.mipLevels).layerCount(1);
            }
            var cmd = entries.get(0).image.ctx.cmd();
            int actualSrcStage = anyUndefined ? unionSrcStage | VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT : unionSrcStage;
            vkCmdPipelineBarrier(cmd, actualSrcStage, unionDstStage, 0, null, null, imbs);
            for (var e : entries) e.image.currentLayout = e.newLayout;
        }
    }

    private final java.util.ArrayList<Long> extraViews = new java.util.ArrayList<>();

    /** Additional full-image view with a different aspect (e.g. DEPTH-only sampling view of a depth-stencil image). */
    public long createAspectView(int viewAspect) {
        try (MemoryStack stack = stackPush()) {
            var vci = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(this.image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(this.format);
            vci.subresourceRange().aspectMask(viewAspect).baseMipLevel(0).levelCount(this.mipLevels).baseArrayLayer(0).layerCount(1);
            var pView = stack.mallocLong(1);
            check(vkCreateImageView(this.ctx.vk().device, vci, null, pView), "vkCreateImageView(aspect)");
            long view = pView.get(0);
            this.extraViews.add(view);
            return view;
        }
    }

    public void free() {
        if (this.mipViews != null) {
            for (long v : this.mipViews) {
                this.ctx.deferDestroyImage(0, v, 0);
            }
        }
        for (long v : this.extraViews) {
            this.ctx.deferDestroyImage(0, v, 0);
        }
        this.ctx.deferDestroyImage(this.image, this.view, this.memory);
    }

    /** Simple sampler factory (nearest/clamped or nearest-mipmap for HiZ etc).
     *  Cached by (mipmapNearest, linear) so the ~9 call sites across the renderer
     *  share ~4 sampler handles instead of creating one each. */
    private static final java.util.Map<Long, Long> SAMPLER_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    public static long createSampler(VulkanContext ctx, boolean mipmapNearest, boolean linear) {
        long key = ctx.device.address() ^ (mipmapNearest ? 1L : 0L) ^ (linear ? 2L : 0L);
        Long cached = SAMPLER_CACHE.get(key);
        if (cached != null) return cached;
        try (MemoryStack stack = stackPush()) {
            var sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(linear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST)
                    .minFilter(linear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .minLod(0).maxLod(mipmapNearest ? VK_LOD_CLAMP_NONE : 0.25f);
            var pSampler = stack.mallocLong(1);
            check(vkCreateSampler(ctx.device, sci, null, pSampler), "vkCreateSampler");
            long handle = pSampler.get(0);
            SAMPLER_CACHE.put(key, handle);
            return handle;
        }
    }
}
