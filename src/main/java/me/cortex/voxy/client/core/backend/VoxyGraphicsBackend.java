package me.cortex.voxy.client.core.backend;

import com.mojang.blaze3d.systems.GpuDevice;

import java.util.Locale;

/**
 * Selects the Voxy renderer from Minecraft's active Blaze3D device.
 *
 * The existing renderer is OpenGL-specific. Vulkan is deliberately retained as
 * a distinct backend so a Blaze3D implementation can be added without making
 * OpenGL capability queries during device initialization.
 */
public enum VoxyGraphicsBackend {
    UNKNOWN(false, false),
    OPENGL(true, false),
    VULKAN(false, true);

    private static VoxyGraphicsBackend current = UNKNOWN;

    private final boolean rendererImplemented;
    private final boolean blaze3dProbeSupported;

    VoxyGraphicsBackend(boolean rendererImplemented, boolean blaze3dProbeSupported) {
        this.rendererImplemented = rendererImplemented;
        this.blaze3dProbeSupported = blaze3dProbeSupported;
    }

    public static void initialize(GpuDevice device) {
        String backendName = device.getDeviceInfo().backendName().toLowerCase(Locale.ROOT);
        current = switch (backendName) {
            case "opengl" -> OPENGL;
            case "vulkan" -> VULKAN;
            default -> UNKNOWN;
        };
    }

    public static VoxyGraphicsBackend current() {
        return current;
    }

    public boolean hasImplementedRenderer() {
        return this.rendererImplemented;
    }

    /**
     * The Vulkan probe uses only the public Blaze3D device API. It is kept
     * separate from the complete OpenGL renderer until its resource pipeline
     * has been ported.
     */
    public boolean supportsBlaze3dProbe() {
        return this.blaze3dProbeSupported;
    }
}
