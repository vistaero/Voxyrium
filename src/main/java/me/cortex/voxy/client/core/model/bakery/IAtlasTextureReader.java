package me.cortex.voxy.client.core.model.bakery;

import com.mojang.blaze3d.textures.GpuTexture;

//Backend-neutral readback of MC's stitched block atlas into a CPU int[]
// (RGBA8, one int per texel, byte order R,G,B,A — identical for GL
// RGBA/UNSIGNED_BYTE and VK_FORMAT_R8G8B8A8_UNORM). The software model-texture
// rasterizer samples this array on worker threads.
//
//The GL default lazily loads GlAtlasTextureReader on first use (there is always
// a GL context by then). When MC is on Vulkan, VkRenderCore installs the VK
// implementation via setInstance BEFORE the model bakery is constructed, so the
// GL class — and any GL classload — never happens on the VK path. Mirrors the
// AbstractUploadStream/AbstractDownloadStream seam pattern.
public abstract class IAtlasTextureReader {
    /** Reads mip 0 of the given RGBA8 atlas into a fresh {@code int[width*height]}. */
    public abstract int[] read(GpuTexture atlas, int width, int height);

    private static IAtlasTextureReader INSTANCE;

    public static IAtlasTextureReader INSTANCE() {
        var i = INSTANCE;
        if (i == null) i = INSTANCE = new GlAtlasTextureReader();
        return i;
    }

    public static void setInstance(IAtlasTextureReader instance) {
        if (INSTANCE != null) throw new IllegalStateException("Atlas texture reader already initialized");
        INSTANCE = instance;
    }

    /**
     * Temporarily replaces the process-wide reader and returns the previous one.
     * Renderer transitions may already have initialized the OpenGL reader; callers
     * that only need a scoped reader must restore the returned instance in finally.
     */
    public static IAtlasTextureReader replaceInstance(IAtlasTextureReader instance) {
        IAtlasTextureReader previous = INSTANCE;
        INSTANCE = instance;
        return previous;
    }

    public static void clearInstance() {
        INSTANCE = null;
    }
}
