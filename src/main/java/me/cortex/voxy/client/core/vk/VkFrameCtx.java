package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.common.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkEventCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkSubmitInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;

import static me.cortex.voxy.client.core.vk.VkUtil.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

//The per-frame Vulkan recording context for the pure-VK path.
//
//ALL of Voxy's GPU work is recorded into MC's live frame command buffer between
// MC's own render passes (upload copies -> compute -> raster passes -> draws ->
// readback copies), ordered with pipeline barriers. This class owns:
//
//  - the current recording target (cmd()), either MC's frame command buffer
//    (inside the render hook) or a one-shot immediate buffer (resource
//    construction outside a frame);
//  - frame completion tracking WITHOUT touching MC's encoder internals: each
//    Voxy frame ends with vkCmdSetEvent; the event provably signals only when
//    MC has submitted the frame and the GPU reached Voxy's commands. Upload /
//    download streams and deferred destruction retire on those events;
//  - deferred destruction of buffers/images still referenced by frames in
//    flight.
//
//Thread model: render thread only (MC records its VK frame on its render
// thread, and every Voxy entry point here is called from that thread).
public final class VkFrameCtx {
    public interface FrameRetireListener {
        /** Called when GPU work for frame {@code frameIdx} has provably completed. */
        void onFramesRetired(long retiredUpToInclusive);
    }

    private final VulkanContext ctx;
    private VkCommandBuffer frameCmd;      //MC's frame command buffer while inside the hook
    private VkCommandBuffer immediateCmd;  //one-shot fallback outside the hook
    private boolean anyWorkThisFrame;

    private long frameCounter = 0;         //index of the frame currently being recorded
    private long retiredCounter = -1;      //all frames <= this have completed on the GPU

    private final Deque<InFlightFrame> inFlight = new ArrayDeque<>();
    private final ArrayList<Long> eventPool = new ArrayList<>();
    private final ArrayList<PendingDestroy> pendingDestroys = new ArrayList<>();
    private final ArrayList<FrameRetireListener> retireListeners = new ArrayList<>();

    private record InFlightFrame(long frameIdx, long event) {}
    private record PendingDestroy(long frameIdx, long buffer, long image, long imageView, long memory) {}

    public VkFrameCtx(VulkanContext ctx) {
        this.ctx = ctx;
    }

    public VulkanContext vk() {
        return this.ctx;
    }

    public void addRetireListener(FrameRetireListener listener) {
        this.retireListeners.add(listener);
    }

    /** Frame index currently being recorded; work recorded now completes when this frame retires. */
    public long currentFrame() {
        return this.frameCounter;
    }

    //==================================================================================
    // Recording targets

    /** Enter the render hook: record into MC's frame command buffer. */
    public void beginFrame(VkCommandBuffer mcFrameCommandBuffer) {
        if (this.frameCmd != null) throw new IllegalStateException("Frame already begun");
        this.frameCmd = mcFrameCommandBuffer;
    }

