package me.cortex.voxy.client.core.util;

import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.gl.IrisRenderSystem;

import java.io.IOException;
import java.util.function.Supplier;

/** Compatibility boundary for the public Iris API available on this Minecraft range. */
public final class IrisUtil {
    public static final boolean IRIS_INSTALLED = FabricLoader.getInstance().isModLoaded("iris");
    public static final boolean SHADER_SUPPORT = true;

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
        // The native renderer never constructs Minecraft BufferBuilders. Iris
        // 1.7 also predates the thread-local vertex-format opt-out used by dev.
        return action.get();
    }

    public static int textureUnitCleanupCount() {
        // Iris expands Minecraft's legacy 12-entry texture-state array. Voxy's
        // shader-pack bindings currently reach unit 17, so clear a full 32-unit
        // range whenever Iris is present while retaining the vanilla-safe limit.
        return IRIS_INSTALLED ? 32 : 12;
    }

    public static void clearIrisSamplers(int unitCount) {
        if (!IRIS_INSTALLED) {
            return;
        }
        for (int unit = 0; unit < unitCount; unit++) {
            IrisRenderSystem.bindSamplerToUnit(unit, 0);
        }
    }

    public static void reload() {
        if (!IRIS_INSTALLED) {
            return;
        }
        try {
            if (IrisApi.getInstance().isShaderPackInUse()
                    || IrisApi.getInstance().getConfig().areShadersEnabled()) {
                Iris.reload();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to reload Iris for the Voxy renderer transition", e);
        }
    }

    public static void disableIrisShaders() {
        if (IRIS_INSTALLED) {
            IrisApi.getInstance().getConfig().setShadersEnabledAndApply(false);
        }
    }
}
