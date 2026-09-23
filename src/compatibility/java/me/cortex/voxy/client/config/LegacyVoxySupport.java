package me.cortex.voxy.client.config;

import org.lwjgl.opengl.GL;

/** Capability gate for the OpenGL-only Voxy compatibility builds. */
public final class LegacyVoxySupport {
    private LegacyVoxySupport() {}

    public static boolean isRenderingSupported() {
        try {
            return GL.getCapabilities().OpenGL45;
        } catch (IllegalStateException ignored) {
            return false;
        }
    }
}
