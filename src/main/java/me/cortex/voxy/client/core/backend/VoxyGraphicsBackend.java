package me.cortex.voxy.client.core.backend;

import com.mojang.blaze3d.systems.GpuDevice;

import java.util.Locale;

/** Selects a Voxy renderer on top of Minecraft's active graphics API. */
public enum VoxyGraphicsBackend {
    UNKNOWN,
    OPENGL,
    VULKAN;

    public enum RendererMode {
        NATIVE,
        BLAZE3D
    }

    public enum ActiveRenderer {
        UNAVAILABLE,
        NATIVE,
        BLAZE3D
    }

    private static VoxyGraphicsBackend current = UNKNOWN;
    private static ActiveRenderer activeRenderer = ActiveRenderer.UNAVAILABLE;
    private static boolean nativeRendererAvailable;

    public static void initialize(GpuDevice device) {
        String backendName = device.getDeviceInfo().backendName().toLowerCase(Locale.ROOT);
        if (backendName.contains("vulkan")) {
            current = VULKAN;
        } else if (backendName.contains("opengl")) {
            current = OPENGL;
        } else {
            current = UNKNOWN;
        }
        nativeRendererAvailable = false;
        activeRenderer = ActiveRenderer.UNAVAILABLE;
    }

    public static VoxyGraphicsBackend current() {
        return current;
    }

    public static void resolveRenderer(RendererMode preference, boolean nativeAvailable) {
        nativeRendererAvailable = nativeAvailable;
        applyPreference(preference);
    }

    /** Keeps all Voxy render paths disabled while an interactive choice is pending. */
    public static void deferRendererSelection() {
        nativeRendererAvailable = false;
        activeRenderer = ActiveRenderer.UNAVAILABLE;
    }

    public static void applyPreference(RendererMode preference) {
        RendererMode resolvedPreference = preference == null ? RendererMode.NATIVE : preference;
        activeRenderer = switch (resolvedPreference) {
            case BLAZE3D -> ActiveRenderer.BLAZE3D;
            case NATIVE -> nativeRendererAvailable ? ActiveRenderer.NATIVE : ActiveRenderer.UNAVAILABLE;
        };
    }

    public static ActiveRenderer activeRenderer() {
        return activeRenderer;
    }

    public static boolean isNativeRendererAvailable() {
        return nativeRendererAvailable;
    }

    public static boolean usesNativeRenderer() {
        return activeRenderer == ActiveRenderer.NATIVE;
    }

    public static boolean usesBlaze3dRenderer() {
        return activeRenderer == ActiveRenderer.BLAZE3D;
    }

    public static boolean usesNativeVulkanRenderer() {
        return current == VULKAN && usesNativeRenderer();
    }

    public static boolean usesNativeOpenGlRenderer() {
        return current == OPENGL && usesNativeRenderer();
    }

    public boolean hasImplementedRenderer() {
        return usesNativeRenderer();
    }

    /**
     * Kept for the existing mixin call sites. The alternative renderer only
     * uses public Blaze3D APIs, so it works on both OpenGL and Vulkan devices.
     */
    public boolean supportsBlaze3dProbe() {
        return usesBlaze3dRenderer();
    }

    public static String statusLine() {
        return "minecraft=" + current.name().toLowerCase(Locale.ROOT)
                + ", renderer=" + activeRenderer.name().toLowerCase(Locale.ROOT)
                + ", nativeAvailable=" + nativeRendererAvailable;
    }
}
