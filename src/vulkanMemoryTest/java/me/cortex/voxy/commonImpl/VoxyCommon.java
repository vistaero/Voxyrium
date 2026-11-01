package me.cortex.voxy.commonImpl;

// Keep the real TrackedObject implementation without initializing Minecraft.
public final class VoxyCommon {
    public static boolean isVerificationFlagOn(String name, boolean fallback) { return fallback; }
    public static boolean isVerificationFlagOn(String name) { return false; }
}
