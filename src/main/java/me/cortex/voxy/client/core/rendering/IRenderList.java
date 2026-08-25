package me.cortex.voxy.client.core.rendering;

/**
 * Backend-neutral handle to the hierarchical traversal's render list output.
 * The GL path implements this directly on {@link me.cortex.voxy.client.core.gl.GlBuffer};
 * the Vulkan path implements it on a VK-allocated, GL-imported shared buffer so the
 * (phase-1, still-GL) traversal compute can keep writing it with zero changes.
 */
public interface IRenderList {
    /** GL buffer name usable with glBindBufferBase from the traversal compute pass. */
    int glId();
    /** Size of the buffer in bytes. */
    long sizeBytes();
}
