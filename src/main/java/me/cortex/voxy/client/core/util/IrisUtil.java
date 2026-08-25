package me.cortex.voxy.client.core.util;

import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.shadows.ShadowRenderer;

import java.util.function.Supplier;

/** Compatibility boundary for the Iris 1.7 API shipped for Minecraft 1.20.6. */
public final class IrisUtil {
    public static final boolean IRIS_INSTALLED = FabricLoader.getInstance().isModLoaded("iris");
    public static final boolean SHADER_SUPPORT = false;

    private IrisUtil() {
    }

    public static boolean irisShadowActive() {
        return IRIS_INSTALLED && ShadowRenderer.ACTIVE;
    }

    public static boolean irisShaderPackEnabled() {
        if (!IRIS_INSTALLED) {
            return false;
        }
        try {
            return IrisApi.getInstance().isShaderPackInUse();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean irisShaderpackActiveSafe() {
        return irisShaderPackEnabled();
    }

    public static boolean irisShadersEnabledInConfig() {
        return irisShaderPackEnabled();
    }

    public static <T> T runWithoutVertexFormatExtension(Supplier<T> action) {
        return action.get();
    }

    public static void clearIrisSamplers() {
        // Iris 1.7 restores the sampler state through its existing Sodium hook.
    }

    public static void reload() {
        // The current Voxy Iris pipeline targets a newer Iris API. 1.20.6 uses
        // the native compatibility behavior and does not install that pipeline.
    }

    public static void disableIrisShaders() {
        if (IRIS_INSTALLED) {
            IrisApi.getInstance().getConfig().setShadersEnabledAndApply(false);
        }
    }
}
