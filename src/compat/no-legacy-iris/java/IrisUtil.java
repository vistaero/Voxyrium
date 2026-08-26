package me.cortex.voxy.client.core.util;

import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.api.v0.IrisApi;

import java.util.function.Supplier;

/** Compatibility boundary for Iris releases without the Voxy shader pipeline. */
public final class IrisUtil {
    public static final boolean IRIS_INSTALLED = FabricLoader.getInstance().isModLoaded("iris");
    public static final boolean SHADER_SUPPORT = false;

    private IrisUtil() {
    }

    public static boolean irisShadowActive() {
        if (!IRIS_INSTALLED) {
            return false;
        }
        try {
            return IrisApi.getInstance().isRenderingShadowPass();
        } catch (Throwable ignored) {
            return false;
        }
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
        // Iris restores the sampler state through its existing Sodium hook.
    }

    public static void reload() {
        // These versions intentionally retain the native compatibility path.
    }

    public static void disableIrisShaders() {
        if (IRIS_INSTALLED) {
            IrisApi.getInstance().getConfig().setShadersEnabledAndApply(false);
        }
    }
}
