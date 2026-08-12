package me.cortex.voxy.client.core.rendering.util;

//Backend-neutral handle to a GPU buffer. GlBuffer implements this on the
// OpenGL path; VkBuffer on the pure-Vulkan (host) path. The shared CPU-side
// logic (node manager, geometry managers, upload/download streams) traffics
// exclusively in this type so it runs unmodified on both backends; backend-
// specific code casts to its concrete type.
public interface IDeviceBuffer {
    long sizeBytes();

    void free();
}