    /** Leave the render hook: stamp the frame event and advance the frame counter. */
    public void endFrame() {
        if (this.frameCmd == null) throw new IllegalStateException("No frame begun");
        if (this.anyWorkThisFrame) {
            long event = this.obtainEvent();
            vkCmdSetEvent(this.frameCmd, event, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
            this.inFlight.add(new InFlightFrame(this.frameCounter, event));
            this.frameCounter++;
            this.anyWorkThisFrame = false;
        }
        this.frameCmd = null;
    }

    //The command buffer to record into. Inside the render hook this is MC's
    // frame command buffer; outside it, a one-shot immediate command buffer is
    // begun on demand and submitted synchronously by flushImmediate().
    public VkCommandBuffer cmd() {
        this.anyWorkThisFrame = true;
        if (this.frameCmd != null) return this.frameCmd;
        if (this.immediateCmd == null) {
            try (MemoryStack stack = stackPush()) {
                var cbai = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                        .commandPool(this.ctx.commandPool)
                        .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                        .commandBufferCount(1);
                var pCmd = stack.mallocPointer(1);
                check(vkAllocateCommandBuffers(this.ctx.device, cbai, pCmd), "vkAllocateCommandBuffers(immediate)");
                this.immediateCmd = new VkCommandBuffer(pCmd.get(0), this.ctx.device);
                var begin = VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                        .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
                check(vkBeginCommandBuffer(this.immediateCmd, begin), "vkBeginCommandBuffer(immediate)");
            }
        }
        return this.immediateCmd;
    }

    /** Submits + waits any pending immediate commands (resource init outside a frame). */
    public void flushImmediate() {
        if (this.immediateCmd == null) return;
        var cmd = this.immediateCmd;
        this.immediateCmd = null;
        try (MemoryStack stack = stackPush()) {
            check(vkEndCommandBuffer(cmd), "vkEndCommandBuffer(immediate)");
            var fci = VkFenceCreateInfo.calloc(stack).sType$Default();
            var pFence = stack.mallocLong(1);
            check(vkCreateFence(this.ctx.device, fci, null, pFence), "vkCreateFence(immediate)");
            long fence = pFence.get(0);
            var submit = VkSubmitInfo.calloc(stack).sType$Default()
                    .pCommandBuffers(stack.pointers(cmd));
            check(vkQueueSubmit(this.ctx.queue, submit, fence), "vkQueueSubmit(immediate)");
            check(vkWaitForFences(this.ctx.device, fence, true, Long.MAX_VALUE), "vkWaitForFences(immediate)");
            vkDestroyFence(this.ctx.device, fence, null);
            vkFreeCommandBuffers(this.ctx.device, this.ctx.commandPool, cmd);
        }
    }

    //==================================================================================
    // Frame retirement

    /** Poll frame events; retire completed frames (recycles staging space, runs destroys). */
    public void pollRetired() {
        boolean any = false;
        while (!this.inFlight.isEmpty()) {
            var frame = this.inFlight.peek();
            int status = vkGetEventStatus(this.ctx.device, frame.event);
            if (status != VK_EVENT_SET) break;
            this.inFlight.pop();
            check(vkResetEvent(this.ctx.device, frame.event), "vkResetEvent");
            this.eventPool.add(frame.event);
            this.retiredCounter = frame.frameIdx;
            any = true;
        }
        if (any) {
            this.runRetirement();
        }
    }

    /** Hard sync: device idle, then retire EVERYTHING (shutdown / teardown). */
    public void waitIdleRetireAll() {
        this.flushImmediate();
        vkDeviceWaitIdle(this.ctx.device);
        while (!this.inFlight.isEmpty()) {
            vkDestroyEvent(this.ctx.device, this.inFlight.pop().event, null);
        }
        //Device is idle: every recorded frame — including the one currently being
        // recorded — has completed, so even current-frame-tagged destroys are safe.
        this.retiredCounter = this.frameCounter;
        this.runRetirement();
    }

    private void runRetirement() {
        for (var l : this.retireListeners) {
            l.onFramesRetired(this.retiredCounter);
        }
        this.pendingDestroys.removeIf(d -> {
            if (d.frameIdx <= this.retiredCounter) {
                if (d.buffer != VK_NULL_HANDLE) vkDestroyBuffer(this.ctx.device, d.buffer, null);
                if (d.imageView != VK_NULL_HANDLE) vkDestroyImageView(this.ctx.device, d.imageView, null);
                if (d.image != VK_NULL_HANDLE) vkDestroyImage(this.ctx.device, d.image, null);
                if (d.memory != VK_NULL_HANDLE) vkFreeMemory(this.ctx.device, d.memory, null);
                return true;
            }
            return false;
        });
    }

    private long obtainEvent() {
        if (!this.eventPool.isEmpty()) {
            return this.eventPool.remove(this.eventPool.size() - 1);
        }
        try (MemoryStack stack = stackPush()) {
            var eci = VkEventCreateInfo.calloc(stack).sType$Default();
            var pEvent = stack.mallocLong(1);
            check(vkCreateEvent(this.ctx.device, eci, null, pEvent), "vkCreateEvent");
            return pEvent.get(0);
        }
    }

    //==================================================================================
    // Deferred destruction

    public void deferDestroy(long buffer, long memory) {
        this.pendingDestroys.add(new PendingDestroy(this.frameCounter, buffer, VK_NULL_HANDLE, VK_NULL_HANDLE, memory));
    }

    public void deferDestroyImage(long image, long view, long memory) {
        this.pendingDestroys.add(new PendingDestroy(this.frameCounter, VK_NULL_HANDLE, image, view, memory));
    }

    //==================================================================================
    // Command helpers

    public void fillBuffer(VkBuffer buffer, long offset, long size, int value) {
        vkCmdFillBuffer(this.cmd(), buffer.buffer, offset, size, value);
        this.barrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
    }

    /** Global memory barrier in the current command buffer. */
    public void barrier(int srcStage, int srcAccess, int dstStage, int dstAccess) {
        try (MemoryStack stack = stackPush()) {
            var mb = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(srcAccess).dstAccessMask(dstAccess);
            vkCmdPipelineBarrier(this.cmd(), srcStage, dstStage, 0, mb, null, null);
        }
    }

    /** Compute-write -> compute-read barrier (compute-to-compute chaining). */
    public void computeToComputeBarrier() {
        this.barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
    }

    /** Compute-write -> draw-indirect/vertex-read barrier (cmdgen -> raster draw). */
    public void computeToDrawBarrier() {
        this.barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_VERTEX_INPUT_BIT,
                VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_SHADER_READ_BIT);
    }

    /** Compute-write -> transfer-read barrier (compute output -> download copy). */
    public void computeToTransferBarrier() {
        this.barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
    }

    public void free() {
        this.waitIdleRetireAll();
        for (long event : this.eventPool) {
            vkDestroyEvent(this.ctx.device, event, null);
        }
        this.eventPool.clear();
        if (!this.pendingDestroys.isEmpty()) {
            Logger.warn("VkFrameCtx freed with " + this.pendingDestroys.size() + " pending destroys remaining");
        }
    }
}
